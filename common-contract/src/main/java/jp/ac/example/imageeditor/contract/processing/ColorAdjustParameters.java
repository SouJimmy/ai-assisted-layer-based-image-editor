package jp.ac.example.imageeditor.contract.processing;

public record ColorAdjustParameters(int brightness, double contrast, double saturation)
        implements ProcessingParameters {}
