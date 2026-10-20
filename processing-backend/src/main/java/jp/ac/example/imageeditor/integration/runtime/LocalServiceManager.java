package jp.ac.example.imageeditor.integration.runtime;

import jp.ac.example.imageeditor.contract.ai.*;
import jp.ac.example.imageeditor.contract.common.*;
import jp.ac.example.imageeditor.contract.psd.*;
import jp.ac.example.imageeditor.integration.ai.HttpAiSegmentationService;
import jp.ac.example.imageeditor.integration.psd.HttpPsdImportService;
import jp.ac.example.imageeditor.integration.shared.*;
import jp.ac.example.imageeditor.internal.integration.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Owns exactly one helper generation. Process waits never run on the caller's UI thread. */
public final class LocalServiceManager implements AutoCloseable {
    private final Object lock = new Object();
    private final ExecutorService lifecycle =
            Executors.newSingleThreadExecutor(r -> daemon(r, "helper-lifecycle"));
    private final ScheduledExecutorService monitor =
            Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "helper-health"));
    private final AtomicLong generation = new AtomicLong();
    private volatile ServiceStatus status = empty(ServiceState.STOPPED);
    private volatile ServiceConfiguration configuration;
    private volatile jp.ac.example.imageeditor.internal.integration.LocalServiceManager owner;
    private volatile HelperClient client;
    private volatile OperationPromise<ServiceStatus> active;
    private volatile long readyDeadline;
    private final HttpAiSegmentationService ai =
            new HttpAiSegmentationService(
                    this::currentClient, () -> status.serviceInstanceId().orElse(null));
    private final HttpPsdImportService psd =
            new HttpPsdImportService(
                    this::currentClient, () -> status.serviceInstanceId().orElse(null));

    public LocalServiceManager() {
        monitor.scheduleWithFixedDelay(this::refresh, 500, 500, TimeUnit.MILLISECONDS);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private static ServiceStatus empty(ServiceState state) {
        return new ServiceStatus(
                state,
                Optional.empty(),
                false,
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private HelperClient currentClient() {
        return status.state() == ServiceState.RUNNING ? client : null;
    }

    public AiSegmentationService aiService() {
        return ai;
    }

    public PsdImportService psdService() {
        return psd;
    }

    public ServiceStatus status() {
        return status;
    }

    private static OperationPromise<ServiceStatus> operation() {
        return new OperationPromise<>(
                () -> new ServiceLifecycleException(ErrorCode.CANCELLED, "起動を取り消しました", null));
    }

    public AsyncOperation<ServiceStatus> start(ServiceConfiguration value) {
        return launch(value, false);
    }

    public AsyncOperation<ServiceStatus> restart() {
        return launch(configuration, true);
    }

    private AsyncOperation<ServiceStatus> launch(ServiceConfiguration value, boolean restart) {
        OperationPromise<ServiceStatus> result = operation();
        final long epoch;
        synchronized (lock) {
            if (status.state() == ServiceState.CLOSED) {
                result.fail(
                        new ServiceLifecycleException(
                                ErrorCode.SERVICE_UNAVAILABLE, "サービス管理を終了しました", null));
                return result;
            }
            if (value == null) {
                result.fail(
                        new ServiceLifecycleException(
                                ErrorCode.INVALID_ARGUMENT, "起動設定がありません", null));
                return result;
            }
            if (status.state() == ServiceState.STARTING
                    || status.state() == ServiceState.STOPPING) {
                result.fail(new ServiceLifecycleException(ErrorCode.BUSY, "起動または停止中です", null));
                return result;
            }
            if (!restart && status.state() == ServiceState.RUNNING) {
                if (value.equals(configuration)) result.succeed(status);
                else
                    result.fail(
                            new ServiceLifecycleException(
                                    ErrorCode.INVALID_ARGUMENT, "異なる設定で起動中です", null));
                return result;
            }
            configuration = value;
            epoch = generation.incrementAndGet();
            status = empty(restart ? ServiceState.STOPPING : ServiceState.STARTING);
            active = result;
        }
        ai.invalidateAll();
        AtomicReference<Future<?>> task = new AtomicReference<>();
        result.onCancel(
                () -> {
                    synchronized (lock) {
                        if (generation.get() == epoch) {
                            generation.incrementAndGet();
                            if (status.state() != ServiceState.CLOSED)
                                status = empty(ServiceState.STOPPED);
                        }
                    }
                    Future<?> work = task.get();
                    if (work != null) work.cancel(true);
                });
        try {
            Future<?> work = lifecycle.submit(() -> runStart(value, epoch, result));
            task.set(work);
            if (result.isCancelled()) work.cancel(true);
        } catch (RejectedExecutionException e) {
            result.fail(
                    new ServiceLifecycleException(ErrorCode.SERVICE_UNAVAILABLE, "起動受付を終了しました", e));
        }
        return result;
    }

    private void runStart(
            ServiceConfiguration config, long epoch, OperationPromise<ServiceStatus> result) {
        jp.ac.example.imageeditor.internal.integration.LocalServiceManager local = null;
        try {
            var previous = owner;
            if (previous != null) {
                previous.dispose();
                owner = null;
                client = null;
            }
            if (result.isCancelled() || generation.get() != epoch) return;
            synchronized (lock) {
                if (generation.get() != epoch) return;
                status = empty(ServiceState.STARTING);
            }
            local = new jp.ac.example.imageeditor.internal.integration.LocalServiceManager();
            owner = local;
            HelperClient connection =
                    local.start(
                            config.pythonExecutable(),
                            config.serviceDirectory(),
                            config.modelManifest());
            Map<String, Object> health = connection.health().get(3, TimeUnit.SECONDS);
            ServiceStatus started;
            synchronized (lock) {
                if (generation.get() != epoch || result.isCancelled()) return;
                client = connection;
                readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                started = fromHealth(health);
                status = started;
            }
            // Completion can run application callbacks; never hold the lifecycle lock here.
            result.succeed(started);
            diagnostic("SERVICE_STARTED");
        } catch (Exception e) {
            synchronized (lock) {
                if (generation.get() == epoch && status.state() != ServiceState.CLOSED) {
                    ServiceError error =
                            new ServiceError(
                                    ErrorCode.SERVICE_UNAVAILABLE,
                                    "サービスを起動できません",
                                    Optional.empty(),
                                    true);
                    status =
                            new ServiceStatus(
                                    ServiceState.FAILED,
                                    Optional.empty(),
                                    false,
                                    false,
                                    Optional.empty(),
                                    Optional.empty(),
                                    Optional.of(error));
                }
            }
            result.fail(
                    new ServiceLifecycleException(
                            ErrorCode.SERVICE_UNAVAILABLE, "サービスを起動できません", Failures.unwrap(e)));
            diagnostic("SERVICE_START_FAILED");
        } finally {
            if (local != null
                    && (result.isCancelled()
                            || generation.get() != epoch
                            || status.state() != ServiceState.RUNNING)) {
                local.dispose();
                if (owner == local) {
                    owner = null;
                    client = null;
                }
            }
        }
    }

    private ServiceStatus fromHealth(Map<String, Object> health) {
        String instance = Json.string(health, "serviceInstanceId");
        UUID.fromString(instance);
        Optional<DeviceKind> device =
                Optional.ofNullable(health.get("device"))
                        .map(Object::toString)
                        .map(
                                v ->
                                        switch (v) {
                                            case "cpu" -> DeviceKind.CPU;
                                            case "mps" -> DeviceKind.MPS;
                                            default ->
                                                    throw new IllegalArgumentException(
                                                            "Unknown device");
                                        });
        Optional<ServiceError> error = Optional.empty();
        Object raw = health.get("aiError");
        if (raw instanceof String code) {
            try {
                error =
                        Optional.of(
                                new ServiceError(
                                        ErrorCode.valueOf(code),
                                        "AIの準備に失敗しました",
                                        Optional.empty(),
                                        false));
            } catch (IllegalArgumentException e) {
                error =
                        Optional.of(
                                new ServiceError(
                                        ErrorCode.INVALID_RESPONSE,
                                        "AI準備状態が不正です",
                                        Optional.empty(),
                                        false));
            }
        }
        if (raw instanceof Map<?, ?>) {
            var data = Json.object(raw);
            ErrorCode code;
            try {
                code = ErrorCode.valueOf(Json.string(data, "code"));
            } catch (IllegalArgumentException e) {
                code = ErrorCode.INVALID_RESPONSE;
            }
            error = Optional.of(new ServiceError(code, "AIの準備に失敗しました", Optional.empty(), false));
        }
        if (!Boolean.TRUE.equals(health.get("aiReady"))
                && error.isEmpty()
                && System.nanoTime() > readyDeadline)
            error =
                    Optional.of(
                            new ServiceError(
                                    ErrorCode.TIMEOUT, "AIの準備時間を超えました", Optional.empty(), true));
        return new ServiceStatus(
                ServiceState.RUNNING,
                Optional.of(instance),
                Boolean.TRUE.equals(health.get("aiReady")),
                Boolean.TRUE.equals(health.get("psdReady")),
                device,
                error,
                Optional.empty());
    }

    private void refresh() {
        HelperClient connection = currentClient();
        if (connection == null) return;
        long epoch = generation.get();
        var monitoredOwner = owner;
        try {
            var health = connection.health().get(3, TimeUnit.SECONDS);
            if (!status.serviceInstanceId()
                    .orElseThrow()
                    .equals(Json.string(health, "serviceInstanceId")))
                throw new IllegalArgumentException("Instance changed");
            synchronized (lock) {
                if (generation.get() == epoch && currentClient() == connection)
                    status = fromHealth(health);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // A transient health deadline during model imports is not process death.
            if (Failures.code(e) == ErrorCode.TIMEOUT || e instanceof TimeoutException) return;
            synchronized (lock) {
                if (generation.get() != epoch || currentClient() != connection) return;
                status =
                        new ServiceStatus(
                                ServiceState.FAILED,
                                Optional.empty(),
                                false,
                                false,
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of(
                                        new ServiceError(
                                                ErrorCode.SERVICE_UNAVAILABLE,
                                                "サービス接続が失われました",
                                                Optional.empty(),
                                                true)));
            }
            ai.invalidateAll();
            connection.close();
            if (monitoredOwner != null) monitoredOwner.dispose();
            if (owner == monitoredOwner) owner = null;
            if (client == connection) client = null;
            diagnostic("SERVICE_CONNECTION_FAILED");
        }
    }

    /**
     * Only allowlisted events are written; raw child stderr and exception messages are never
     * persisted.
     */
    private synchronized void diagnostic(String event) {
        var config = configuration;
        if (config == null) return;
        try {
            java.nio.file.Files.createDirectories(config.logDirectory());
            var log = config.logDirectory().resolve("helper.log");
            if (java.nio.file.Files.exists(log)
                    && java.nio.file.Files.size(log) > 5L * 1024 * 1024) {
                java.nio.file.Files.deleteIfExists(config.logDirectory().resolve("helper.log.2"));
                var older = config.logDirectory().resolve("helper.log.1");
                if (java.nio.file.Files.exists(older))
                    java.nio.file.Files.move(older, config.logDirectory().resolve("helper.log.2"));
                java.nio.file.Files.move(log, older);
            }
            java.nio.file.Files.writeString(
                    log,
                    java.time.Instant.now() + " " + event + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException | SecurityException ignored) {
        }
    }

    @Override
    public void close() {
        OperationPromise<ServiceStatus> running;
        synchronized (lock) {
            if (status.state() == ServiceState.CLOSED) return;
            generation.incrementAndGet();
            status = empty(ServiceState.CLOSED);
            running = active;
        }
        if (running != null) running.cancel();
        ai.invalidateAll();
        monitor.shutdownNow();
        var local = owner;
        if (local != null) local.dispose();
        owner = null;
        client = null;
        lifecycle.shutdownNow();
        try {
            lifecycle.awaitTermination(10, TimeUnit.SECONDS);
            monitor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        diagnostic("SERVICE_CLOSED");
    }
}
