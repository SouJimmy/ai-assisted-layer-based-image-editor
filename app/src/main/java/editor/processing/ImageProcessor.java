package editor.processing;

import static editor.processing.Processing.*;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/** Pure, deterministic image processing. Every result owns its pixels. */
public final class ImageProcessor {
    public static final long MAX_PIXELS = 33_554_432;
    public static final int MAX_SIDE = 16_384;

    public static void checkDimensions(int w, int h) {
        if (w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE || (long) w * h > MAX_PIXELS)
            throw new Failure(Code.INVALID_ARGUMENT, "画像サイズが範囲外です");
    }

    public Result process(Request request) {
        return process(request, Context.defaults());
    }

    public Result process(Request request, Context context) {
        if (request == null
                || request.source() == null
                || request.operation() == null
                || request.parameters() == null
                || context == null) invalid("入力が不足しています");
        BufferedImage source = request.source();
        checkDimensions(source.getWidth(), source.getHeight());
        validate(request);
        context.check();
        BufferedImage image = copyAsPremultipliedArgb(source);
        Progress progress = new Progress(context);
        BufferedImage out =
                switch (request.operation()) {
                    case ROTATE_RIGHT, ROTATE_LEFT, FLIP_H, FLIP_V ->
                            geometry(image, request.operation(), context, progress);
                    case CROP ->
                            crop(image, ((Crop) request.parameters()).rect(), context, progress);
                    case RESIZE -> resize(image, (Resize) request.parameters(), context, progress);
                    case BLUR ->
                            blur(image, ((Blur) request.parameters()).radius(), context, progress);
                    case MOSAIC ->
                            mosaic(
                                    image,
                                    ((Mosaic) request.parameters()).blockSize(),
                                    context,
                                    progress);
                    default ->
                            colors(
                                    image,
                                    request.operation(),
                                    request.parameters(),
                                    context,
                                    progress);
                };
        context.check();
        context.progress().accept(1);
        return new Result(
                out,
                new Rect(0, 0, source.getWidth(), source.getHeight()),
                new Rect(0, 0, out.getWidth(), out.getHeight()),
                source.getWidth() != out.getWidth() || source.getHeight() != out.getHeight(),
                true,
                "v1",
                request.operation(),
                request.parameters());
    }

    private void validate(Request request) {
        boolean correct =
                switch (request.operation()) {
                    case CROP -> request.parameters() instanceof Crop;
                    case RESIZE -> request.parameters() instanceof Resize;
                    case COLOR_ADJUST -> request.parameters() instanceof ColorAdjust;
                    case BLUR -> request.parameters() instanceof Blur;
                    case MOSAIC -> request.parameters() instanceof Mosaic;
                    default -> request.parameters() instanceof None;
                };
        if (!correct) invalid("処理とパラメータの型が一致しません");
        if (request.parameters() instanceof Crop parameters) {
            Rect rectangle = parameters.rect();
            if (rectangle == null
                    || rectangle.x() < 0
                    || rectangle.y() < 0
                    || rectangle.width() < 1
                    || rectangle.height() < 1
                    || (long) rectangle.x() + rectangle.width() > request.source().getWidth()
                    || (long) rectangle.y() + rectangle.height() > request.source().getHeight())
                invalid("切り抜き範囲が不正です");
        } else if (request.parameters() instanceof Resize parameters)
            checkDimensions(parameters.width(), parameters.height());
        else if (request.parameters() instanceof ColorAdjust parameters) {
            if (parameters.brightness() < -255
                    || parameters.brightness() > 255
                    || !Double.isFinite(parameters.contrast())
                    || !Double.isFinite(parameters.saturation())
                    || parameters.contrast() < 0
                    || parameters.contrast() > 3
                    || parameters.saturation() < 0
                    || parameters.saturation() > 3) invalid("色調整値が不正です");
        } else if (request.parameters() instanceof Blur parameters) {
            if (parameters.radius() < 0 || parameters.radius() > 20) invalid("ぼかし半径が不正です");
        } else if (request.parameters() instanceof Mosaic parameters) {
            if (parameters.blockSize() < 1 || parameters.blockSize() > 128) invalid("モザイクサイズが不正です");
        }
    }

