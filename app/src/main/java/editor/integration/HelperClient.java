package editor.integration;

import editor.processing.ImageProcessor;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

import javax.imageio.ImageIO;

/** B-side adapter for API v1. It never updates Document, Layer or History. */
public final class HelperClient implements AutoCloseable {
    public record RegisteredImage(
            String imageId,
            String sourceRevision,
            int width,
            int height,
            String serviceInstanceId) {}

    public record Point(int x, int y, int label) {}

    public record Candidate(String candidateId, BufferedImage mask, double score) {}

    public record Segmentation(
            String imageId,
            String sourceRevision,
            String requestId,
            List<Candidate> candidates,
            String deviceUsed) {
        public Segmentation {
            candidates = List.copyOf(candidates);
        }
    }

    public record PsdImport(
            int canvasWidth,
            int canvasHeight,
            List<Map<String, Object>> nodes,
            Map<String, BufferedImage> assets,
            List<Map<String, Object>> warnings) {
        public PsdImport {
            nodes = List.copyOf(nodes);
            assets = Map.copyOf(assets);
            warnings = List.copyOf(warnings);
        }
    }

    private static final Set<String> CODES =
            Set.of(
                    "INVALID_ARGUMENT",
                    "RESOURCE_LIMIT",
                    "AUTH_FAILED",
                    "API_VERSION_UNSUPPORTED",
                    "NO_RESULT",
                    "INVALID_IMAGE",
                    "UNSUPPORTED_FORMAT",
                    "IMAGE_NOT_FOUND",
                    "REVISION_MISMATCH",
                    "BUSY",
                    "MODEL_NOT_READY",
                    "MODEL_INIT_FAILED",
                    "INFERENCE_FAILED",
                    "PSD_PARSE_FAILED",
                    "TIMEOUT",
                    "CANCELLED",
                    "INTERNAL_ERROR");
    private final URI base;
    private final String token;
    private final HttpClient http;
    private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(
                    2,
                    2,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(16),
                    r -> {
                        Thread t = new Thread(r, "helper-client");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentMap<String, String> latest = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RegisteredImage> images = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public HelperClient(URI base, String token) {
        if (!"http".equals(base.getScheme())
                || !"127.0.0.1".equals(base.getHost())
                || base.getPort() < 1
                || base.getPort() > 65535
                || base.getRawUserInfo() != null
                || base.getRawQuery() != null
                || base.getRawFragment() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/"))
                || token == null
                || token.isBlank())
            throw new IllegalArgumentException("Loopback origin and token required");
        this.base = base;
        this.token = token;
        http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .proxy(
                                new ProxySelector() {
                                    public List<Proxy> select(URI uri) {
                                        return List.of(Proxy.NO_PROXY);
                                    }

                                    public void connectFailed(
                                            URI uri, SocketAddress address, IOException error) {}
                                })
                        .build();
    }

    private <T> CompletableFuture<T> task(Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        pending.add(result);
        result.whenComplete((value, error) -> pending.remove(result));
        if (closed) {
            result.completeExceptionally(
                    new ServiceFailure("SERVICE_UNAVAILABLE", "サービス接続を終了しました", null));
            return result;
        }
        try {
            Future<?> work =
                    workers.submit(
                            () -> {
                                try {
                                    T value = action.call();
                                    if (!result.isCancelled()) result.complete(value);
                                } catch (Throwable e) {
                                    result.completeExceptionally(e);
                                }
                            });
            result.whenComplete(
                    (value, error) -> {
                        if (result.isCancelled()) work.cancel(true);
                    });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(new ServiceFailure("BUSY", "通信キューが満杯です", e));
        }
        return result;
    }

    public CompletableFuture<Map<String, Object>> health() {
        return task(
                () ->
                        object(
                                exchange(
                                        "GET",
                                        "/health",
                                        null,
                                        null,
                                        2,
                                        65536,
                                        200,
                                        UUID.randomUUID().toString())));
    }

    public CompletableFuture<RegisteredImage> registerImage(
            BufferedImage snapshot, String revision) {
        return task(
                () -> {
                    if (snapshot == null
                            || revision == null
                            || revision.isEmpty()
                            || revision.length() > 128) throw invalid("登録入力が不正です");
                    ImageProcessor.checkDimensions(snapshot.getWidth(), snapshot.getHeight());
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    ImageIO.write(snapshot, "PNG", png);
                    if (png.size() > 64 * 1024 * 1024) throw invalid("登録画像が上限を超えています");
                    Multipart body = multipart("image", "image.png", png.toByteArray(), revision);
                    Map<String, Object> data =
                            object(
                                    exchange(
                                            "POST",
                                            "/image",
                                            body.bytes,
                                            body.type,
                                            30,
                                            65536,
                                            201,
                                            UUID.randomUUID().toString()));
                    try {
                        RegisteredImage image =
                                new RegisteredImage(
                                        Json.string(data, "imageId"),
                                        Json.string(data, "sourceRevision"),
                                        Json.integer(data, "width"),
                                        Json.integer(data, "height"),
                                        Json.string(data, "serviceInstanceId"));
                        UUID.fromString(image.imageId());
                        UUID.fromString(image.serviceInstanceId());
                        if (!revision.equals(image.sourceRevision())
                                || snapshot.getWidth() != image.width()
                                || snapshot.getHeight() != image.height())
                            throw new IllegalArgumentException();
                        images.put(image.imageId(), image);
                        return image;
                    } catch (RuntimeException e) {
                        throw invalidResponse(e);
                    }
                });
    }

    public CompletableFuture<Segmentation> segment(
            String imageId, String revision, String requestId, List<Point> points) {
        final List<Point> copy;
        try {
            copy = List.copyOf(points);
            UUID.fromString(requestId);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(invalid("推論入力が不正です"));
        }
        if (imageId == null) return CompletableFuture.failedFuture(invalid("画像IDが不正です"));
        RegisteredImage registered = images.get(imageId);
        if (registered == null)
            return CompletableFuture.failedFuture(
                    new ServiceFailure("IMAGE_NOT_FOUND", "画像を登録してください", requestId, 404));
        if (!registered.sourceRevision().equals(revision))
            return CompletableFuture.failedFuture(invalid("画像世代が一致しません"));
        if (copy.isEmpty()
                || copy.size() > 64
                || copy.stream().noneMatch(p -> p.label() == 1)
                || copy.stream()
                        .anyMatch(
                                p ->
                                        p.x() < 0
                                                || p.y() < 0
                                                || p.x() >= registered.width()
                                                || p.y() >= registered.height()
                                                || (p.label() != 0 && p.label() != 1)))
            return CompletableFuture.failedFuture(invalid("クリック座標またはラベルが不正です"));
        latest.put(imageId, requestId);
        return task(
                () -> {
                    if (!requestId.equals(latest.get(imageId))) throw new CancellationException();
                    List<Object> prompt = new ArrayList<>();
                    for (Point p : copy)
                        prompt.add(Map.of("x", p.x(), "y", p.y(), "label", p.label()));
                    byte[] body =
                            Json.write(
                                            Map.of(
                                                    "imageId",
                                                    imageId,
                                                    "sourceRevision",
                                                    revision,
                                                    "requestId",
                                                    requestId,
                                                    "points",
                                                    prompt))
                                    .getBytes(StandardCharsets.UTF_8);
                    Map<String, Object> data =
                            object(
                                    exchange(
                                            "POST",
                                            "/segment",
                                            body,
                                            "application/json",
                                            15,
                                            48 * 1024 * 1024,
                                            200,
                                            requestId));
                    Segmentation value;
                    try {
                        if (!imageId.equals(Json.string(data, "imageId"))
                                || !revision.equals(Json.string(data, "sourceRevision"))
                                || !requestId.equals(Json.string(data, "requestId"))
                                || Json.integer(data, "width") != registered.width()
                                || Json.integer(data, "height") != registered.height())
                            throw new IllegalArgumentException();
                        List<?> candidates = Json.list(data, "candidates");
                        if (candidates.isEmpty() || candidates.size() > 3)
                            throw new IllegalArgumentException();
                        List<Candidate> result = new ArrayList<>();
                        Set<String> ids = new HashSet<>();
                        for (Object item : candidates) {
                            Map<String, Object> candidate = Json.object(item);
                            String id = Json.string(candidate, "candidateId");
                            UUID.fromString(id);
                            if (!ids.add(id)
                                    || !(candidate.get("score") instanceof Number score)
                                    || !Double.isFinite(score.doubleValue()))
                                throw new IllegalArgumentException();
                            BufferedImage mask =
                                    PngDecoder.read(
                                            Base64.getDecoder()
                                                    .decode(
                                                            Json.string(
                                                                    candidate, "maskPngBase64")),
                                            registered.width(),
                                            registered.height(),
                                            true);
                            result.add(new Candidate(id, mask, score.doubleValue()));
                        }
                        String device = Json.string(data, "deviceUsed");
                        if (!device.equals("cpu") && !device.equals("mps"))
                            throw new IllegalArgumentException();
                        value = new Segmentation(imageId, revision, requestId, result, device);
                    } catch (RuntimeException | IOException e) {
                        throw invalidResponse(e);
                    }
                    if (!requestId.equals(latest.get(imageId)) || !images.containsKey(imageId))
                        throw new CancellationException();
                    return value;
                });
    }

    public void cancelSegmentation(String imageId) {
        latest.remove(imageId);
    }

    public CompletableFuture<Void> releaseImage(String imageId) {
        try {
            UUID.fromString(imageId);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(invalid("画像IDが不正です"));
        }
        images.remove(imageId);
        latest.remove(imageId);
        return task(
                () -> {
                    exchange(
                            "DELETE",
                            "/image/" + imageId,
                            null,
                            null,
                            15,
                            65536,
                            204,
                            UUID.randomUUID().toString());
                    return null;
                });
    }

    public CompletableFuture<PsdImport> importPsd(Path file) {
        return task(
                () -> {
                    byte[] fileBytes;
                    try (var stream = Files.newInputStream(file)) {
                        fileBytes = stream.readNBytes(128 * 1024 * 1024 + 1);
                    }
                    if (fileBytes.length > 128 * 1024 * 1024) throw invalid("PSDが上限を超えています");
                    Multipart body = multipart("file", "input.psd", fileBytes, null);
                    byte[] archive =
                            exchange(
                                    "POST",
                                    "/psd/import",
                                    body.bytes,
                                    body.type,
                                    60,
                                    256 * 1024 * 1024,
                                    200,
                                    UUID.randomUUID().toString());
                    return readPsdArchive(archive);
                });
    }

    public static PsdImport readPsdArchive(byte[] bytes) {
        return PsdArchiveReader.read(bytes);
    }

    private byte[] exchange(
            String method,
            String path,
            byte[] body,
            String type,
            int seconds,
            int limit,
            int expected,
            String requestId) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(base.resolve(path))
                        .timeout(Duration.ofSeconds(seconds))
                        .header("Authorization", "Bearer " + token)
                        .header("X-Api-Version", "1")
                        .header("X-Request-Id", requestId);
        if (type != null) builder.header("Content-Type", type);
        builder.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        CompletableFuture<HttpResponse<byte[]>> call =
                http.sendAsync(builder.build(), info -> new LimitedBody(limit));
        try {
            HttpResponse<byte[]> response = call.get(seconds, TimeUnit.SECONDS);
            if (!response.headers().firstValue("X-Api-Version").orElse("").equals("1"))
                throw new ServiceFailure(
                        "API_VERSION_UNSUPPORTED",
                        "APIの版が一致しません",
                        requestId,
                        response.statusCode());
            if (!response.headers().firstValue("X-Request-Id").orElse("").equals(requestId))
                throw invalidResponse(null);
            if (response.statusCode() != expected) {
                try {
                    Map<String, Object> error = Json.object(object(response.body()).get("error"));
                    String code = Json.string(error, "code");
                    if (!CODES.contains(code) || !requestId.equals(Json.string(error, "requestId")))
                        throw new IllegalArgumentException();
                    throw new ServiceFailure(
                            code, Json.string(error, "message"), requestId, response.statusCode());
                } catch (ServiceFailure e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw invalidResponse(e);
                }
            }
            return response.body();
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new ServiceFailure("TIMEOUT", "サービスの応答が期限を超えました", e);
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException)
                throw new ServiceFailure("TIMEOUT", "サービスの応答が期限を超えました", cause);
            if (cause instanceof ServiceFailure failure) throw failure;
            throw new ServiceFailure("SERVICE_UNAVAILABLE", "サービスに接続できません", cause);
        }
    }

