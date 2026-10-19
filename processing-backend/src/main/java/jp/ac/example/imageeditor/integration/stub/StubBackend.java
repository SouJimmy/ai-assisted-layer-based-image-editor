package jp.ac.example.imageeditor.integration.stub;

import jp.ac.example.imageeditor.contract.ai.*;
import jp.ac.example.imageeditor.contract.common.*;
import jp.ac.example.imageeditor.contract.processing.*;
import jp.ac.example.imageeditor.contract.psd.*;
import jp.ac.example.imageeditor.integration.shared.OperationPromise;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Manually completed operations for A's success, failure, cancellation and out-of-order UI tests.
 */
public final class StubBackend
        implements ImageProcessor, AiSegmentationService, PsdImportService, AutoCloseable {
    public static final class Pending<T> {
        private final String name;
        private final Object input;
        private final OperationPromise<T> promise;

        private Pending(String name, Object input, Supplier<? extends BackendException> cancelled) {
            this.name = name;
            this.input = input;
            this.promise = new OperationPromise<>(cancelled);
        }

        public String name() {
            return name;
        }

        public Object input() {
            return input;
        }

        public boolean succeed(T value) {
            return promise.succeed(value);
        }

        public boolean fail(BackendException error) {
            return promise.fail(error);
        }

        public AsyncOperation<T> operation() {
            return promise;
        }
    }

    private final BlockingQueue<Pending<?>> pending = new LinkedBlockingQueue<>();
    private final Set<Pending<?>> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private synchronized <T> AsyncOperation<T> enqueue(
            String name, Object input, Supplier<? extends BackendException> cancelled) {
        Pending<T> next = new Pending<>(name, input, cancelled);
        active.add(next);
        next.promise.stage().whenComplete((v, e) -> active.remove(next));
        if (closed) next.promise.cancel();
        else pending.add(next);
        return next.promise;
    }

    public Pending<?> take(long timeout, TimeUnit unit) throws InterruptedException {
        return pending.poll(timeout, unit);
    }

    @Override
    public ProcessingResult process(ProcessingRequest request, ProcessingContext context)
            throws ProcessingException {
        if (context.cancellationToken().isCancellationRequested())
            throw new ProcessingException(ErrorCode.CANCELLED, "Stub処理取消", null);
        var operation = submit(request, context.progressListener());
        try {
            return operation.stage().toCompletableFuture().get();
        } catch (InterruptedException e) {
            operation.cancel();
            Thread.currentThread().interrupt();
            throw new ProcessingException(ErrorCode.CANCELLED, "Stub処理取消", e);
        } catch (ExecutionException e) {
            throw (ProcessingException) e.getCause();
        }
    }

    @Override
    public AsyncOperation<ProcessingResult> submit(
            ProcessingRequest request, ProgressListener progress) {
        return enqueue(
                "process",
                request,
                () -> new ProcessingException(ErrorCode.CANCELLED, "Stub処理取消", null));
    }

    @Override
    public AsyncOperation<RegisteredImage> registerImage(BufferedImage snapshot, String revision) {
        return enqueue(
                "register",
                revision,
                () -> new AiServiceException(ErrorCode.CANCELLED, "Stub登録取消", null));
    }

    @Override
    public AsyncOperation<AiMaskResult> segment(AiRequest request) {
        return enqueue(
                "segment",
                request,
                () -> new AiServiceException(ErrorCode.CANCELLED, "Stub推論取消", null));
    }

    @Override
    public AsyncOperation<Void> releaseImage(RegisteredImage image) {
        return enqueue(
                "release",
                image,
                () -> new AiServiceException(ErrorCode.CANCELLED, "Stub解放取消", null));
    }

    @Override
    public AsyncOperation<PsdImportResult> importPsd(Path path) {
        return enqueue(
                "psd", path, () -> new PsdImportException(ErrorCode.CANCELLED, "Stub取込取消", null));
    }

    @Override
    public void invalidateRequests(RegisteredImage image) {
        for (var item : active)
            if (item.input instanceof AiRequest r && r.image().equals(image)) item.promise.cancel();
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (var item : active) item.promise.cancel();
        pending.clear();
    }
}
