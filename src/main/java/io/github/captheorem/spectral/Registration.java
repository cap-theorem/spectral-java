package io.github.captheorem.spectral;

import java.time.Duration;
import java.util.Objects;

public record Registration(String service, String instanceId, String endpoint, Duration ttl) {
    public Registration {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(ttl, "ttl");
    }
}