    private static Map<String, Object> object(byte[] bytes) {
        try {
            return Json.object(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            throw invalidResponse(e);
        }
    }

    private record Multipart(byte[] bytes, String type) {}

    private static Multipart multipart(String field, String filename, byte[] data, String revision)
            throws IOException {
        String boundary = "editor-" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (revision != null)
            out.write(
                    ("--"
                                    + boundary
                                    + "\r\n"
                                    + "Content-Disposition: form-data;"
                                    + " name=\"sourceRevision\"\r\n\r\n"
                                    + revision
                                    + "\r\n")
                            .getBytes(StandardCharsets.UTF_8));
        out.write(
                ("--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\""
                                + field
                                + "\"; filename=\""
                                + filename
                                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return new Multipart(out.toByteArray(), "multipart/form-data; boundary=" + boundary);
    }

    private static ServiceFailure invalid(String message) {
        return new ServiceFailure("INVALID_ARGUMENT", message, (Throwable) null);
    }

    private static ServiceFailure invalidResponse(Throwable cause) {
        return new ServiceFailure("INVALID_RESPONSE", "サービスの応答が不正です", cause);
    }

    @Override
    public void close() {
        closed = true;
        latest.clear();
        images.clear();
        for (var job : pending) job.cancel(true);
        workers.shutdownNow();
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final int limit;
        Flow.Subscription subscription;

        LimitedBody(int limit) {
            this.limit = limit;
        }

        public CompletionStage<byte[]> getBody() {
            return result;
        }

        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) out.size() + buffer.remaining() > limit) {
                    subscription.cancel();
                    result.completeExceptionally(invalidResponse(null));
                    return;
                }
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                out.writeBytes(bytes);
            }
            subscription.request(1);
        }

        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        public void onComplete() {
            result.complete(out.toByteArray());
        }
    }
}
