package jp.ac.example.imageeditor.testing;

import static org.junit.jupiter.api.Assertions.*;

import jp.ac.example.imageeditor.contract.common.ErrorCode;
import jp.ac.example.imageeditor.contract.processing.ProcessingException;
import jp.ac.example.imageeditor.integration.shared.OperationPromise;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class OperationPromiseTest {
    private static OperationPromise<String> promise() {
        return new OperationPromise<>(
                () -> new ProcessingException(ErrorCode.CANCELLED, "cancel", null));
    }

    @Test
    void completionCallbacksAndCancellationHooksRunOutsideThePromiseLock() throws Exception {
        for (String outcome : new String[] {"success", "failure", "cancel"}) {
            var operation = promise();
            var callback = operation.stage().handle((value, error) -> Thread.holdsLock(operation));
            operation.onCancel(() -> assertFalse(Thread.holdsLock(operation)));
            switch (outcome) {
                case "success" -> operation.succeed("ok");
                case "failure" -> operation.fail(new IllegalStateException("failed"));
                case "cancel" -> operation.cancel();
            }
            assertFalse(callback.toCompletableFuture().get(1, TimeUnit.SECONDS), outcome);
        }
        var cancelled = promise();
        cancelled.cancel();
        cancelled.onCancel(() -> assertFalse(Thread.holdsLock(cancelled)));
    }

    @Test
    void competingTerminalDecisionsHaveExactlyOneWinner() throws Exception {
        try (var workers = Executors.newFixedThreadPool(3)) {
            for (int iteration = 0; iteration < 100; iteration++) {
                var operation = promise();
                var start = new CountDownLatch(1);
                var hooks = new AtomicInteger();
                operation.onCancel(hooks::incrementAndGet);
                var success =
                        workers.submit(
                                () -> {
                                    start.await();
                                    return operation.succeed("ok");
                                });
                var failure =
                        workers.submit(
                                () -> {
                                    start.await();
                                    return operation.fail(new IllegalStateException("failed"));
                                });
                var cancel =
                        workers.submit(
                                () -> {
                                    start.await();
                                    return operation.cancel();
                                });
                start.countDown();
                int winners =
                        (success.get(2, TimeUnit.SECONDS) ? 1 : 0)
                                + (failure.get(2, TimeUnit.SECONDS) ? 1 : 0)
                                + (cancel.get(2, TimeUnit.SECONDS) ? 1 : 0);
                assertEquals(1, winners);
                assertTrue(operation.isDone());
                assertEquals(operation.isCancelled() ? 1 : 0, hooks.get());
                assertFalse(operation.cancel());
            }
        }
    }
}
