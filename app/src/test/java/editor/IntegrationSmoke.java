package editor;

import editor.integration.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Actual Java -> child Python -> MobileSAM / PSD round trip. Opt-in because it loads model weights.
 */
public final class IntegrationSmoke {
    public static void main(String[] args) throws Exception {
        Path service = Path.of(args[0]).toAbsolutePath();
        LocalServiceManager manager = new LocalServiceManager();
        try {
            HelperClient client =
                    manager.start(
                            service.resolve(".venv/bin/python"),
                            service,
                            service.resolve("models/model-manifest.json"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            Map<String, Object> health;
            do {
                health = client.health().get(3, TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(health.get("aiReady"))) break;
                if (health.get("aiError") != null) throw new AssertionError(health);
                Thread.sleep(100);
            } while (System.nanoTime() < deadline);
            if (!Boolean.TRUE.equals(health.get("aiReady")))
                throw new AssertionError("AI warmup timeout");
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
            var registered = client.registerImage(image, "revision-1").get(30, TimeUnit.SECONDS);
            var result =
                    client.segment(
                                    registered.imageId(),
                                    "revision-1",
                                    UUID.randomUUID().toString(),
                                    List.of(new HelperClient.Point(40, 40, 1)))
                            .get(15, TimeUnit.SECONDS);
            if (result.candidates().isEmpty() || result.candidates().size() > 3)
                throw new AssertionError("candidate count");
            var corrected =
                    client.segment(
                                    registered.imageId(),
                                    "revision-1",
                                    UUID.randomUUID().toString(),
                                    List.of(
                                            new HelperClient.Point(40, 40, 1),
                                            new HelperClient.Point(105, 30, 0)))
                            .get(15, TimeUnit.SECONDS);
            if (corrected.candidates().isEmpty()) throw new AssertionError("correction");
            client.releaseImage(registered.imageId()).get(15, TimeUnit.SECONDS);
            var psd = client.importPsd(Path.of(args[1])).get(60, TimeUnit.SECONDS);
            if (psd.assets().isEmpty() || psd.nodes().isEmpty())
                throw new AssertionError("PSD assets missing");
            System.out.println(
                    "Java/Python actual round trip passed: device="
                            + result.deviceUsed()
                            + ", candidates="
                            + result.candidates().size()
                            + ", PSD layers="
                            + psd.nodes().size());
        } finally {
            manager.dispose();
        }
    }
}
