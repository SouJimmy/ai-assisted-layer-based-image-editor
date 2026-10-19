package jp.ac.example.imageeditor.contract.processing;

import jp.ac.example.imageeditor.contract.common.BackendException;
import jp.ac.example.imageeditor.contract.common.ErrorCode;

public final class ProcessingException extends BackendException {
    public ProcessingException(
            ErrorCode code,
            String message,
            String requestId,
            int httpStatus,
            boolean retryable,
            Throwable cause) {
        super(code, message, requestId, httpStatus, retryable, cause);
    }

    public ProcessingException(ErrorCode code, String message, Throwable cause) {
        this(code, message, null, 0, false, cause);
    }
}
