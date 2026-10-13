package editor.processing;

import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.function.DoubleConsumer;

/** Provisional B-side contract. No dependency on JavaFX or the document model. */
public final class Processing {
    private Processing() {}
    public enum Operation { CROP, ROTATE_RIGHT, ROTATE_LEFT, FLIP_H, FLIP_V, RESIZE,
        COLOR_ADJUST, GRAYSCALE, SEPIA, NEGATIVE, BLUR, MOSAIC }
    public enum Code { INVALID_ARGUMENT, RESOURCE_LIMIT, CANCELLED, BUSY }
    public static final class Failure extends RuntimeException {
        private final Code code;
        public Failure(Code code, String message) { super(message); this.code = code; }
        public Code code() { return code; }
    }
    public sealed interface Parameters permits None, Crop, Resize, ColorAdjust, Blur, Mosaic {}
    public record None() implements Parameters {}
    public record Rect(int x, int y, int width, int height) {}
    public record Crop(Rect rect) implements Parameters {}
    public record Resize(int width, int height) implements Parameters {}
    public record ColorAdjust(int brightness, double contrast, double saturation) implements Parameters {}
    public record Blur(int radius) implements Parameters {}
    public record Mosaic(int blockSize) implements Parameters {}
    public record Request(BufferedImage source, Operation operation, Parameters parameters) {}
    public record Result(BufferedImage image, Rect sourceChangedBounds, Rect outputChangedBounds,
                         boolean geometryChanged, boolean deterministic, String algorithmVersion,
                         Operation operation, Parameters parameters) {}
    @FunctionalInterface public interface Cancellation { boolean requested(); }
    public record Context(Cancellation cancellation, DoubleConsumer progress) {
        public Context { Objects.requireNonNull(cancellation); Objects.requireNonNull(progress); }
        public static Context defaults() { return new Context(() -> false, ignored -> {}); }
        public void check() {
            if (cancellation.requested() || Thread.currentThread().isInterrupted())
                throw new Failure(Code.CANCELLED, "処理を取り消しました");
        }
    }
    @FunctionalInterface public interface ImageFilter { BufferedImage apply(BufferedImage source); }
}
