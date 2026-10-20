package jp.ac.example.imageeditor.testing;

import static jp.ac.example.imageeditor.testing.PublicContractTest.*;

import static org.junit.jupiter.api.Assertions.*;

import jp.ac.example.imageeditor.contract.ai.*;
import jp.ac.example.imageeditor.contract.common.*;
import jp.ac.example.imageeditor.contract.processing.*;
import jp.ac.example.imageeditor.contract.psd.*;
import jp.ac.example.imageeditor.integration.ai.*;
import jp.ac.example.imageeditor.integration.psd.*;
import jp.ac.example.imageeditor.integration.runtime.*;
import jp.ac.example.imageeditor.integration.shared.*;
import jp.ac.example.imageeditor.integration.stub.*;
import jp.ac.example.imageeditor.internal.integration.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class MigrationBoundaryTest {
    @Test
    void checkedFailuresRetainDiagnosticsAndRetryability() {
        for (ErrorCode code : ErrorCode.values()) {
            var wire = new ServiceFailure(code.name(), "wire", "req", 503);
            var ai = Failures.ai(new CompletionException(new ExecutionException(wire)));
            var psd = Failures.psd(wire);
            assertEquals(code, ai.code());
            assertEquals(code, psd.code());
            assertEquals("req", ai.requestId().orElseThrow());
            assertEquals(503, psd.httpStatus().orElseThrow());
            assertNotNull(ai.getCause());
            assertEquals(ai.retryable(), psd.retryable());
            assertSame(ai, Failures.ai(ai));
            assertSame(psd, Failures.psd(psd));
        }
        assertEquals(ErrorCode.INTERNAL_ERROR, Failures.code(new java.io.IOException()));
        assertEquals(ErrorCode.CANCELLED, Failures.code(new InterruptedException()));
        var empty = new PsdImportException(ErrorCode.INVALID_ARGUMENT, "bad", null);
        assertTrue(empty.httpStatus().isEmpty());
        assertTrue(empty.requestId().isEmpty());
    }

    static Map<String, Object> fullManifest() {
        var manifest = ContractTests.manifest();
        ContractTests.first(manifest, "nodes").put("blendMode", "norm");
        manifest.put(
                "warnings",
                List.of(
                        Map.of(
                                "code",
                                "FLATTENED",
                                "message",
                                "group flattened",
                                "disposition",
                                "REPLACEMENT_RASTER",
                                "sourceKind",
                                "group",
                                "unsupportedFeature",
                                "blend")));
        return manifest;
    }

    @Test
    void typedPsdHttpSuccessFailureCancelAndGeneration() throws Exception {
        Path input = Files.createTempFile("psd-typed-", ".psd");
        Files.write(input, new byte[] {1, 2, 3});
        try (var server = new ContractTests.Server();
                var client = server.client()) {
            var manifest = fullManifest();
            byte[] archive = ContractTests.archive(manifest, ContractTests.assets());
            server.handler = e -> new ContractTests.Reply(200, archive);
            AtomicReference<String> instance = new AtomicReference<>(server.instance);
            var psd = new HttpPsdImportService(() -> client, instance::get);
            var result = get(psd.importPsd(input));
            assertEquals(2, result.canvasHeight());
            assertEquals(WarningCode.FLATTENED, result.warnings().getFirst().code());
            assertTrue(result.warnings().getFirst().nodeId().isEmpty());
            assertEquals("asset", result.rasterAssets().get("asset").assetId());
            assertEquals(ImportDisposition.RASTER, result.nodes().getFirst().importDisposition());
            assertEquals("node", result.nodes().getFirst().id());
            assertEquals(ErrorCode.INVALID_ARGUMENT, error(psd.importPsd(null)).code());
            var missing = new HttpPsdImportService(() -> null, () -> null);
            assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error(missing.importPsd(input)).code());
            server.handler = e -> new ContractTests.Reply(200, new byte[] {1});
            assertEquals(ErrorCode.INVALID_RESPONSE, error(psd.importPsd(input)).code());
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            server.handler =
                    e -> {
                        entered.countDown();
                        try {
                            release.await(3, TimeUnit.SECONDS);
                        } catch (InterruptedException ignored) {
                        }
                        return new ContractTests.Reply(200, archive);
                    };
            var pending = psd.importPsd(input);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            instance.set("other");
            release.countDown();
            assertEquals(ErrorCode.CANCELLED, error(pending).code());
            var cancelled = psd.importPsd(input);
            assertTrue(cancelled.cancel());
            assertEquals(ErrorCode.CANCELLED, error(cancelled).code());
            var wire =
                    new HelperClient.PsdImport(
                            2, 2, Collections.nCopies(501, Map.of()), Map.of(), List.of());
            assertThrows(IllegalArgumentException.class, () -> HttpPsdImportService.convert(wire));
        } finally {
            Files.deleteIfExists(input);
        }
    }

    @Test
    void aiFailuresLateRegistrationAndDeviceValidation() throws Exception {
        var offline = new HttpAiSegmentationService(() -> null, () -> null);
        assertEquals(
                ErrorCode.SERVICE_UNAVAILABLE,
                error(offline.registerImage(ContractTests.image(), "r")).code());
        var image = new RegisteredImage(ContractTests.id(), "r", 2, 2, ContractTests.id());
        assertEquals(
                ErrorCode.SERVICE_UNAVAILABLE,
                error(
                                offline.segment(
                                        new AiRequest(
                                                image,
                                                UUID.randomUUID(),
                                                List.of(
                                                        new PromptPoint(
                                                                0, 0, PromptLabel.POSITIVE)))))
                        .code());
        try (var server = new ContractTests.Server();
                var client = server.client()) {
            AtomicReference<String> instance = new AtomicReference<>(server.instance);
            var ai = new HttpAiSegmentationService(() -> client, instance::get);
            assertEquals(ErrorCode.INVALID_ARGUMENT, error(ai.registerImage(null, "r")).code());
            var registered = get(ai.registerImage(ContractTests.image(), "r"));
            var request =
                    new AiRequest(
                            registered,
                            UUID.randomUUID(),
                            List.of(new PromptPoint(0, 0, PromptLabel.POSITIVE)));
            for (String device : List.of("mps", "unknown")) {
                server.handler =
                        e -> {
                            var response =
                                    server.result(e.getRequestHeaders().getFirst("X-Request-Id"));
                            response.put("deviceUsed", device);
                            return new ContractTests.Reply(200, response);
                        };
                if (device.equals("mps"))
                    assertEquals(DeviceKind.MPS, get(ai.segment(request)).deviceUsed());
                else assertEquals(ErrorCode.INVALID_RESPONSE, error(ai.segment(request)).code());
            }
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
                        } else if (e.getRequestMethod().equals("DELETE")) deleted.countDown();
                        return server.normal(e);
                    };
            var registration = ai.registerImage(ContractTests.image(), "r");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            instance.set("new-generation");
            release.countDown();
            assertEquals(ErrorCode.CANCELLED, error(registration).code());
            assertTrue(deleted.await(3, TimeUnit.SECONDS));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void controllableStubSupportsOutOfOrderFailuresAndCancellation() throws Exception {
        try (var stub = new StubBackend()) {
            var request =
                    new ProcessingRequest(
                            ContractTests.image(),
                            ProcessingOperation.NEGATIVE,
                            new NoParameters());
            var operation = stub.submit(request, v -> {});
            var pending = (StubBackend.Pending<ProcessingResult>) stub.take(1, TimeUnit.SECONDS);
            assertEquals("process", pending.name());
            assertSame(request, pending.input());
            assertSame(operation, pending.operation());
            pending.fail(new ProcessingException(ErrorCode.RESOURCE_LIMIT, "limit", null));
            assertEquals(ErrorCode.RESOURCE_LIMIT, error(operation).code());
            var image = new RegisteredImage(ContractTests.id(), "r", 2, 2, ContractTests.id());
            var registration = stub.registerImage(ContractTests.image(), "r");
            var item = (StubBackend.Pending<RegisteredImage>) stub.take(1, TimeUnit.SECONDS);
            assertTrue(item.succeed(image));
            assertEquals(image, get(registration));
            var aiRequest =
                    new AiRequest(
                            image,
                            UUID.randomUUID(),
                            List.of(new PromptPoint(0, 0, PromptLabel.POSITIVE)));
            var first = stub.segment(aiRequest);
            var second = stub.segment(aiRequest);
            stub.invalidateRequests(image);
            assertEquals(ErrorCode.CANCELLED, error(first).code());
            assertEquals(ErrorCode.CANCELLED, error(second).code());
            var release = stub.releaseImage(image);
            release.cancel();
            assertEquals(ErrorCode.CANCELLED, error(release).code());
            var psd = stub.importPsd(Path.of("test.psd"));
            psd.cancel();
            assertEquals(ErrorCode.CANCELLED, error(psd).code());
            assertThrows(
                    ProcessingException.class,
                    () -> stub.process(request, new ProcessingContext(() -> true, v -> {})));
            stub.close();
            assertEquals(ErrorCode.CANCELLED, error(stub.submit(request, v -> {})).code());
        }
    }

    static ServiceConfiguration fake(Path directory, String health, String mode) throws Exception {
        Files.createDirectories(directory.resolve("src"));
        String script =
                ContractTests.childScript(mode)
                        .replace(
                                "{'serviceInstanceId':instance}",
                                "{'serviceInstanceId':instance," + health + "}");
        Files.writeString(directory.resolve("src/editor_helper.py"), script);
        return new ServiceConfiguration(
                Path.of("python-service/.venv/bin/python"),
                directory,
                directory.resolve("manifest.json"),
                directory.resolve("logs"));
    }

    static void awaitState(
            jp.ac.example.imageeditor.integration.runtime.LocalServiceManager manager,
            ServiceState expected)
            throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (manager.status().state() != expected && System.nanoTime() < end) Thread.sleep(20);
        assertEquals(expected, manager.status().state());
    }

    @Test
    void lifecycleFailuresMonitorAndSafeLogging() throws Exception {
        for (String health :
                List.of(
                        "'device':'gpu'",
                        "'device':'mps','aiReady':True,'aiError':None",
                        "'device':'cpu','aiError':'NEW_ERROR'",
                        "'device':'cpu','aiError':{'code':'MODEL_INIT_FAILED'}",
                        "'device':'cpu','aiError':{'code':'NEW_ERROR'}")) {
            Path dir = Files.createTempDirectory("runtime-health-");
            var config = fake(dir, health, "normal");
            try (var manager =
                    new jp.ac.example.imageeditor.integration.runtime.LocalServiceManager()) {
                if (health.contains("gpu")) {
                    assertEquals(
                            ErrorCode.SERVICE_UNAVAILABLE, error(manager.start(config)).code());
                    assertEquals(ServiceState.FAILED, manager.status().state());
                } else {
                    get(manager.start(config));
                    Thread.sleep(600);
                    assertEquals(ServiceState.RUNNING, manager.status().state());
                    assertNotNull(manager.status().device());
                    if (health.contains("NEW_ERROR"))
                        assertEquals(
                                ErrorCode.INVALID_RESPONSE,
                                manager.status().aiError().orElseThrow().code());
                }
            }
        }
        Path dir = Files.createTempDirectory("runtime-death-");
        var config = fake(dir, "'device':'cpu','aiReady':True", "exit");
        try (var manager =
                new jp.ac.example.imageeditor.integration.runtime.LocalServiceManager()) {
            get(manager.start(config));
            awaitState(manager, ServiceState.FAILED);
            assertTrue(manager.status().lifecycleError().isPresent());
        }
    }

    @Test
    void lifecycleCancelAndLogRotation() throws Exception {
        Path dir = Files.createTempDirectory("runtime-cancel-");
        var config = fake(dir, "'device':'cpu'", "normal");
        Path script = dir.resolve("src/editor_helper.py");
        Files.writeString(
                script,
                Files.readString(script)
                        .replace("print(json.dumps", "time.sleep(2)\nprint(json.dumps"));
        try (var manager =
                new jp.ac.example.imageeditor.integration.runtime.LocalServiceManager()) {
            var starting = manager.start(config);
            assertEquals(ErrorCode.BUSY, error(manager.start(config)).code());
            assertTrue(starting.cancel());
            assertEquals(ErrorCode.CANCELLED, error(starting).code());
            awaitState(manager, ServiceState.STOPPED);
        }
        Path readyDir = Files.createTempDirectory("runtime-log-");
        var ready = fake(readyDir, "'device':'cpu'", "normal");
        Files.createDirectories(ready.logDirectory());
        Files.writeString(
                ready.logDirectory().resolve("helper.log"), "x".repeat(5 * 1024 * 1024 + 1));
        Files.writeString(ready.logDirectory().resolve("helper.log.1"), "old");
        try (var manager =
                new jp.ac.example.imageeditor.integration.runtime.LocalServiceManager()) {
            get(manager.start(ready));
            assertEquals(ErrorCode.INVALID_ARGUMENT, error(manager.start(config)).code());
            assertNotNull(manager.psdService());
        }
        assertTrue(Files.exists(ready.logDirectory().resolve("helper.log.2")));
    }

    @Test
    void releaseFailuresAndUnavailableFacadeRemainTyped() throws Exception {
        try (var server = new ContractTests.Server();
                var client = server.client()) {
            var ai = new HttpAiSegmentationService(() -> client, () -> server.instance);
            var image = get(ai.registerImage(ContractTests.image(), "r"));
            var unavailable = new HttpAiSegmentationService(() -> null, () -> server.instance);
            assertEquals(
                    ErrorCode.SERVICE_UNAVAILABLE, error(unavailable.releaseImage(image)).code());
            server.handler =
                    e ->
                            new ContractTests.Reply(
                                    429,
                                    Map.of(
                                            "error",
                                            Map.of(
                                                    "code",
                                                    "BUSY",
                                                    "message",
                                                    "busy",
                                                    "requestId",
                                                    e.getRequestHeaders().getFirst("X-Request-Id"),
                                                    "retryable",
                                                    true)));
            var failure = error(ai.releaseImage(image));
            assertEquals(ErrorCode.BUSY, failure.code());
            assertTrue(failure.retryable());
            assertEquals(429, failure.httpStatus().orElseThrow());
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void synchronousStubSuccessFailureAndInterrupt() throws Exception {
        for (String mode : List.of("success", "failure", "interrupt")) {
            try (var stub = new StubBackend()) {
                AtomicReference<Throwable> failure = new AtomicReference<>();
                AtomicReference<ProcessingResult> actual = new AtomicReference<>();
                var request =
                        new ProcessingRequest(
                                ContractTests.image(),
                                ProcessingOperation.NEGATIVE,
                                new NoParameters());
                Thread caller =
                        new Thread(
                                () -> {
                                    try {
                                        actual.set(
                                                stub.process(
                                                        request, ProcessingContext.defaults()));
                                    } catch (Throwable e) {
                                        failure.set(e);
                                    }
                                });
                caller.start();
                var pending =
                        (StubBackend.Pending<ProcessingResult>) stub.take(2, TimeUnit.SECONDS);
                assertNotNull(pending);
                var expected =
                        new ProcessingResult(
                                ContractTests.image(),
                                new PixelRect(0, 0, 2, 2),
                                new PixelRect(0, 0, 2, 2),
                                false,
                                true,
                                "v1",
                                request.operation(),
                                request.parameters());
                if (mode.equals("success")) pending.succeed(expected);
                else if (mode.equals("failure"))
                    pending.fail(new ProcessingException(ErrorCode.RESOURCE_LIMIT, "limit", null));
                else caller.interrupt();
                caller.join(2000);
                assertFalse(caller.isAlive());
                if (mode.equals("success")) assertSame(expected, actual.get());
                else
                    assertEquals(
                            mode.equals("failure") ? ErrorCode.RESOURCE_LIMIT : ErrorCode.CANCELLED,
                            assertInstanceOf(ProcessingException.class, failure.get()).code());
            }
        }
    }

    @Test
    void partiallyUnavailableSuppliersFailWithoutSynchronousThrows() throws Exception {
        try (var server = new ContractTests.Server();
                var client = server.client()) {
            var image = new RegisteredImage(server.imageId, "r", 2, 2, server.instance);
            var request =
                    new AiRequest(
                            image,
                            UUID.randomUUID(),
                            List.of(new PromptPoint(0, 0, PromptLabel.POSITIVE)));
            for (var ai :
                    List.of(
                            new HttpAiSegmentationService(() -> client, () -> null),
                            new HttpAiSegmentationService(() -> null, () -> server.instance))) {
                assertEquals(
                        ErrorCode.SERVICE_UNAVAILABLE,
                        error(ai.registerImage(ContractTests.image(), "r")).code());
                assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error(ai.segment(request)).code());
            }
            var psd = new HttpPsdImportService(() -> client, () -> null);
            assertEquals(
                    ErrorCode.SERVICE_UNAVAILABLE,
                    error(psd.importPsd(Path.of("ignored.psd"))).code());
        }
    }

    @Test
    void listedButUnreferencedRasterAssetIsRejected() throws Exception {
        var manifest = fullManifest();
        var assets = new ArrayList<Object>((List<?>) manifest.get("assets"));
        assets.add(
                Map.of("assetId", "unused", "path", "assets/unused.png", "width", 2, "height", 2));
        manifest.put("assets", assets);
        byte[] archive =
                ContractTests.archive(
                        manifest,
                        Map.of(
                                "assets/asset.png",
                                ContractTests.png(ContractTests.image()),
                                "assets/unused.png",
                                ContractTests.png(ContractTests.image())));
        var error = assertThrows(ServiceFailure.class, () -> HelperClient.readPsdArchive(archive));
        assertEquals("INVALID_RESPONSE", error.code());
    }

    @Test
    void explicitRetryabilityIsPreserved() {
        for (boolean value : List.of(false, true)) {
            var wire = new ServiceFailure("INFERENCE_FAILED", "failure", "request", 500, value);
            assertEquals(value, Failures.ai(wire).retryable());
            assertEquals(value, Failures.psd(wire).retryable());
        }
    }

    @Test
    void startupCallbackCanWaitForAnotherThreadUsingTheManager() throws Exception {
        Path dir = Files.createTempDirectory("runtime-callback-");
        var config = fake(dir, "'device':'cpu','aiReady':True", "normal");
        Path script = dir.resolve("src/editor_helper.py");
        Files.writeString(
                script,
                Files.readString(script)
                        .replace("print(json.dumps", "time.sleep(0.3)\nprint(json.dumps"));
        try (var manager = new jp.ac.example.imageeditor.integration.runtime.LocalServiceManager();
                var caller = Executors.newSingleThreadExecutor()) {
            var callback =
                    manager.start(config)
                            .stage()
                            .thenApply(
                                    status -> {
                                        try {
                                            return caller.submit(() -> get(manager.start(config)))
                                                    .get(2, TimeUnit.SECONDS);
                                        } catch (Exception e) {
                                            throw new CompletionException(e);
                                        }
                                    });
            assertEquals(
                    ServiceState.RUNNING,
                    callback.toCompletableFuture().get(10, TimeUnit.SECONDS).state());
        }
    }

    @Test
    void aiInvalidationAndSupersessionNotifyOutsideTheFacadeLock() throws Exception {
        for (boolean supersede : List.of(false, true)) {
            try (var server = new ContractTests.Server();
                    var client = server.client()) {
                var ai = new HttpAiSegmentationService(() -> client, () -> server.instance);
                var image = get(ai.registerImage(ContractTests.image(), "r"));
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                server.handler =
                        event -> {
                            entered.countDown();
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return server.normal(event);
                        };
                try {
                    var request =
                            new AiRequest(
                                    image,
                                    UUID.randomUUID(),
                                    List.of(new PromptPoint(0, 0, PromptLabel.POSITIVE)));
                    var first = ai.segment(request);
                    assertTrue(entered.await(2, TimeUnit.SECONDS));
                    var callback = first.stage().handle((value, error) -> Thread.holdsLock(ai));
                    if (supersede) {
                        var next =
                                ai.segment(
                                        new AiRequest(image, UUID.randomUUID(), request.points()));
                        next.cancel();
                    } else ai.invalidateRequests(image);
                    assertFalse(callback.toCompletableFuture().get(2, TimeUnit.SECONDS));
                    assertEquals(ErrorCode.CANCELLED, error(first).code());
                } finally {
                    release.countDown();
                }
            }
        }
    }

    @Test
    void stubCloseNotifiesOutsideTheStubLock() throws Exception {
        try (var stub = new StubBackend()) {
            var operation = stub.registerImage(ContractTests.image(), "r");
            var callback = operation.stage().handle((value, error) -> Thread.holdsLock(stub));
            stub.close();
            assertFalse(callback.toCompletableFuture().get(1, TimeUnit.SECONDS));
            assertEquals(ErrorCode.CANCELLED, error(operation).code());
            assertNull(stub.take(1, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void requestSubmittedByCancellationCallbackRemainsTheNewest() throws Exception {
        try (var server = new ContractTests.Server();
                var client = server.client()) {
            var ai = new HttpAiSegmentationService(() -> client, () -> server.instance);
            var image = get(ai.registerImage(ContractTests.image(), "r"));
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            server.handler =
                    event -> {
                        entered.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return server.normal(event);
                    };
            try {
                var points = List.of(new PromptPoint(0, 0, PromptLabel.POSITIVE));
                var first = ai.segment(new AiRequest(image, UUID.randomUUID(), points));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var newestId = UUID.randomUUID();
                var callback =
                        first.stage()
                                .handle(
                                        (value, error) ->
                                                ai.segment(new AiRequest(image, newestId, points)));
                var second = ai.segment(new AiRequest(image, UUID.randomUUID(), points));
                assertEquals(ErrorCode.CANCELLED, error(second).code());
                release.countDown();
                var newest = callback.toCompletableFuture().get(2, TimeUnit.SECONDS);
                assertEquals(newestId, get(newest).requestId());
            } finally {
                release.countDown();
            }
        }
    }
}
