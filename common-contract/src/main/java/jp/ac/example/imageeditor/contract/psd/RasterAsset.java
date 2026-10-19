package jp.ac.example.imageeditor.contract.psd;

import java.awt.image.BufferedImage;

public record RasterAsset(String assetId, BufferedImage image) {}
