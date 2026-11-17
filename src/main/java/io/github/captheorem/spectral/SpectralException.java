package io.github.captheorem.spectral;

public class SpectralException extends RuntimeException {
    public SpectralException(String message) {
        super(message);
    }

    public SpectralException(String message, Throwable cause) {
        super(message, cause);
    }
}
