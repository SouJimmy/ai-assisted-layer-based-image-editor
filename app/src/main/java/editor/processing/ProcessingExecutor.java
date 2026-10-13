package editor.processing;

import static editor.processing.Processing.*;

import java.util.concurrent.*;
import java.util.function.DoubleConsumer;

/** One image worker and two queued jobs; callbacks execute on the worker, not the UI thread. */
public final class ProcessingExecutor implements AutoCloseable {
    private final ThreadPoolExecutor executor =
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
    private final ImageProcessor processor = new ImageProcessor();
    private final java.util.Set<CompletableFuture<Result>> pending = ConcurrentHashMap.newKeySet();

    public CompletableFuture<Result> submit(Request request, DoubleConsumer progress) {
        CompletableFuture<Result> result = new CompletableFuture<>();
        pending.add(result);
        result.whenComplete((value, error) -> pending.remove(result));
        try {
            Future<?> job =
                    executor.submit(
                            () -> {
                                try {
                                    result.complete(
                                            processor.process(
                                                    request,
                                                    new Context(result::isCancelled, progress)));
                                } catch (Throwable e) {
                                    result.completeExceptionally(e);
                                }
                            });
            result.whenComplete(
                    (value, error) -> {
                        if (result.isCancelled()) {
                            job.cancel(true);
                            executor.purge();
                        }
                    });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(new Failure(Code.BUSY, "画像処理キューが満杯、または終了済みです"));
        }
        return result;
    }

    public CompletableFuture<Result> submit(Request request) {
        return submit(request, ignored -> {});
    }

    @Override
    public void close() {
        for (var job : pending) job.cancel(true);
        executor.shutdownNow();
    }
}
