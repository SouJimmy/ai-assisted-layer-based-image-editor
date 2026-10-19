package jp.ac.example.imageeditor.contract.ai;

import jp.ac.example.imageeditor.contract.common.AsyncOperation;

import java.awt.image.BufferedImage;

public interface AiSegmentationService {
    AsyncOperation<RegisteredImage> registerImage(BufferedImage snapshot, String sourceRevision);

    AsyncOperation<AiMaskResult> segment(AiRequest request);

    AsyncOperation<Void> releaseImage(RegisteredImage image);

    void invalidateRequests(RegisteredImage image);
}
