package jp.ac.example.imageeditor.contract.processing;

import java.awt.image.BufferedImage;

public record ProcessingResult(
        BufferedImage image,
        PixelRect sourceChangedBounds,
        PixelRect outputChangedBounds,
        boolean geometryChanged,
        boolean deterministic,
        String algorithmVersion,
        ProcessingOperation operation,
        ProcessingParameters parameters) {}
