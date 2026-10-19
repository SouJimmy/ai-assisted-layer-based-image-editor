package jp.ac.example.imageeditor.internal.integration;

public final class ServiceFailure extends RuntimeException {
    private final String code, requestId;
    private final int httpStatus;
    private final Boolean retryable;

    public ServiceFailure(String code, String message, String requestId, int httpStatus) {
        this(code, message, requestId, httpStatus, null);
    }

    public ServiceFailure(
            String code, String message, String requestId, int httpStatus, Boolean retryable) {
        super(message);
        this.retryable = retryable;
        this.code = code;
        this.requestId = requestId;
        this.httpStatus = httpStatus;
    }

    public ServiceFailure(String code, String message, Throwable cause) {
        super(message, cause);
        this.retryable = null;
        this.code = code;
        this.requestId = null;
        this.httpStatus = 0;
    }

    public String code() {
        return code;
    }

    public String requestId() {
        return requestId;
    }

    public java.util.Optional<Boolean> retryable() {
        return java.util.Optional.ofNullable(retryable);
    }

    public int httpStatus() {
        return httpStatus;
    }
}
