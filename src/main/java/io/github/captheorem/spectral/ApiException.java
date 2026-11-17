package io.github.captheorem.spectral;

public class ApiException extends SpectralException {
    private final Op op;
    private final int statusCode;
    private final String body;

    public ApiException(Op op, int statusCode, String body) {
        super(body.isEmpty()
                ? "spectral " + op + ": http " + statusCode
                : "spectral " + op + ": http " + statusCode + ": " + body);
        this.op = op;
        this.statusCode = statusCode;
        this.body = body;
    }

    public Op op() {
        return op;
    }

    public int statusCode() {
        return statusCode;
    }

    public String body() {
        return body;
    }

    static SpectralException fromStatus(Op op, int statusCode, String body) {
        if (statusCode == 504) {
            return new SidecarTimeoutException(op, statusCode, body);
        }
        if (op == Op.REGISTER && statusCode == 400) {
            return new InvalidRegistrationException(op, statusCode, body);
        }
        if (op == Op.REGISTER && statusCode == 409) {
            return new AlreadyRegisteredException(op, statusCode, body);
        }
        if (op == Op.LOOKUP && statusCode == 404) {
            return new ServiceNotFoundException(op, statusCode, body);
        }
        if (op == Op.RENEW && statusCode == 404) {
            ApiException cause = new ApiException(op, statusCode, body);
            return new LeaseExpiredException(cause.getMessage(), cause);
        }
        return new ApiException(op, statusCode, body);
    }
}
