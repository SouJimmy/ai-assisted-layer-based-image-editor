package editor.integration;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Owns exactly one helper process. Startup may block; call outside the JavaFX thread. */
public final class LocalServiceManager implements AutoCloseable {
    private Process process;
    private boolean disposed;
    private HelperClient client;
    private final ExecutorService readers =
            Executors.newCachedThreadPool(
                    r -> {
                        Thread t = new Thread(r, "helper-output");
                        t.setDaemon(true);
                        return t;
                    });

    public synchronized HelperClient start(Path python, Path workingDirectory, Path manifest)
            throws IOException {
        if (disposed) throw new IOException("Service manager has been disposed");
        if (process != null && process.isAlive()) return client;
        if (process != null || client != null) close();
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        List<String> args =
                new ArrayList<>(
                        List.of(
                                python.toAbsolutePath().toString(),
                                "-m",
                                "editor_helper",
                                "--host",
                                "127.0.0.1",
                                "--port",
                                "0"));
        if (manifest != null)
            args.addAll(List.of("--model-manifest", manifest.toAbsolutePath().toString()));
        ProcessBuilder builder = new ProcessBuilder(args).directory(workingDirectory.toFile());
        builder.environment().put("EDITOR_HELPER_TOKEN", token);
        // Source-layout development works without editable installation. Never inherit a
        // conflicting PYTHONPATH.
        builder.environment()
                .put("PYTHONPATH", workingDirectory.resolve("src").toAbsolutePath().toString());
        process = builder.start();
        Process child = process;
        readers.submit(
                () -> {
                    try (var in = child.getErrorStream()) {
                        in.transferTo(OutputStream.nullOutputStream());
                    } catch (IOException ignored) {
                    }
                });
        Future<String> line =
                readers.submit(
                        () -> {
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            InputStream in = child.getInputStream();
                            int b;
                            while ((b = in.read()) != -1 && b != '\n') {
                                if (out.size() >= 8192)
                                    throw new IOException("Startup message too large");
                                out.write(b);
                            }
                            if (b == -1) throw new IOException("Helper exited before handshake");
                            return out.toString(StandardCharsets.UTF_8);
                        });
        try {
            Map<String, Object> data = Json.object(Json.parse(line.get(10, TimeUnit.SECONDS)));
            int port = Json.integer(data, "port");
            if (!"listening".equals(Json.string(data, "event"))
                    || Json.integer(data, "apiVersion") != 1
                    || !"127.0.0.1".equals(Json.string(data, "host"))
                    || port < 1
                    || port > 65535
                    || ((Number) data.get("pid")).longValue() != child.pid())
                throw new IOException("Invalid helper handshake");
            String instance = Json.string(data, "serviceInstanceId");
            UUID.fromString(instance);
            client = new HelperClient(URI.create("http://127.0.0.1:" + port), token);
            Map<String, Object> health = client.health().get(3, TimeUnit.SECONDS);
            if (!instance.equals(Json.string(health, "serviceInstanceId")))
                throw new IOException("Helper instance mismatch");
            readers.submit(
                    () -> {
                        try (var in = child.getInputStream()) {
                            in.transferTo(OutputStream.nullOutputStream());
                        } catch (IOException ignored) {
                        }
                    });
            return client;
        } catch (Exception e) {
            close();
            throw new IOException("ローカルサービスを起動できません", e);
        }
    }

    @Override
    public synchronized void close() {
        if (client != null) {
            client.close();
            client = null;
        }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            process = null;
        }
    }

    /** Permanently release output-reader threads after the last start/stop cycle. */
    public synchronized void dispose() {
        disposed = true;
        close();
        readers.shutdownNow();
    }
}
