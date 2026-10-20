package jp.ac.example.imageeditor.integration.ai;

import jp.ac.example.imageeditor.contract.ai.*;
import jp.ac.example.imageeditor.contract.common.*;
import jp.ac.example.imageeditor.integration.shared.*;
import jp.ac.example.imageeditor.internal.integration.HelperClient;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Stable facade: every operation is bound to the service instance captured at admission. */
public final class HttpAiSegmentationService implements AiSegmentationService {
    private final Supplier<HelperClient> clients;
    private final Supplier<String> instances;
    private final Map<RegisteredImage, Set<OperationPromise<AiMaskResult>>> requests =
            new ConcurrentHashMap<>();

    public HttpAiSegmentationService(Supplier<HelperClient> clients, Supplier<String> instances) {
        this.clients = clients;
        this.instances = instances;
    }

    private static <T> OperationPromise<T> operation() {
        return new OperationPromise<>(
                () -> new AiServiceException(ErrorCode.CANCELLED, "AI処理を取り消しました", null));
    }

    private static AiServiceException unavailable() {
        return new AiServiceException(ErrorCode.SERVICE_UNAVAILABLE, "サービスが起動していません", null);
    }

    @Override
    public AsyncOperation<RegisteredImage> registerImage(BufferedImage snapshot, String revision) {
        OperationPromise<RegisteredImage> result = operation();
        String instance = instances.get();
        HelperClient client = clients.get();
        if (instance == null || client == null) {
            result.fail(unavailable());
            return result;
        }
        // Keep observing registration after local cancellation, so a known returned ID can be
        // released.
        client.registerImage(snapshot, revision)
                .whenComplete(
                        (image, error) -> {
                            if (error != null) {
                                result.fail(Failures.ai(error));
                                return;
                            }
                            RegisteredImage registered =
                                    new RegisteredImage(
                                            image.imageId(),
                                            image.sourceRevision(),
                                            image.width(),
                                            image.height(),
                                            image.serviceInstanceId());
                            if (!instance.equals(instances.get())
                                    || !instance.equals(image.serviceInstanceId())) {
                                client.releaseImage(image.imageId());
                                result.fail(
                                        new AiServiceException(
                                                ErrorCode.CANCELLED, "起動世代が変わりました", null));
                            } else if (!result.succeed(registered))
                                client.releaseImage(image.imageId());
                        });
        return result;
    }

    @Override
    public AsyncOperation<AiMaskResult> segment(AiRequest request) {
        OperationPromise<AiMaskResult> result = operation();
        if (request == null) {
            result.fail(new AiServiceException(ErrorCode.INVALID_ARGUMENT, "要求がありません", null));
            return result;
        }
        RegisteredImage image = request.image();
        String instance;
        HelperClient client;
        Set<OperationPromise<AiMaskResult>> superseded;
        synchronized (this) {
            instance = instances.get();
            client = clients.get();
            if (client == null || instance == null) {
                result.fail(unavailable());
                return result;
            }
            if (!instance.equals(image.serviceInstanceId())) {
                result.fail(
                        new AiServiceException(ErrorCode.IMAGE_NOT_FOUND, "画像を再登録してください", null));
                return result;
            }
            superseded = requests.remove(image);
            client.cancelSegmentation(image.imageId());
            requests.computeIfAbsent(image, key -> ConcurrentHashMap.newKeySet()).add(result);
        }
        result.stage()
                .whenComplete(
                        (value, error) ->
                                requests.computeIfPresent(
                                        image,
                                        (key, set) -> {
                                            set.remove(result);
                                            return set.isEmpty() ? null : set;
                                        }));
        // Free superseded work before submitting, without calling application code under the lock.
        cancel(superseded);
        List<HelperClient.Point> points =
                request.points().stream()
                        .map(
                                p ->
                                        new HelperClient.Point(
                                                p.x(),
                                                p.y(),
                                                p.label() == PromptLabel.POSITIVE ? 1 : 0))
                        .toList();
        CompletableFuture<HelperClient.Segmentation> future;
        synchronized (this) {
            var admitted = requests.get(image);
            // A reentrant callback or concurrent admission may already have replaced this request.
            future =
                    admitted != null && admitted.contains(result)
                            ? client.segment(
                                    image.imageId(),
                                    image.sourceRevision(),
                                    request.requestId().toString(),
                                    points)
                            : null;
        }
        if (future == null) {
            result.cancel();
            return result;
        }
        result.onCancel(() -> future.cancel(true));
        future.whenComplete(
                (value, error) -> {
                    if (error != null) {
                        result.fail(Failures.ai(error));
                        return;
                    }
                    if (!instance.equals(instances.get())) {
                        result.cancel();
                        return;
                    }
                    try {
                        if (value.candidates().stream()
                                .anyMatch(
                                        c ->
                                                c.mask().getWidth() != image.width()
                                                        || c.mask().getHeight() != image.height()))
                            throw new IllegalArgumentException(
                                    "Registered image dimensions mismatch");
                        List<MaskCandidate> candidates =
                                value.candidates().stream()
                                        .map(
                                                c ->
                                                        new MaskCandidate(
                                                                c.candidateId(),
                                                                c.mask(),
                                                                c.score()))
                                        .toList();
                        result.succeed(
                                new AiMaskResult(
                                        image,
                                        request.requestId(),
                                        candidates,
                                        request.points(),
                                        switch (value.deviceUsed()) {
                                            case "cpu" -> DeviceKind.CPU;
                                            case "mps" -> DeviceKind.MPS;
                                            default ->
                                                    throw new IllegalArgumentException(
                                                            "Unknown device");
                                        }));
                    } catch (RuntimeException e) {
                        result.fail(
                                new AiServiceException(ErrorCode.INVALID_RESPONSE, "AI応答が不正です", e));
                    }
                });
        return result;
    }

    @Override
    public void invalidateRequests(RegisteredImage image) {
        if (image == null) return;
        Set<OperationPromise<AiMaskResult>> pending;
        synchronized (this) {
            pending = requests.remove(image);
            HelperClient client = clients.get();
            if (client != null && image.serviceInstanceId().equals(instances.get()))
                client.cancelSegmentation(image.imageId());
        }
        cancel(pending);
    }

    private static void cancel(Set<OperationPromise<AiMaskResult>> pending) {
        if (pending != null) for (var operation : pending) operation.cancel();
    }

    @Override
    public AsyncOperation<Void> releaseImage(RegisteredImage image) {
        OperationPromise<Void> result = operation();
        if (image == null) {
            result.fail(new AiServiceException(ErrorCode.INVALID_ARGUMENT, "画像がありません", null));
            return result;
        }
        invalidateRequests(image);
        HelperClient client = clients.get();
        String instance = instances.get();
        if (instance == null || !image.serviceInstanceId().equals(instance)) {
            result.succeed(null);
            return result;
        }
        if (client == null) {
            result.fail(unavailable());
            return result;
        }
        var future = client.releaseImage(image.imageId());
        result.onCancel(() -> future.cancel(true));
        future.whenComplete(
                (value, error) -> {
                    if (error != null) result.fail(Failures.ai(error));
                    else result.succeed(null);
                });
        return result;
    }

    public void invalidateAll() {
        for (var image : List.copyOf(requests.keySet())) invalidateRequests(image);
    }
}
