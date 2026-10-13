package editor;

import static editor.processing.Processing.*;

import editor.processing.*;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public final class RegressionTests {
    static int checks;
    static final ImageProcessor PROCESSOR = new ImageProcessor();

    static void check(boolean ok, String description) {
        checks++;
        if (!ok) throw new AssertionError(description);
    }

    static BufferedImage run(BufferedImage source, Operation op, Parameters p) {
        int[] before = data(source);
        Result r = PROCESSOR.process(new Request(source, op, p));
        check(Arrays.equals(before, data(source)), "source mutated by " + op);
        check(r.image() != source, "shared result");
        check(r.image().getType() == BufferedImage.TYPE_INT_ARGB_PRE, "image type");
        return r.image();
    }

    static int[] data(BufferedImage s) {
        return (int[]) s.getRaster().getDataElements(0, 0, s.getWidth(), s.getHeight(), null);
    }

    static BufferedImage pre(int w, int h, int... pixels) {
        BufferedImage s = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        s.getRaster().setDataElements(0, 0, w, h, pixels);
        return s;
    }

    static void failure(Runnable action, Code code) {
        try {
            action.run();
            throw new AssertionError("expected " + code);
        } catch (Failure e) {
            check(e.code() == code, "wrong failure: " + e.code());
        }
    }

    public static void main(String[] args) throws Exception {
        BufferedImage source =
                pre(3, 2, 0xff100000, 0xff200000, 0xff300000, 0xff400000, 0xff500000, 0xff600000);
        check(
                Arrays.equals(
                        data(run(source, Operation.ROTATE_RIGHT, new None())),
                        new int[] {
                            0xff400000, 0xff100000, 0xff500000, 0xff200000, 0xff600000, 0xff300000
                        }),
                "right rotation");
        BufferedImage turned = source;
        for (int i = 0; i < 4; i++) turned = run(turned, Operation.ROTATE_RIGHT, new None());
        check(Arrays.equals(data(source), data(turned)), "four rotations");
        check(
                Arrays.equals(
                        data(source),
                        data(
                                run(
                                        run(source, Operation.ROTATE_LEFT, new None()),
                                        Operation.ROTATE_RIGHT,
                                        new None()))),
                "inverse rotation");
        for (Operation op : new Operation[] {Operation.FLIP_H, Operation.FLIP_V})
            check(
                    Arrays.equals(
                            data(source), data(run(run(source, op, new None()), op, new None()))),
                    "inverse flip");
        BufferedImage crop = run(source, Operation.CROP, new Crop(new Rect(1, 1, 2, 1)));
        check(Arrays.equals(data(crop), new int[] {0xff500000, 0xff600000}), "crop offset");
        crop.setRGB(0, 0, 0);
        check(source.getRGB(1, 1) == 0xff500000, "crop aliases source");
        BufferedImage red = pre(1, 1, 0xffff0000);
        check(run(red, Operation.GRAYSCALE, new None()).getRGB(0, 0) == 0xff4c4c4c, "grayscale");
        check(
                run(pre(1, 1, 0xffffffff), Operation.SEPIA, new None()).getRGB(0, 0) == 0xffffffef,
                "sepia");
        check(
                run(pre(1, 1, 0xff0a141e), Operation.NEGATIVE, new None()).getRGB(0, 0)
                        == 0xfff5ebe1,
                "negative");
        check(
                run(red, Operation.COLOR_ADJUST, new ColorAdjust(0, 0, 1)).getRGB(0, 0)
                        == 0xff808080,
                "contrast zero");
        check(
                run(red, Operation.COLOR_ADJUST, new ColorAdjust(0, 1, 0)).getRGB(0, 0)
                        == 0xff4c4c4c,
                "saturation zero");
        BufferedImage lowAlpha = pre(2, 1, 0x01010000, 0x03010203);
        for (Parameters p :
                new Parameters[] {
                    new Resize(2, 1), new ColorAdjust(0, 1, 1), new Blur(0), new Mosaic(1)
                }) {
            Operation op =
                    p instanceof Resize
                            ? Operation.RESIZE
                            : p instanceof ColorAdjust
                                    ? Operation.COLOR_ADJUST
                                    : p instanceof Blur ? Operation.BLUR : Operation.MOSAIC;
            check(Arrays.equals(data(lowAlpha), data(run(lowAlpha, op, p))), "identity raw pixels");
        }
        BufferedImage transparent = pre(2, 1, 0xffff0000, 0);
        BufferedImage resized = run(transparent, Operation.RESIZE, new Resize(3, 1));
        check(data(resized)[1] == 0x80800000, "premultiplied interpolation");
        BufferedImage mosaic = run(transparent, Operation.MOSAIC, new Mosaic(2));
        check(
                Arrays.equals(data(mosaic), new int[] {0x80800000, 0x80800000}),
                "alpha-weighted mosaic");
        BufferedImage odd = pre(3, 1, 0xff000000, 0xffffffff, 0xff102030);
        int[] m = data(run(odd, Operation.MOSAIC, new Mosaic(2)));
        check(m[0] == 0xff808080 && m[1] == m[0] && m[2] == 0xff102030, "partial mosaic block");
        Random random = new Random(7);
        for (int radius : new int[] {1, 3, 20})
            for (int h : new int[] {1, 3, 11}) {
                int w = 7;
                int[] pixels = new int[w * h];
                for (int i = 0; i < pixels.length; i++) {
                    int a = random.nextInt(256);
                    pixels[i] =
                            (a << 24)
                                    | (random.nextInt(a + 1) << 16)
                                    | (random.nextInt(a + 1) << 8)
                                    | random.nextInt(a + 1);
                }
                BufferedImage in = pre(w, h, pixels),
                        out = run(in, Operation.BLUR, new Blur(radius));
                int[] expected = referenceBlur(pixels, w, h, radius), actual = data(out);
                for (int i = 0; i < actual.length; i++)
                    for (int shift = 0; shift <= 24; shift += 8)
                        check(
                                Math.abs(
                                                ((actual[i] >>> shift) & 255)
                                                        - ((expected[i] >>> shift) & 255))
                                        <= 1,
                                "blur reference at " + i);
            }
        AtomicInteger progress = new AtomicInteger();
        failure(
                () ->
                        PROCESSOR.process(
                                new Request(source, Operation.BLUR, new Blur(3)),
                                new Context(() -> true, v -> progress.incrementAndGet())),
                Code.CANCELLED);
        check(progress.get() == 0, "cancel publishes progress");
        failure(
                () ->
                        PROCESSOR.process(
                                new Request(
                                        source,
                                        Operation.CROP,
                                        new Crop(new Rect(Integer.MAX_VALUE, 0, 1, 1)))),
                Code.INVALID_ARGUMENT);
        failure(
                () ->
                        PROCESSOR.process(
                                new Request(source, Operation.RESIZE, new Resize(16384, 16384))),
                Code.INVALID_ARGUMENT);
        failure(
                () ->
                        PROCESSOR.process(
                                new Request(
                                        source,
                                        Operation.COLOR_ADJUST,
                                        new ColorAdjust(0, Double.NaN, 1))),
                Code.INVALID_ARGUMENT);
        failure(
                () -> PROCESSOR.process(new Request(source, Operation.BLUR, new None())),
                Code.INVALID_ARGUMENT);
        failure(() -> PROCESSOR.process(null), Code.INVALID_ARGUMENT);
        java.util.Map<String, Object> json =
                editor.integration.Json.object(
                        editor.integration.Json.parse(
                                "{\"name\":\"画像\\n\",\"values\":[1,true,null]}"));
        check(editor.integration.Json.string(json, "name").equals("画像\n"), "JSON escape/unicode");
        check(
                editor.integration.Json.parse(editor.integration.Json.write(json)).equals(json),
                "JSON round trip");
        for (String text :
                new String[] {"{\"a\":1,\"a\":2}", "[1,]", "01", "1e999", "true false"}) {
            try {
                editor.integration.Json.parse(text);
                throw new AssertionError("Invalid JSON accepted");
            } catch (IllegalArgumentException expected) {
                checks++;
            }
        }
        try {
            editor.integration.HelperClient.readPsdArchive(new byte[] {1, 2, 3});
            throw new AssertionError("bad archive");
        } catch (editor.integration.ServiceFailure e) {
            check(e.code().equals("INVALID_RESPONSE"), "bad ZIP error");
        }
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1),
                release = new java.util.concurrent.CountDownLatch(1);
        try (ProcessingExecutor executor = new ProcessingExecutor()) {
            Request request = new Request(source, Operation.GRAYSCALE, new None());
            var first =
                    executor.submit(
                            request,
                            v -> {
                                entered.countDown();
                                try {
                                    release.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            });
            check(entered.await(2, java.util.concurrent.TimeUnit.SECONDS), "worker start");
            var second = executor.submit(request);
            var third = executor.submit(request);
            var fourth = executor.submit(request);
            try {
                fourth.get();
                throw new AssertionError("full queue accepted");
            } catch (java.util.concurrent.ExecutionException e) {
                check(e.getCause() instanceof Failure f && f.code() == Code.BUSY, "queue error");
            }
            second.cancel(true);
            release.countDown();
            first.get(2, java.util.concurrent.TimeUnit.SECONDS);
            third.get(2, java.util.concurrent.TimeUnit.SECONDS);
            check(second.isCancelled(), "queued cancellation");
        } finally {
            release.countDown();
        }
        check(Filters.grayscale().apply(red).getRGB(0, 0) == 0xff4c4c4c, "ImageFilter adapter");
        System.out.println("Java regression checks passed: " + checks);
    }

    // Independent direct 2D convolution to exercise ring-buffer boundaries and premultiplication.
    static int[] referenceBlur(int[] pixels, int w, int h, int radius) {
        double sigma = Math.max(radius / 3.0, .5), total = 0;
        double[] kernel = new double[radius * 2 + 1];
        for (int i = -radius; i <= radius; i++) {
            kernel[i + radius] = Math.exp(-i * i / (2 * sigma * sigma));
            total += kernel[i + radius];
        }
        for (int i = 0; i < kernel.length; i++) kernel[i] /= total;
        int[] out = new int[pixels.length];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int value = 0, alpha = 255;
                for (int shift = 24; shift >= 0; shift -= 8) {
                    double sum = 0;
                    for (int dy = -radius; dy <= radius; dy++)
                        for (int dx = -radius; dx <= radius; dx++) {
                            int ix = Math.max(0, Math.min(w - 1, x + dx)),
                                    iy = Math.max(0, Math.min(h - 1, y + dy));
                            sum +=
                                    ((pixels[iy * w + ix] >>> shift) & 255)
                                            * kernel[dx + radius]
                                            * kernel[dy + radius];
                        }
                    int c = Math.min(alpha, (int) Math.floor(sum + .5));
                    if (shift == 24) alpha = c;
                    value |= c << shift;
                }
                out[y * w + x] = value;
            }
        return out;
    }
}
