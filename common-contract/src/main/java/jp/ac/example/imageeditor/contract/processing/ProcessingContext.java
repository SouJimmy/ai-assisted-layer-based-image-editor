package jp.ac.example.imageeditor.contract.processing;

import jp.ac.example.imageeditor.contract.common.CancellationToken;
import jp.ac.example.imageeditor.contract.common.ProgressListener;

import java.util.Objects;

public record ProcessingContext(
        CancellationToken cancellationToken, ProgressListener progressListener) {
    public ProcessingContext {
        Objects.requireNonNull(cancellationToken);
        Objects.requireNonNull(progressListener);
    }

    public static ProcessingContext defaults() {
        return new ProcessingContext(() -> false, ignored -> {});
    }
}
