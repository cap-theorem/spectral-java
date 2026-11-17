package io.github.captheorem.spectral;

public final class InvalidRegistrationException extends ApiException {
    public InvalidRegistrationException(Op op, int statusCode, String body) {
        super(op, statusCode, body);
    }
}
