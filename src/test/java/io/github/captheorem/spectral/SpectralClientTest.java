package io.github.captheorem.spectral;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpectralClientTest {
    private static final Registration REGISTRATION =
            new Registration("payments", "payments-1", "http://100.64.1.3:9007", Duration.ofSeconds(10));

    @Test
    void addrResolution() {
        assertEquals(SpectralClient.DEFAULT_ADDR, SpectralClient.builder().env(Map.of()).build().addr());
        assertEquals(
                "http://10.0.0.5:8007",
                SpectralClient.builder().env(Map.of(SpectralClient.ADDR_ENV, "http://10.0.0.5:8007")).build().addr());
        assertEquals(
                "http://127.0.0.1:8003",
                SpectralClient.builder()
                        .addr("http://127.0.0.1:8003")
                        .env(Map.of(SpectralClient.ADDR_ENV, "http://10.0.0.5:8007"))
                        .build()
                        .addr());
        assertEquals("http://127.0.0.1:8003", SpectralClient.builder().addr("http://127.0.0.1:8003/").build().addr());
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1:8000", "ftp://127.0.0.1:8000", "http://", "not a url"})
    void rejectsBadAddr(String addr) {
        assertThrows(IllegalArgumentException.class, () -> SpectralClient.builder().addr(addr).build());
    }

    @Test
    void registerSendsRegistrationAndParsesLease() {
        try (FakeSidecar fake = new FakeSidecar(req -> FakeSidecar.Reply.json(201,
                "{\"token\":\"abc123\",\"ttl\":\"10s\",\"expires_at\":\"2026-11-16T12:00:10.123456789-08:00\"}"))) {
            Lease lease = fake.client().register(REGISTRATION);

            FakeSidecar.Request req = fake.requests.get(0);
            assertEquals("POST", req.method());
            assertEquals("/register", req.path());
            assertEquals("application/json", req.contentType());
            assertEquals(
                    Map.of(
                            "service", "payments",
                            "instance_id", "payments-1",
                            "endpoint", "http://100.64.1.3:9007",
                            "ttl", "10s"),
                    Json.parseObject(req.body()));
            assertEquals("abc123", lease.token());
            assertEquals(Duration.ofSeconds(10), lease.ttl());
            assertEquals(Instant.parse("2026-11-16T20:00:10.123456789Z"), lease.expiresAt());
        }
    }

    @Test
    void registerMapsErrors() {
        Map<Integer, Class<? extends ApiException>> cases = Map.of(
                400, InvalidRegistrationException.class,
                409, AlreadyRegisteredException.class,
                504, SidecarTimeoutException.class,
                500, ApiException.class);

        cases.forEach((status, expected) -> {
            try (FakeSidecar fake = new FakeSidecar(req -> FakeSidecar.Reply.text(status, "nope\n"))) {
                ApiException err = assertThrows(ApiException.class, () -> fake.client().register(REGISTRATION));
                assertSame(expected, err.getClass());
                assertEquals(Op.REGISTER, err.op());
                assertEquals(status, err.statusCode());
                assertEquals("nope", err.body());
            }
        });
    }

    @Test
    void renewSendsToken() {
        try (FakeSidecar fake = new FakeSidecar(req -> FakeSidecar.Reply.empty(202))) {
            fake.client().renew("abc123");

            FakeSidecar.Request req = fake.requests.get(0);
            assertEquals("POST", req.method());
            assertEquals("/renew", req.path());
            assertEquals(Map.of("token", "abc123"), Json.parseObject(req.body()));
        }
    }

    @Test
    void renewUnknownTokenIsLeaseExpired() {
        try (FakeSidecar fake = new FakeSidecar(req -> FakeSidecar.Reply.text(404, "unknown lease"))) {
            LeaseExpiredException err = assertThrows(LeaseExpiredException.class, () -> fake.client().renew("gone"));
            assertInstanceOf(ApiException.class, err.getCause());
        }
    }

    @Test
    void lookupQueriesByService() {
        try (FakeSidecar fake = new FakeSidecar(req -> FakeSidecar.Reply.json(200,
                "{\"service\":\"model-server/llama 70b\",\"instance_id\":\"ms-0412\",\"endpoint\":\"http://100.64.1.6:9002\"}"))) {
            Provider provider = fake.client().lookup("model-server/llama 70b");

            FakeSidecar.Request req = fake.requests.get(0);
            assertEquals("GET", req.method());
            assertEquals("/lookup", req.path());
            assertEquals(Map.of("service", "model-server/llama 70b"), req.query());
            assertEquals(new Provider("model-server/llama 70b", "ms-0412", "http://100.64.1.6:9002"), provider);
        }
    }

    @Test
    void lookupNotFound() {
        try (FakeSidecar fake = new FakeSidecar(req -> FakeSidecar.Reply.text(404, "no providers"))) {
            assertThrows(ServiceNotFoundException.class, () -> fake.client().lookup("inventory"));
        }
    }

    @Test
    void statusParsesResponse() {
        try (FakeSidecar fake = new FakeSidecar(req -> FakeSidecar.Reply.json(200, "{\"ready\":true,\"state\":\"active\"}"))) {
            assertEquals(new Status(true, "active"), fake.client().status());
            assertEquals("/status", fake.requests.get(0).path());
        }
    }

    @Test
    void statusRejectsMalformedResponse() {
        try (FakeSidecar fake = new FakeSidecar(req -> FakeSidecar.Reply.json(200, "{\"state\":\"active\"}"))) {
            SpectralException err = assertThrows(SpectralException.class, () -> fake.client().status());
            assertTrue(err.getMessage().contains("missing \"ready\""), err.getMessage());
        }
    }

    @Test
    void keepsBasePathPrefix() {
        try (FakeSidecar fake = new FakeSidecar(req -> FakeSidecar.Reply.json(200, "{\"ready\":true,\"state\":\"active\"}"))) {
            SpectralClient.builder().addr(fake.url() + "/sidecar/").build().status();
            assertEquals("/sidecar/status", fake.requests.get(0).path());
        }
    }

    @Test
    void unreachableSidecar() {
        String url;
        try (FakeSidecar fake = new FakeSidecar(req -> FakeSidecar.Reply.empty(200))) {
            url = fake.url();
        }
        SpectralClient client = SpectralClient.builder().addr(url).timeout(Duration.ofSeconds(2)).build();
        assertThrows(SidecarUnavailableException.class, client::status);
    }
}
