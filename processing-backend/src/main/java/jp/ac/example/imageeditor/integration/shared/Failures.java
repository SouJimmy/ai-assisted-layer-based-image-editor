package jp.ac.example.imageeditor.integration.shared;

import jp.ac.example.imageeditor.contract.ai.AiServiceException;
import jp.ac.example.imageeditor.contract.common.*;
import jp.ac.example.imageeditor.contract.psd.PsdImportException;
import jp.ac.example.imageeditor.internal.integration.ServiceFailure;

import java.util.concurrent.*;

public final class Failures {
    private Failures() {}

    public static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error;
    }

    public static ErrorCode code(Throwable error) {
        error = unwrap(error);
        if (error instanceof BackendException b) return b.code();
        if (error instanceof ServiceFailure f) {
            try {
                return ErrorCode.valueOf(f.code());
            } catch (IllegalArgumentException ignored) {
                return ErrorCode.INVALID_RESPONSE;
            }
        }
        if (error instanceof CancellationException || error instanceof InterruptedException)
            return ErrorCode.CANCELLED;
        if (error instanceof IllegalArgumentException || error instanceof NullPointerException)
            return ErrorCode.INVALID_ARGUMENT;
        return ErrorCode.INTERNAL_ERROR;
    }

    public static AiServiceException ai(Throwable error) {
        error = unwrap(error);
        if (error instanceof AiServiceException a) return a;
        return new AiServiceException(
                code(error), "AI処理を完了できません", id(error), status(error), retry(error), error);
    }

    public static PsdImportException psd(Throwable error) {
        error = unwrap(error);
        if (error instanceof PsdImportException p) return p;
        return new PsdImportException(
                code(error), "PSD処理を完了できません", id(error), status(error), retry(error), error);
    }

    private static String id(Throwable e) {
        return e instanceof ServiceFailure f ? f.requestId() : null;
    }

    private static int status(Throwable e) {
        return e instanceof ServiceFailure f ? f.httpStatus() : 0;
    }

    private static boolean retry(Throwable e) {
        if (e instanceof ServiceFailure f && f.retryable().isPresent())
            return f.retryable().orElseThrow();
        return switch (code(e)) {
            case BUSY, TIMEOUT, SERVICE_UNAVAILABLE, INFERENCE_FAILED -> true;
            default -> false;
        };
    }
}
