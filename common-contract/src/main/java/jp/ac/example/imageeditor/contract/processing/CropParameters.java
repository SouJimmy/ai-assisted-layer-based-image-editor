package jp.ac.example.imageeditor.contract.processing;

public record CropParameters(PixelRect rect) implements ProcessingParameters {}
