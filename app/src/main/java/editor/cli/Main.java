package editor.cli;

import static editor.processing.Processing.*;

import editor.processing.*;

import java.awt.image.BufferedImage;
import java.nio.file.*;

import javax.imageio.ImageIO;

/** B-side processing demo, separate from A's editor UI and file services. */
public final class Main {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println(
                    "Usage: input.png output.png OPERATION [values...]\n"
                        + "RESIZE w h; CROP x y w h; COLOR_ADJUST brightness contrast saturation;"
                        + " BLUR radius; MOSAIC blockSize");
            System.exit(2);
        }
        Path output = Path.of(args[1]);
        if (Files.exists(output)) throw new IllegalArgumentException("出力先は既に存在します。別の名前を指定してください");
        Operation op = Operation.valueOf(args[2]);
        Parameters p =
                switch (op) {
                    case RESIZE -> new Resize(Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                    case CROP ->
                            new Crop(
                                    new Rect(
                                            Integer.parseInt(args[3]),
                                            Integer.parseInt(args[4]),
                                            Integer.parseInt(args[5]),
                                            Integer.parseInt(args[6])));
                    case COLOR_ADJUST ->
                            new ColorAdjust(
                                    Integer.parseInt(args[3]),
                                    Double.parseDouble(args[4]),
                                    Double.parseDouble(args[5]));
                    case BLUR -> new Blur(Integer.parseInt(args[3]));
                    case MOSAIC -> new Mosaic(Integer.parseInt(args[3]));
                    default -> new None();
                };
        BufferedImage input = ImageIO.read(Path.of(args[0]).toFile());
        Result r = new ImageProcessor().process(new Request(input, op, p));
        try (var stream =
                Files.newOutputStream(
                        output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            if (!ImageIO.write(r.image(), "PNG", stream))
                throw new IllegalStateException("PNG writer unavailable");
        }
        System.out.println(r.image().getWidth() + "x" + r.image().getHeight() + " -> " + output);
    }
}
