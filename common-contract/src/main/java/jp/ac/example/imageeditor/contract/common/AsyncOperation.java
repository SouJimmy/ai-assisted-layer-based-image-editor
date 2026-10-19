package jp.ac.example.imageeditor.contract.common;

import java.util.concurrent.CompletionStage;

public interface AsyncOperation<T> {
    CompletionStage<T> stage();

    boolean cancel();
}
