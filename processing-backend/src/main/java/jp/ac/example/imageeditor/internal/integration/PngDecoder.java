package jp.ac.example.imageeditor.internal.integration;

import jp.ac.example.imageeditor.internal.processing.ImageProcessor;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;

/** Checks encoded dimensions before allocating pixels and reads masks as raw samples. */
final class PngDecoder {
    private PngDecoder() {}

    static BufferedImage read(byte[] data, int w, int h, boolean binary) throws IOException {
        ImageProcessor.checkDimensions(w, h);
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Invalid PNG");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                if (!reader.getFormatName().equalsIgnoreCase("png")
                        || reader.getWidth(0) != w
                        || reader.getHeight(0) != h) throw new IOException("PNG dimensions/format");
                BufferedImage image = reader.read(0);
                if (binary) {
                    if (image.getRaster().getNumBands() != 1
                            || image.getSampleModel().getSampleSize(0) != 8)
                        throw new IOException("8-bit gray mask required");
                    for (int y = 0; y < h; y++)
                        for (int x = 0; x < w; x++) {
                            int v = image.getRaster().getSample(x, y, 0);
                            if (v != 0 && v != 255) throw new IOException("Non-binary mask");
                        }
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }
}
