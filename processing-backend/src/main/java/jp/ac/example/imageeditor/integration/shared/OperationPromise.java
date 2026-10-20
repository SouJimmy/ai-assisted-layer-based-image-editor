package jp.ac.example.imageeditor.integration.shared;

import jp.ac.example.imageeditor.contract.common.*;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** One atomic terminal decision, with a read-only completion view. */
public final class OperationPromise<T> implements AsyncOperation<T> {
    private final CompletableFuture<T> result = new CompletableFuture<>();
    private final CompletionStage<T> view = result.minimalCompletionStage();
    private final Supplier<? extends BackendException> cancellation;
    private volatile Runnable cancelAction = () -> {};
    private volatile boolean cancelled;
    private volatile boolean decided;

    public OperationPromise(Supplier<? extends BackendException> cancellation) {
        this.cancellation = Objects.requireNonNull(cancellation);
    }

    public CompletionStage<T> stage() {
        return view;
    }

    public boolean succeed(T value) {
        if (!decide()) return false;
        result.complete(value);
        return true;
    }

    public boolean fail(Throwable error) {
        Objects.requireNonNull(error);
        if (!decide()) return false;
        result.completeExceptionally(error);
        return true;
    }

    // Reserve the terminal outcome before completing: callbacks may call back into this object.
    private synchronized boolean decide() {
        if (decided) return false;
        decided = true;
        return true;
    }

    public boolean cancel() {
        Runnable action;
        BackendException error;
        synchronized (this) {
            if (decided) return false;
            error = Objects.requireNonNull(cancellation.get());
            decided = true;
            cancelled = true;
            action = cancelAction;
        }
        result.completeExceptionally(error);
        action.run();
        return true;
    }

    public void onCancel(Runnable action) {
        Objects.requireNonNull(action);
        boolean runNow;
        synchronized (this) {
            cancelAction = action;
            runNow = cancelled;
        }
        if (runNow) action.run();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean isDone() {
        return decided;
    }
}