    private static void invalid(String s) {
        throw new Failure(Code.INVALID_ARGUMENT, s);
    }

    private static BufferedImage newArgbImage(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
    }

    private static BufferedImage copyAsPremultipliedArgb(BufferedImage source) {
        BufferedImage out = newArgbImage(source.getWidth(), source.getHeight());
        if (source.getType() == BufferedImage.TYPE_INT_ARGB_PRE)
            out.getRaster().setRect(source.getRaster());
        else {
            Graphics2D g = out.createGraphics();
            try {
                g.setComposite(AlphaComposite.Src);
                g.drawImage(source, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        return out;
    }

    private static int readPremultipliedPixel(BufferedImage image, int x, int y) {
        return ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer())
                .getData()[y * image.getWidth() + x];
    }

    private static void writePremultipliedPixel(BufferedImage image, int x, int y, int pixel) {
        ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer())
                        .getData()[y * image.getWidth() + x] =
                pixel;
    }

    private BufferedImage geometry(
            BufferedImage source, Operation operation, Context context, Progress progress) {
        int w = source.getWidth(), h = source.getHeight();
        boolean rotate = operation == Operation.ROTATE_LEFT || operation == Operation.ROTATE_RIGHT;
        BufferedImage result = newArgbImage(rotate ? h : w, rotate ? w : h);
        for (int y = 0; y < h; y++) {
            context.check();
            for (int x = 0; x < w; x++) {
                int dx = x, dy = y;
                switch (operation) {
                    case ROTATE_RIGHT -> {
                        dx = h - 1 - y;
                        dy = x;
                    }
                    case ROTATE_LEFT -> {
                        dx = y;
                        dy = w - 1 - x;
                    }
                    case FLIP_H -> dx = w - 1 - x;
                    case FLIP_V -> dy = h - 1 - y;
                    default -> throw new AssertionError();
                }
                writePremultipliedPixel(result, dx, dy, readPremultipliedPixel(source, x, y));
            }
            progress.send((y + 1.0) / h);
        }
        return result;
    }

    private BufferedImage crop(
            BufferedImage source, Rect rectangle, Context context, Progress progress) {
        BufferedImage result = newArgbImage(rectangle.width(), rectangle.height());
        for (int y = 0; y < rectangle.height(); y++) {
            context.check();
            Object row =
                    source.getRaster()
                            .getDataElements(
                                    rectangle.x(), rectangle.y() + y, rectangle.width(), 1, null);
            result.getRaster().setDataElements(0, y, rectangle.width(), 1, row);
            progress.send((y + 1.0) / rectangle.height());
        }
        return result;
    }

    private BufferedImage resize(
            BufferedImage source, Resize parameters, Context context, Progress progress) {
        if (source.getWidth() == parameters.width() && source.getHeight() == parameters.height())
            return source;
        BufferedImage result = newArgbImage(parameters.width(), parameters.height());
        for (int y = 0; y < parameters.height(); y++) {
            context.check();
            double sy =
                    Math.max(
                            0,
                            Math.min(
                                    source.getHeight() - 1,
                                    (y + .5) * source.getHeight() / parameters.height() - .5));
            int y0 = (int) Math.floor(sy), y1 = Math.min(y0 + 1, source.getHeight() - 1);
            double fy = sy - y0;
            for (int x = 0; x < parameters.width(); x++) {
                double sx =
                        Math.max(
                                0,
                                Math.min(
                                        source.getWidth() - 1,
                                        (x + .5) * source.getWidth() / parameters.width() - .5));
                int x0 = (int) Math.floor(sx), x1 = Math.min(x0 + 1, source.getWidth() - 1);
                double fx = sx - x0;
                int p00 = readPremultipliedPixel(source, x0, y0),
                        p10 = readPremultipliedPixel(source, x1, y0),
                        p01 = readPremultipliedPixel(source, x0, y1),
                        p11 = readPremultipliedPixel(source, x1, y1);
                double w00 = (1 - fx) * (1 - fy),
                        w10 = fx * (1 - fy),
                        w01 = (1 - fx) * fy,
                        w11 = fx * fy;
                int pixel = 0, alpha = 255;
                for (int shift = 24; shift >= 0; shift -= 8) {
                    double v =
                            ((p00 >>> shift) & 255) * w00
                                    + ((p10 >>> shift) & 255) * w10
                                    + ((p01 >>> shift) & 255) * w01
                                    + ((p11 >>> shift) & 255) * w11;
                    int c = Math.min(alpha, roundToByte(v));
                    if (shift == 24) alpha = c;
                    pixel |= c << shift;
                }
                writePremultipliedPixel(result, x, y, pixel);
            }
            progress.send((y + 1.0) / parameters.height());
        }
        return result;
    }

    private BufferedImage colors(
            BufferedImage source,
            Operation operation,
            Parameters parameters,
            Context context,
            Progress progress) {
        if (parameters instanceof ColorAdjust c
                && c.brightness() == 0
                && c.contrast() == 1
                && c.saturation() == 1) return source;
        int w = source.getWidth(), h = source.getHeight();
        BufferedImage result = newArgbImage(w, h);
        for (int y = 0; y < h; y++) {
            context.check();
            int[] row = source.getRGB(0, y, w, 1, null, 0, w);
            for (int x = 0; x < w; x++) {
                int pixel = row[x], a = pixel >>> 24;
                if (a == 0) {
                    row[x] = 0;
                    continue;
                }
                int r = (pixel >>> 16) & 255, g = (pixel >>> 8) & 255, b = pixel & 255;
                int nr = r, ng = g, nb = b;
                switch (operation) {
                    case GRAYSCALE -> nr = ng = nb = roundToByte(.299 * r + .587 * g + .114 * b);
                    case NEGATIVE -> {
                        nr = 255 - r;
                        ng = 255 - g;
                        nb = 255 - b;
                    }
                    case SEPIA -> {
                        nr = roundToByte(.393 * r + .769 * g + .189 * b);
                        ng = roundToByte(.349 * r + .686 * g + .168 * b);
                        nb = roundToByte(.272 * r + .534 * g + .131 * b);
                    }
                    case COLOR_ADJUST -> {
                        ColorAdjust c = (ColorAdjust) parameters;
                        r =
                                roundToByte(
                                        (roundToByte(r + c.brightness()) - 128) * c.contrast()
                                                + 128);
                        g =
                                roundToByte(
                                        (roundToByte(g + c.brightness()) - 128) * c.contrast()
                                                + 128);
                        b =
                                roundToByte(
                                        (roundToByte(b + c.brightness()) - 128) * c.contrast()
                                                + 128);
                        double l = .299 * r + .587 * g + .114 * b;
                        nr = roundToByte(l + c.saturation() * (r - l));
                        ng = roundToByte(l + c.saturation() * (g - l));
                        nb = roundToByte(l + c.saturation() * (b - l));
                    }
                    default -> throw new AssertionError();
                }
                row[x] = (a << 24) | (nr << 16) | (ng << 8) | nb;
            }
            result.setRGB(0, y, w, 1, row, 0, w);
            progress.send((y + 1.0) / h);
        }
        return result;
    }

    private BufferedImage mosaic(
            BufferedImage source, int size, Context context, Progress progress) {
        if (size == 1) return source;
        int w = source.getWidth(), h = source.getHeight();
        BufferedImage result = newArgbImage(w, h);
        for (int by = 0; by < h; by += size) {
            context.check();
            int bottom = Math.min(h, by + size);
            for (int bx = 0; bx < w; bx += size) {
                int right = Math.min(w, bx + size);
                long sa = 0, sr = 0, sg = 0, sb = 0;
                int[] pixels = source.getRGB(bx, by, right - bx, bottom - by, null, 0, right - bx);
                for (int v : pixels) {
                    int a = v >>> 24;
                    sa += a;
                    sr += ((v >>> 16) & 255) * (long) a;
                    sg += ((v >>> 8) & 255) * (long) a;
                    sb += (v & 255) * (long) a;
                }
                int a = roundToByte((double) sa / pixels.length);
                int v =
                        sa == 0
                                ? 0
                                : (a << 24)
                                        | (roundToByte((double) sr / sa) << 16)
                                        | (roundToByte((double) sg / sa) << 8)
                                        | roundToByte((double) sb / sa);
                Arrays.fill(pixels, v);
                result.setRGB(bx, by, right - bx, bottom - by, pixels, 0, right - bx);
            }
            progress.send((double) bottom / h);
        }
        return result;
    }

    private BufferedImage blur(
            BufferedImage source, int radius, Context context, Progress progress) {
        if (radius == 0) return source;
        int w = source.getWidth(), h = source.getHeight();
        double sigma = Math.max(radius / 3.0, .5), sum = 0;
        double[] kernel = new double[2 * radius + 1];
        for (int i = -radius; i <= radius; i++) {
            kernel[i + radius] = Math.exp(-i * i / (2 * sigma * sigma));
            sum += kernel[i + radius];
        }
        for (int i = 0; i < kernel.length; i++) kernel[i] /= sum;
        // Ring of horizontally filtered horizontalRows; memory is O(width * radius), not O(width *
        // height).
        double[][] horizontalRows = new double[kernel.length][];
        int[] cachedRowIndices = new int[kernel.length];
        Arrays.fill(cachedRowIndices, -1);
        BufferedImage result = newArgbImage(w, h);
        double[] accumulated = new double[w * 4];
        int[] line = new int[w];
        for (int y = 0; y < h; y++) {
            context.check();
            for (int ky = -radius; ky <= radius; ky++) {
                int iy = Math.max(0, Math.min(h - 1, y + ky)), slot = iy % horizontalRows.length;
                if (cachedRowIndices[slot] != iy) {
                    if (horizontalRows[slot] == null) horizontalRows[slot] = new double[w * 4];
                    horizontal(source, iy, kernel, radius, horizontalRows[slot]);
                    cachedRowIndices[slot] = iy;
                }
            }
            Arrays.fill(accumulated, 0);
            // Traverse contiguous channels once per kernel row; avoid row lookups and
            // boundary calculations in the per-pixel/channel inner loop.
            for (int ky = -radius; ky <= radius; ky++) {
                int iy = Math.max(0, Math.min(h - 1, y + ky));
                double[] row = horizontalRows[iy % horizontalRows.length];
                double weight = kernel[ky + radius];
                for (int i = 0; i < accumulated.length; i++) {
                    accumulated[i] += row[i] * weight;
                }
            }
            for (int x = 0; x < w; x++) {
                int offset = x * 4;
                int alpha = roundToByte(accumulated[offset]);
                line[x] =
                        (alpha << 24)
                                | (Math.min(alpha, roundToByte(accumulated[offset + 1])) << 16)
                                | (Math.min(alpha, roundToByte(accumulated[offset + 2])) << 8)
                                | Math.min(alpha, roundToByte(accumulated[offset + 3]));
            }
            result.getRaster().setDataElements(0, y, w, 1, line);
            progress.send((y + 1.0) / h);
        }
        return result;
    }

    private void horizontal(
            BufferedImage source, int y, double[] kernel, int radius, double[] result) {
        int w = source.getWidth();
        int[] pixels = (int[]) source.getRaster().getDataElements(0, y, w, 1, null);
        for (int x = 0; x < w; x++) {
            double alpha = 0, red = 0, green = 0, blue = 0;
            for (int k = -radius; k <= radius; k++) {
                int pixel = pixels[Math.max(0, Math.min(w - 1, x + k))];
                double weight = kernel[k + radius];
                alpha += (pixel >>> 24) * weight;
                red += ((pixel >>> 16) & 255) * weight;
                green += ((pixel >>> 8) & 255) * weight;
                blue += (pixel & 255) * weight;
            }
            int offset = x * 4;
            result[offset] = alpha;
            result[offset + 1] = red;
            result[offset + 2] = green;
            result[offset + 3] = blue;
        }
    }

    private static int roundToByte(double v) {
        return (int) Math.max(0, Math.min(255, Math.floor(v + .5)));
    }

    private static final class Progress {
        private final Context context;
        private long last;

        Progress(Context context) {
            this.context = context;
        }

        void send(double fraction) {
            long now = System.nanoTime();
            if (now - last >= 100_000_000L) {
                last = now;
                context.progress().accept(fraction);
            }
        }
    }
}
