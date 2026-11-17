package io.github.captheorem.spectral;

import java.time.Duration;
import java.time.Instant;

public record Lease(String token, Duration ttl, Instant expiresAt) {
}
