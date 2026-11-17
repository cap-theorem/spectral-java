package io.github.captheorem.spectral;

public final class SidecarTimeoutException extends ApiException {
    public SidecarTimeoutException(Op op, int statusCode, String body) {
        super(op, statusCode, body);
    }
}
