package io.github.captheorem.spectral;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class SpectralClient {
    public static final String VERSION = "0.1.0";
    public static final String DEFAULT_ADDR = "http://127.0.0.1:8000";
    public static final String ADDR_ENV = "SPECTRAL_ADDR";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private static final int MAX_ERROR_BODY = 4096;

    private final String addr;
    private final Duration timeout;
    private final HttpClient http;

    private SpectralClient(String addr, Duration timeout) {
        this.addr = addr;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(timeout)
                .build();
    }

    public static SpectralClient create() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String addr() {
        return addr;
    }

    public Lease register(Registration registration) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("service", registration.service());
        body.put("instance_id", registration.instanceId());
        body.put("endpoint", registration.endpoint());
        body.put("ttl", GoDuration.format(registration.ttl()));

        Map<String, Object> response = object(Op.REGISTER, send(Op.REGISTER, post("/register", body)));
        String ttl = string(Op.REGISTER, response, "ttl");
        String expiresAt = string(Op.REGISTER, response, "expires_at");

        Duration parsedTtl;
        try {
            parsedTtl = GoDuration.parse(ttl);
        } catch (IllegalArgumentException e) {
            throw new SpectralException("spectral register: invalid ttl \"" + ttl + "\" in response", e);
        }

        Instant parsedExpiresAt;
        try {
            parsedExpiresAt = OffsetDateTime.parse(expiresAt).toInstant();
        } catch (DateTimeParseException e) {
            throw new SpectralException("spectral register: invalid expires_at \"" + expiresAt + "\" in response", e);
        }

        return new Lease(string(Op.REGISTER, response, "token"), parsedTtl, parsedExpiresAt);
    }

    public void renew(String token) {
        Objects.requireNonNull(token, "token");
        send(Op.RENEW, post("/renew", Map.of("token", token)));
    }

    public Provider lookup(String service) {
        Objects.requireNonNull(service, "service");
        String query = "?service=" + URLEncoder.encode(service, StandardCharsets.UTF_8);
        Map<String, Object> response = object(Op.LOOKUP, send(Op.LOOKUP, get("/lookup" + query)));

        return new Provider(
                string(Op.LOOKUP, response, "service"),
                string(Op.LOOKUP, response, "instance_id"),
                string(Op.LOOKUP, response, "endpoint"));
    }

    public Status status() {
        Map<String, Object> response = object(Op.STATUS, send(Op.STATUS, get("/status")));
        if (!(response.get("ready") instanceof Boolean ready)) {
            throw new SpectralException("spectral status: invalid response: missing \"ready\"");
        }
        return new Status(ready, string(Op.STATUS, response, "state"));
    }

    public KeepAlive keepAlive(Lease lease) {
        return KeepAlive.start(this, lease);
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create(addr + path)).GET();
    }

    private HttpRequest.Builder post(String path, Map<String, String> body) {
        return HttpRequest.newBuilder(URI.create(addr + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.object(body), StandardCharsets.UTF_8));
    }

    private String send(Op op, HttpRequest.Builder builder) {
        HttpRequest request = builder
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "spectral-java/" + VERSION)
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpectralException("spectral " + op + ": interrupted", e);
        } catch (IOException e) {
            throw new SidecarUnavailableException(
                    "spectral " + op + ": sidecar unavailable at " + addr + ": " + e, e);
        }

        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode > 299) {
            String body = response.body().strip();
            if (body.length() > MAX_ERROR_BODY) {
                body = body.substring(0, MAX_ERROR_BODY);
            }
            throw ApiException.fromStatus(op, statusCode, body);
        }
        return response.body();
    }

    private static Map<String, Object> object(Op op, String body) {
        try {
            return Json.parseObject(body);
        } catch (IllegalArgumentException e) {
            throw new SpectralException("spectral " + op + ": invalid JSON response: " + e.getMessage(), e);
        }
    }

    private static String string(Op op, Map<String, Object> response, String key) {
        if (!(response.get(key) instanceof String value)) {
            throw new SpectralException("spectral " + op + ": invalid response: missing \"" + key + "\"");
        }
        return value;
    }

    public static final class Builder {
        private String addr;
        private Duration timeout = DEFAULT_TIMEOUT;
        private Map<String, String> env = System.getenv();

        private Builder() {
        }

        public Builder addr(String addr) {
            this.addr = addr;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        Builder env(Map<String, String> env) {
            this.env = env;
            return this;
        }

        public SpectralClient build() {
            String resolved = addr;
            if (resolved == null || resolved.isEmpty()) {
                resolved = env.get(ADDR_ENV);
            }
            if (resolved == null || resolved.isEmpty()) {
                resolved = DEFAULT_ADDR;
            }
            while (resolved.endsWith("/")) {
                resolved = resolved.substring(0, resolved.length() - 1);
            }

            validate(resolved);
            return new SpectralClient(resolved, timeout);
        }

        private static void validate(String addr) {
            URI uri;
            try {
                uri = new URI(addr);
            } catch (Exception e) {
                throw new IllegalArgumentException("spectral: invalid addr \"" + addr + "\"", e);
            }
            String scheme = uri.getScheme();
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null || uri.getHost().isEmpty()) {
                throw new IllegalArgumentException("spectral: addr \"" + addr + "\" must be an http(s) URL with a host");
            }
        }
    }
}
