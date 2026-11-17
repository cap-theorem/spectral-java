package io.github.captheorem.spectral;

public final class ServiceNotFoundException extends ApiException {
    public ServiceNotFoundException(Op op, int statusCode, String body) {
        super(op, statusCode, body);
    }
}
