package io.github.captheorem.spectral;

public final class LeaseExpiredException extends SpectralException {
    public LeaseExpiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
