package jp.ac.example.imageeditor.contract.processing;

public sealed interface ProcessingParameters
        permits NoParameters,
                CropParameters,
                ResizeParameters,
                ColorAdjustParameters,
                BlurParameters,
                MosaicParameters {}
