package editor;

import editor.processing.ImageProcessor;
import editor.processing.Processing.*;

import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.util.Arrays;

/** Manual benchmark; timings are observations, never CI assertions. */
public final class ProcessingBenchmark {
    public static void main(String[] args) {
        int repetitions = args.length == 0 ? 3 : Integer.parseInt(args[0]);
        if (repetitions < 1) throw new IllegalArgumentException("Positive repetitions required");
        for (int width : new int[] {1920, 3840}) {
            int height = width * 9 / 16;
            BufferedImage source =
                    new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    source.setRGB(
                            x,
                            y,
                            ((x + y) % 256 << 24)
                                    | (x % 256 << 16)
                                    | (y % 256 << 8)
                                    | ((x * 7 + y * 3) % 256));
                }
            }
            for (Operation operation : Operation.values()) {
                Parameters parameters =
                        switch (operation) {
                            case CROP -> new Crop(new Rect(10, 10, width - 20, height - 20));
                            case RESIZE -> new Resize(width / 2, height / 2);
                            case COLOR_ADJUST -> new ColorAdjust(20, 1.2, 0.8);
                            case BLUR -> new Blur(20);
                            case MOSAIC -> new Mosaic(12);
                            default -> new None();
                        };
                Request request = new Request(source, operation, parameters);
                ImageProcessor processor = new ImageProcessor();
                long start = System.nanoTime();
                Result cold = processor.process(request, Context.defaults());
                double firstMs = (System.nanoTime() - start) / 1e6;
                int checksum = cold.image().getRGB(0, 0);
                cold = null;
                System.gc();
                for (var pool : ManagementFactory.getMemoryPoolMXBeans()) pool.resetPeakUsage();
                double[] times = new double[repetitions];
                for (int i = 0; i < repetitions; i++) {
                    start = System.nanoTime();
                    Result result = processor.process(request, Context.defaults());
                    times[i] = (System.nanoTime() - start) / 1e6;
                    checksum ^=
                            result.image()
                                    .getRGB(
                                            result.image().getWidth() / 2,
                                            result.image().getHeight() / 2);
                }
                long heapPeak =
                        ManagementFactory.getMemoryPoolMXBeans().stream()
                                .filter(p -> p.getType() == java.lang.management.MemoryType.HEAP)
                                .mapToLong(p -> p.getPeakUsage().getUsed())
                                .sum();
                Arrays.sort(times);
                System.out.printf(
                        java.util.Locale.ROOT,
                        "{\"width\":%d,\"height\":%d,\"operation\":\"%s\","
                                + "\"first_ms\":%.3f,\"median_ms\":%.3f,\"max_ms\":%.3f,"
                                + "\"heap_pool_peak_sum_bytes\":%d,\"checksum\":%d}%n",
                        width,
                        height,
                        operation,
                        firstMs,
                        times[times.length / 2],
                        times[times.length - 1],
                        heapPeak,
                        checksum);
            }
        }
    }
}
