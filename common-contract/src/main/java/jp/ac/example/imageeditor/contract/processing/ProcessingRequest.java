package jp.ac.example.imageeditor.contract.processing;

import java.awt.image.BufferedImage;

public record ProcessingRequest(
        BufferedImage source, ProcessingOperation operation, ProcessingParameters parameters) {}
