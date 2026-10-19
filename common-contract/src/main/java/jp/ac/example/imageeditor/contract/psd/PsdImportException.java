package jp.ac.example.imageeditor.contract.psd;

import jp.ac.example.imageeditor.contract.common.BackendException;
import jp.ac.example.imageeditor.contract.common.ErrorCode;

public final class PsdImportException extends BackendException {
    public PsdImportException(
            ErrorCode code,
            String message,
            String requestId,
            int httpStatus,
            boolean retryable,
            Throwable cause) {
        super(code, message, requestId, httpStatus, retryable, cause);
    }

    public PsdImportException(ErrorCode code, String message, Throwable cause) {
        this(code, message, null, 0, false, cause);
    }
}
