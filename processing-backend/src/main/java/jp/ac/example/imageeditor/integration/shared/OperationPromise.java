package jp.ac.example.imageeditor.integration.shared;

import jp.ac.example.imageeditor.contract.common.*;

import java.util.concurrent.*;
import java.util.function.Supplier;

/** One atomic terminal decision, with a read-only completion view. */
public final class OperationPromise<T> implements AsyncOperation<T> {
    private final CompletableFuture<T> result = new CompletableFuture<>();
    private final CompletionStage<T> view = result.minimalCompletionStage();
    private final Supplier<? extends BackendException> cancellation;
    private volatile Runnable cancelAction = () -> {};
    private volatile boolean cancelled;

    public OperationPromise(Supplier<? extends BackendException> cancellation) {
        this.cancellation = cancellation;
    }

    public CompletionStage<T> stage() {
        return view;
    }

    public synchronized boolean succeed(T value) {
        return result.complete(value);
    }

    public synchronized boolean fail(Throwable error) {
        return result.completeExceptionally(error);
    }

    public synchronized boolean cancel() {
        if (result.isDone()) return false;
        cancelled = true;
        result.completeExceptionally(cancellation.get());
        cancelAction.run();
        return true;
    }

    public synchronized void onCancel(Runnable action) {
        cancelAction = action;
        if (cancelled) action.run();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean isDone() {
        return result.isDone();
    }
}
