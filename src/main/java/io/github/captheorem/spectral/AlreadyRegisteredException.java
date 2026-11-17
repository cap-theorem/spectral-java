package io.github.captheorem.spectral;

public final class AlreadyRegisteredException extends ApiException {
    public AlreadyRegisteredException(Op op, int statusCode, String body) {
        super(op, statusCode, body);
    }
}
