package jp.ac.example.imageeditor.processing;

import jp.ac.example.imageeditor.contract.common.*;
import jp.ac.example.imageeditor.contract.processing.*;
import jp.ac.example.imageeditor.integration.shared.OperationPromise;
import jp.ac.example.imageeditor.internal.processing.Processing;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Shared-contract boundary around the independently verified pixel algorithms. */
public final class DefaultImageProcessor implements ImageProcessor, AutoCloseable {
    private final jp.ac.example.imageeditor.internal.processing.ImageProcessor pixels =
            new jp.ac.example.imageeditor.internal.processing.ImageProcessor();
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(2),
                    r -> {
                        Thread t = new Thread(r, "image-processing");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
    private final Set<OperationPromise<ProcessingResult>> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    @Override
    public ProcessingResult process(ProcessingRequest request, ProcessingContext context)
            throws ProcessingException {
        try {
            if (closed)
                throw new ProcessingException(ErrorCode.SERVICE_UNAVAILABLE, "画像処理を終了しました", null);
            Objects.requireNonNull(request);
            Objects.requireNonNull(context);
            Processing.Parameters parameters = parameters(request.parameters());
            var input =
                    new Processing.Request(
                            request.source(),
                            Processing.Operation.valueOf(request.operation().name()),
                            parameters);
            var output =
                    pixels.process(
                            input,
                            new Processing.Context(
                                    context.cancellationToken()::isCancellationRequested,
                                    v -> {
                                        try {
                                            context.progressListener().onProgress(v);
                                        } catch (RuntimeException ignored) {
                                            System.getLogger(getClass().getName())
                                                    .log(
                                                            System.Logger.Level.WARNING,
                                                            "Progress callback failed");
                                        }
                                    }));
            return new ProcessingResult(
                    output.image(),
                    rect(output.sourceChangedBounds()),
                    rect(output.outputChangedBounds()),
                    output.image().getWidth() != request.source().getWidth()
                            || output.image().getHeight() != request.source().getHeight(),
                    output.deterministic(),
                    output.algorithmVersion(),
                    request.operation(),
                    request.parameters());
        } catch (Processing.Failure e) {
            throw new ProcessingException(ErrorCode.valueOf(e.code().name()), e.getMessage(), e);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ProcessingException(ErrorCode.INVALID_ARGUMENT, "画像処理の入力が不正です", e);
        }
    }

    private static PixelRect rect(Processing.Rect r) {
        return new PixelRect(r.x(), r.y(), r.width(), r.height());
    }

    private static Processing.Parameters parameters(ProcessingParameters value) {
        return switch (Objects.requireNonNull(value)) {
            case NoParameters ignored -> new Processing.None();
            case CropParameters p ->
                    new Processing.Crop(
                            new Processing.Rect(
                                    p.rect().x(),
                                    p.rect().y(),
                                    p.rect().width(),
                                    p.rect().height()));
            case ResizeParameters p -> new Processing.Resize(p.width(), p.height());
            case ColorAdjustParameters p ->
                    new Processing.ColorAdjust(p.brightness(), p.contrast(), p.saturation());
            case BlurParameters p -> new Processing.Blur(p.radius());
            case MosaicParameters p -> new Processing.Mosaic(p.blockSize());
        };
    }

    @Override
    public AsyncOperation<ProcessingResult> submit(
            ProcessingRequest request, ProgressListener progress) {
        OperationPromise<ProcessingResult> operation =
                new OperationPromise<>(
                        () -> new ProcessingException(ErrorCode.CANCELLED, "画像処理を取り消しました", null));
        pending.add(operation);
        operation.stage().whenComplete((v, e) -> pending.remove(operation));
        AtomicReference<Future<?>> task = new AtomicReference<>();
        operation.onCancel(
                () -> {
                    Future<?> f = task.get();
                    if (f != null) f.cancel(true);
                    worker.purge();
                });
        try {
            if (closed) {
                operation.fail(
                        new ProcessingException(
                                ErrorCode.SERVICE_UNAVAILABLE, "画像処理を終了しました", null));
                return operation;
            }
            Future<?> f =
                    worker.submit(
                            () -> {
                                try {
                                    operation.succeed(
                                            process(
                                                    request,
                                                    new ProcessingContext(
                                                            operation::isCancelled, progress)));
                                } catch (ProcessingException e) {
                                    operation.fail(e);
                                } catch (RuntimeException e) {
                                    operation.fail(
                                            new ProcessingException(
                                                    e instanceof NullPointerException
                                                            ? ErrorCode.INVALID_ARGUMENT
                                                            : ErrorCode.INTERNAL_ERROR,
                                                    "画像処理に失敗しました",
                                                    e));
                                }
                            });
            task.set(f);
            if (operation.isCancelled()) {
                f.cancel(true);
                worker.purge();
            }
        } catch (RejectedExecutionException e) {
            operation.fail(
                    new ProcessingException(
                            closed ? ErrorCode.SERVICE_UNAVAILABLE : ErrorCode.BUSY,
                            "画像処理を受け付けられません",
                            e));
        }
        return operation;
    }

    @Override
    public void close() {
        closed = true;
        for (var operation : pending) operation.cancel();
        worker.shutdownNow();
    }
}
