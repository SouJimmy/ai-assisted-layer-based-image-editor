package jp.ac.example.imageeditor.testing;

import static org.junit.jupiter.api.Assertions.*;

import jp.ac.example.imageeditor.contract.ai.*;
import jp.ac.example.imageeditor.contract.common.*;
import jp.ac.example.imageeditor.contract.processing.*;
import jp.ac.example.imageeditor.contract.psd.*;
import jp.ac.example.imageeditor.integration.ai.*;
import jp.ac.example.imageeditor.integration.psd.*;
import jp.ac.example.imageeditor.integration.runtime.*;
import jp.ac.example.imageeditor.integration.shared.*;
import jp.ac.example.imageeditor.internal.integration.*;
import jp.ac.example.imageeditor.processing.DefaultImageProcessor;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class PublicContractTest {
    static <T> T get(AsyncOperation<T> operation) throws Exception {
        return operation.stage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    static BackendException error(AsyncOperation<?> operation) throws Exception {
        ExecutionException exception = assertThrows(ExecutionException.class, () -> get(operation));
        return assertInstanceOf(BackendException.class, exception.getCause());
    }

    @Test
    void readOnlyCompletionAndAtomicCancellation() throws Exception {
        var p =
                new OperationPromise<String>(
                        () -> new ProcessingException(ErrorCode.CANCELLED, "cancel", null));
        p.stage().toCompletableFuture().complete("external");
        p.stage().toCompletableFuture().cancel(true);
        assertFalse(p.isDone());
        assertTrue(p.cancel());
        assertFalse(p.cancel());
        assertFalse(p.succeed("late"));
        assertEquals(ErrorCode.CANCELLED, error(p).code());
        AtomicInteger hooks = new AtomicInteger();
        p.onCancel(hooks::incrementAndGet);
        assertEquals(1, hooks.get());
        var success =
                new OperationPromise<String>(
                        () -> new ProcessingException(ErrorCode.CANCELLED, "cancel", null));
        assertTrue(success.succeed("ok"));
        assertEquals("ok", get(success));
        assertFalse(success.cancel());
        assertEquals(ErrorCode.INVALID_ARGUMENT, Failures.code(new IllegalArgumentException()));
        assertEquals(ErrorCode.CANCELLED, Failures.code(new CancellationException()));
        assertEquals(
                ErrorCode.INVALID_RESPONSE,
                Failures.code(new ServiceFailure("NEW_CODE", "new", null)));
    }

    @Test
    void allPixelOperationsUseSharedMetadataAndIndependentImages() throws Exception {
        var source = new BufferedImage(5, 3, BufferedImage.TYPE_INT_ARGB_PRE);
        source.setRGB(1, 1, 0x80802010);
        try (var processor = new DefaultImageProcessor()) {
            for (var op : ProcessingOperation.values()) {
                ProcessingParameters parameters =
                        switch (op) {
                            case CROP -> new CropParameters(new PixelRect(1, 0, 3, 2));
                            case RESIZE -> new ResizeParameters(3, 2);
                            case COLOR_ADJUST -> new ColorAdjustParameters(20, 1.2, .8);
                            case BLUR -> new BlurParameters(3);
                            case MOSAIC -> new MosaicParameters(2);
                            default -> new NoParameters();
                        };
                var request = new ProcessingRequest(source, op, parameters);
                var result = processor.process(request, ProcessingContext.defaults());
                assertEquals(op, result.operation());
                assertEquals(parameters, result.parameters());
                assertTrue(result.deterministic());
                assertNotNull(result.algorithmVersion());
                assertNotSame(
                        source.getRaster().getDataBuffer(),
                        result.image().getRaster().getDataBuffer());
                assertEquals(5, result.sourceChangedBounds().width());
                assertEquals(result.image().getWidth(), result.outputChangedBounds().width());
                assertEquals(
                        result.image().getWidth() != 5 || result.image().getHeight() != 3,
                        result.geometryChanged());
            }
            var request =
                    new ProcessingRequest(
                            source, ProcessingOperation.GRAYSCALE, new NoParameters());
            AtomicReference<Double> progress = new AtomicReference<>(0.0);
            assertEquals(ErrorCode.INVALID_ARGUMENT, error(processor.submit(request, null)).code());
            get(processor.submit(request, value -> progress.set(value)));
            assertEquals(1.0, progress.get());
            var cancel =
                    assertThrows(
                            ProcessingException.class,
                            () ->
                                    processor.process(
                                            request, new ProcessingContext(() -> true, v -> {})));
            assertEquals(ErrorCode.CANCELLED, cancel.code());
            assertEquals(ErrorCode.INVALID_ARGUMENT, error(processor.submit(null, v -> {})).code());
            assertEquals(
                    ErrorCode.INVALID_ARGUMENT,
                    error(
                                    processor.submit(
                                            new ProcessingRequest(
                                                    source,
                                                    ProcessingOperation.BLUR,
                                                    new NoParameters()),
                                            v -> {}))
                            .code());
            processor.process(
                    request,
                    new ProcessingContext(
                            () -> false,
                            v -> {
                                throw new IllegalStateException();
                            }));
        }
        var closed = new DefaultImageProcessor();
        closed.close();
        assertEquals(
                ErrorCode.SERVICE_UNAVAILABLE,
                assertThrows(
                                ProcessingException.class,
                                () -> closed.process(null, ProcessingContext.defaults()))
                        .code());
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error(closed.submit(null, v -> {})).code());
    }

    @Test
    void queuedAndRunningPixelCancellationReleasesCapacity() throws Exception {
        try (var processor = new DefaultImageProcessor()) {
            var request =
                    new ProcessingRequest(
                            new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB_PRE),
                            ProcessingOperation.NEGATIVE,
                            new NoParameters());
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var first =
                    processor.submit(
                            request,
                            v -> {
                                entered.countDown();
                                try {
                                    release.await(3, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var second = processor.submit(request, v -> {});
            var third = processor.submit(request, v -> {});
            assertEquals(ErrorCode.BUSY, error(processor.submit(request, v -> {})).code());
            assertTrue(second.cancel());
            var replacement = processor.submit(request, v -> {});
            assertTrue(first.cancel());
            release.countDown();
            get(third);
            get(replacement);
            assertEquals(ErrorCode.CANCELLED, error(second).code());
        }
    }

    @Test
    void aiSuccessStaleInstancesAndInvalidation() throws Exception {
        try (var server = new ContractTests.Server();
                var client = server.client()) {
            AtomicReference<String> instance = new AtomicReference<>(server.instance);
            var ai = new HttpAiSegmentationService(() -> client, instance::get);
            var registered = get(ai.registerImage(ContractTests.image(), "r"));
            var points = new ArrayList<>(List.of(new PromptPoint(0, 0, PromptLabel.POSITIVE)));
            var request = new AiRequest(registered, UUID.randomUUID(), points);
            points.clear();
            var response = get(ai.segment(request));
            assertEquals(1, response.sourcePoints().size());
            assertEquals(DeviceKind.CPU, response.deviceUsed());
            assertEquals(
                    BufferedImage.TYPE_BYTE_GRAY,
                    response.candidates().getFirst().mask().getType());
            assertThrows(UnsupportedOperationException.class, () -> response.candidates().clear());
            var forged =
                    new RegisteredImage(
                            registered.imageId(),
                            registered.sourceRevision(),
                            3,
                            2,
                            registered.serviceInstanceId());
            assertEquals(
                    ErrorCode.INVALID_RESPONSE,
                    error(ai.segment(new AiRequest(forged, UUID.randomUUID(), request.points())))
                            .code());
            assertEquals(ErrorCode.INVALID_ARGUMENT, error(ai.segment(null)).code());
            assertEquals(ErrorCode.INVALID_ARGUMENT, error(ai.releaseImage(null)).code());
            instance.set(UUID.randomUUID().toString());
            assertEquals(ErrorCode.IMAGE_NOT_FOUND, error(ai.segment(request)).code());
            get(ai.releaseImage(registered));
            instance.set(server.instance);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            server.handler =
                    e -> {
                        if (e.getRequestURI().getPath().equals("/segment")) {
                            entered.countDown();
                            try {
                                release.await(3, TimeUnit.SECONDS);
                            } catch (InterruptedException ignored) {
                            }
                        }
                        return server.normal(e);
                    };
            var pending = ai.segment(request);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            ai.invalidateRequests(registered);
            assertEquals(ErrorCode.CANCELLED, error(pending).code());
            release.countDown();
            get(ai.releaseImage(registered));
        }
    }

    @Test
    void cancelledRegistrationReclaimsReturnedImage() throws Exception {
        try (var server = new ContractTests.Server();
                var client = server.client()) {
            CountDownLatch entered = new CountDownLatch(1),
                    release = new CountDownLatch(1),
                    deleted = new CountDownLatch(1);
            server.handler =
                    e -> {
                        if (e.getRequestURI().getPath().equals("/image")) {
                            entered.countDown();
                            try {
                                release.await(3, TimeUnit.SECONDS);
                            } catch (InterruptedException ignored) {
                            }
                        }
                        if (e.getRequestMethod().equals("DELETE")) deleted.countDown();
                        return server.normal(e);
                    };
            var ai = new HttpAiSegmentationService(() -> client, () -> server.instance);
            var registration = ai.registerImage(ContractTests.image(), "r");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(registration.cancel());
            release.countDown();
            assertTrue(deleted.await(3, TimeUnit.SECONDS));
            assertEquals(ErrorCode.CANCELLED, error(registration).code());
        }
    }

    @Test
    void metadataValuesAreDeeplyImmutableAndRetainNumbers() {
        Map<String, Object> original = new HashMap<>();
        var nested = new ArrayList<Object>();
        nested.add(new java.math.BigDecimal("12345678901234567890.123"));
        nested.add(null);
        nested.add(true);
        nested.add("text");
        original.put("nested", nested);
        var metadata = (MetadataObject) MetadataValue.from(original);
        nested.clear();
        var values = ((MetadataArray) metadata.values().get("nested")).values();
        assertEquals(4, values.size());
        assertEquals(
                new java.math.BigDecimal("12345678901234567890.123"),
                ((MetadataNumber) values.getFirst()).value());
        assertEquals(MetadataNull.INSTANCE, values.get(1));
        assertEquals(true, ((MetadataBoolean) values.get(2)).value());
        assertEquals("text", ((MetadataString) values.get(3)).value());
        assertThrows(UnsupportedOperationException.class, () -> metadata.values().clear());
        assertThrows(IllegalArgumentException.class, () -> MetadataValue.from(new Object()));
    }

    @Test
    void typedPsdPreservesUnknownWarningsAndRejectsExcessNodes() throws Exception {
        var manifest = ContractTests.manifest();
        ContractTests.first(manifest, "nodes").put("blendMode", "norm");
        manifest.put(
                "warnings",
                List.of(
                        Map.of(
                                "code",
                                "NEW_WARNING",
                                "nodeId",
                                "node",
                                "message",
                                "future warning",
                                "disposition",
                                "RASTER",
                                "sourceKind",
                                "pixel",
                                "unsupportedFeature",
                                "future")));
        var wire =
                HelperClient.readPsdArchive(
                        ContractTests.archive(
                                manifest,
                                Map.of(
                                        "assets/asset.png",
                                        ContractTests.png(ContractTests.image()))));
        var result = HttpPsdImportService.convert(wire);
        assertEquals(1, result.schemaVersion());
        assertEquals(2, result.canvasWidth());
        assertNotNull(result.nodes().getFirst().originalMetadata());
    }

    @Test
    void lifecycleStartRestartAndFinalClose() throws Exception {
        Path dir = Files.createTempDirectory("public-helper-");
        Files.createDirectories(dir.resolve("src"));
        String script =
                ContractTests.childScript("normal")
                        .replace(
                                "instance='12345678-1234-1234-1234-123456789abc'",
                                "import uuid\ninstance=str(uuid.uuid4())")
                        .replace(
                                "{'serviceInstanceId':instance}",
                                "{'serviceInstanceId':instance,'aiReady':False,'psdReady':True,'device':'cpu','aiError':'MODEL_INIT_FAILED'}");
        Files.writeString(dir.resolve("src/editor_helper.py"), script);
        var config =
                new ServiceConfiguration(
                        Path.of("python-service/.venv/bin/python"),
                        dir,
                        dir.resolve("missing.json"),
                        dir.resolve("logs"));
        var manager = new jp.ac.example.imageeditor.integration.runtime.LocalServiceManager();
        assertEquals(ErrorCode.INVALID_ARGUMENT, error(manager.restart()).code());
        assertEquals(
                ErrorCode.SERVICE_UNAVAILABLE,
                error(manager.aiService().registerImage(ContractTests.image(), "r")).code());
        assertEquals(ErrorCode.INVALID_ARGUMENT, error(manager.start(null)).code());
        var first = get(manager.start(config));
        assertEquals(ServiceState.RUNNING, first.state());
        assertTrue(first.psdReady());
        assertFalse(first.aiReady());
        assertEquals(ErrorCode.MODEL_INIT_FAILED, first.aiError().orElseThrow().code());
        assertEquals(first, get(manager.start(config)));
        var second = get(manager.restart());
        assertNotEquals(first.serviceInstanceId(), second.serviceInstanceId());
        long pid = Long.parseLong(Files.readString(dir.resolve("pid.txt")));
        manager.close();
        manager.close();
        assertEquals(ServiceState.CLOSED, manager.status().state());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error(manager.start(config)).code());
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error(manager.restart()).code());
        assertTrue(Files.exists(dir.resolve("logs/helper.log")));
    }
}
