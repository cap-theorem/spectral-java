package io.github.captheorem.spectral;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class KeepAliveTest {
    private static Lease lease(Duration ttl) {
        return new Lease("abc123", ttl, Instant.now().plus(ttl));
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "condition never became true");
            Thread.sleep(5);
        }
    }

    @Test
    void renewsUntilStopped() throws InterruptedException {
        AtomicInteger renews = new AtomicInteger();
        try (FakeSidecar fake = new FakeSidecar(req -> {
            renews.incrementAndGet();
            return FakeSidecar.Reply.empty(202);
        })) {
            KeepAlive keeper = fake.client().keepAlive(lease(Duration.ofMillis(60)));
            waitFor(() -> renews.get() >= 3);

            assertTrue(keeper.isRunning());
            keeper.stop();
            assertFalse(keeper.isRunning());
            assertTrue(keeper.failure().isEmpty());
        }
    }

    @Test
    void stopsWhenLeaseRejected() throws InterruptedException {
        try (FakeSidecar fake = new FakeSidecar(req -> FakeSidecar.Reply.text(404, "unknown lease"))) {
            KeepAlive keeper = fake.client().keepAlive(lease(Duration.ofMillis(60)));
            waitFor(() -> !keeper.isRunning());

            assertInstanceOf(LeaseExpiredException.class, keeper.failure().orElseThrow());
        }
    }

    @Test
    void givesUpAfterTtl() throws InterruptedException {
        try (FakeSidecar fake = new FakeSidecar(req -> FakeSidecar.Reply.text(500, "boom"))) {
            long start = System.nanoTime();
            KeepAlive keeper = fake.client().keepAlive(lease(Duration.ofMillis(60)));
            waitFor(() -> !keeper.isRunning());

            assertTrue(System.nanoTime() - start >= Duration.ofMillis(60).toNanos());
            SpectralException failure = keeper.failure().orElseThrow();
            assertInstanceOf(LeaseExpiredException.class, failure);
            ApiException cause = assertInstanceOf(ApiException.class, failure.getCause());
            assertEquals(500, cause.statusCode());
        }
    }

    @Test
    void retriesTransientFailure() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        try (FakeSidecar fake = new FakeSidecar(req -> calls.incrementAndGet() == 1
                ? FakeSidecar.Reply.text(500, "blip")
                : FakeSidecar.Reply.empty(202))) {
            try (KeepAlive keeper = fake.client().keepAlive(lease(Duration.ofMillis(60)))) {
                waitFor(() -> calls.get() >= 3);
                assertTrue(keeper.isRunning());
            }
        }
    }

    @Test
    void rejectsTtlTooShort() {
        try (FakeSidecar fake = new FakeSidecar(req -> FakeSidecar.Reply.empty(202))) {
            assertThrows(IllegalArgumentException.class, () -> fake.client().keepAlive(lease(Duration.ofNanos(2))));
        }
    }
}
