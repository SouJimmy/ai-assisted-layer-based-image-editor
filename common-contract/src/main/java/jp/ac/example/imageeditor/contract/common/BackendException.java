package jp.ac.example.imageeditor.contract.common;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public abstract class BackendException extends Exception {
    private final ErrorCode code;
    private final String requestId;
    private final int httpStatus;
    private final boolean retryable;

    protected BackendException(
            ErrorCode code,
            String message,
            String requestId,
            int httpStatus,
            boolean retryable,
            Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code);
        this.requestId = requestId;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public final ErrorCode code() {
        return code;
    }

    public final Optional<String> requestId() {
        return Optional.ofNullable(requestId);
    }

    public final OptionalInt httpStatus() {
        return httpStatus == 0 ? OptionalInt.empty() : OptionalInt.of(httpStatus);
    }

    public final boolean retryable() {
        return retryable;
    }
}
