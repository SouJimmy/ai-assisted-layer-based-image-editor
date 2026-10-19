package jp.ac.example.imageeditor.contract.common;

import java.util.Objects;
import java.util.Optional;

public record ServiceError(
        ErrorCode code, String message, Optional<String> requestId, boolean retryable) {
    public ServiceError {
        Objects.requireNonNull(code);
        Objects.requireNonNull(message);
        Objects.requireNonNull(requestId);
    }
}
