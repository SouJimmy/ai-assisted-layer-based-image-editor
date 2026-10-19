package jp.ac.example.imageeditor.testing;

import jp.ac.example.imageeditor.contract.ai.*;
import jp.ac.example.imageeditor.contract.common.*;
import jp.ac.example.imageeditor.integration.runtime.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/** Actual shared-contract -> owned Python 3.11 -> MobileSAM / typed PSD round trip. */
public final class IntegrationSmoke {
    private static <T> T get(AsyncOperation<T> operation, int seconds) throws Exception {
        return operation.stage().toCompletableFuture().get(seconds, TimeUnit.SECONDS);
    }

    public static void main(String[] args) throws Exception {
        Path service = Path.of(args[0]).toAbsolutePath();
        try (var manager = new LocalServiceManager()) {
            get(
                    manager.start(
                            new ServiceConfiguration(
                                    service.resolve(".venv/bin/python"),
                                    service,
                                    service.resolve("models/model-manifest.json"),
                                    service.resolve("../logs"))),
                    15);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!manager.status().aiReady() && System.nanoTime() < deadline) {
                if (manager.status().aiError().isPresent())
                    throw new AssertionError(manager.status());
                Thread.sleep(100);
            }
            if (!manager.status().aiReady()) throw new AssertionError("AI preparation timeout");
            BufferedImage image = new BufferedImage(128, 96, BufferedImage.TYPE_INT_ARGB_PRE);
            var g = image.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, 128, 96);
                g.setColor(Color.RED);
                g.fillRect(20, 15, 60, 60);
                g.setColor(Color.BLUE);
                g.fillOval(90, 20, 25, 25);
            } finally {
                g.dispose();
            }
            var ai = manager.aiService();
            var registered = get(ai.registerImage(image, "revision-1"), 30);
            var response =
                    get(
                            ai.segment(
                                    new AiRequest(
                                            registered,
                                            UUID.randomUUID(),
                                            List.of(
                                                    new PromptPoint(
                                                            40, 40, PromptLabel.POSITIVE)))),
                            15);
            if (response.candidates().isEmpty() || response.candidates().size() > 3)
                throw new AssertionError("candidate count");
            var corrected =
                    get(
                            ai.segment(
                                    new AiRequest(
                                            registered,
                                            UUID.randomUUID(),
                                            List.of(
                                                    new PromptPoint(40, 40, PromptLabel.POSITIVE),
                                                    new PromptPoint(
                                                            105, 30, PromptLabel.NEGATIVE)))),
                            15);
            if (corrected.candidates().isEmpty()) throw new AssertionError("correction");
            get(ai.releaseImage(registered), 15);
            var psd = get(manager.psdService().importPsd(Path.of(args[1])), 60);
            if (psd.rasterAssets().isEmpty() || psd.nodes().isEmpty())
                throw new AssertionError("PSD assets missing");
            System.out.println(
                    "Shared-contract real round trip passed: device="
                            + response.deviceUsed()
                            + ", candidates="
                            + response.candidates().size()
                            + ", PSD nodes="
                            + psd.nodes().size());
        }
    }
}
