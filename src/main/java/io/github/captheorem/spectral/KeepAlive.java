package io.github.captheorem.spectral;

import java.time.Duration;
import java.util.Optional;

public final class KeepAlive implements AutoCloseable {
    private final SpectralClient client;
    private final Lease lease;
    private final Duration interval;
    private final Thread thread;

    private volatile boolean stopped;
    private volatile SpectralException failure;

    private KeepAlive(SpectralClient client, Lease lease, Duration interval) {
        this.client = client;
        this.lease = lease;
        this.interval = interval;
        this.thread = new Thread(this::run, "spectral-keepalive");
        this.thread.setDaemon(true);
    }

    static KeepAlive start(SpectralClient client, Lease lease) {
        Duration interval = lease.ttl().dividedBy(3);
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("spectral keepAlive: lease ttl " + lease.ttl() + " is too short");
        }
        KeepAlive keepAlive = new KeepAlive(client, lease, interval);
        keepAlive.thread.start();
        return keepAlive;
    }

    public boolean isRunning() {
        return thread.isAlive();
    }

    public Optional<SpectralException> failure() {
        return Optional.ofNullable(failure);
    }

    public void stop() {
        stopped = true;
        thread.interrupt();
        boolean interrupted = false;
        while (true) {
            try {
                thread.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void run() {
        long ttlNanos = lease.ttl().toNanos();
        long deadline = System.nanoTime() + ttlNanos;

        while (!stopped) {
            try {
                Thread.sleep(interval.toMillis(), interval.toNanosPart() % 1_000_000);
            } catch (InterruptedException e) {
                return;
            }
            if (stopped) {
                return;
            }

            long now = System.nanoTime();
            try {
                client.renew(lease.token());
                deadline = now + ttlNanos;
            } catch (LeaseExpiredException e) {
                failure = e;
                return;
            } catch (SpectralException e) {
                if (stopped) {
                    return;
                }
                if (now - deadline >= 0) {
                    failure = new LeaseExpiredException(
                            "spectral keepAlive: lease expired, last renew failed: " + e.getMessage(), e);
                    return;
                }
            }
        }
    }
}
