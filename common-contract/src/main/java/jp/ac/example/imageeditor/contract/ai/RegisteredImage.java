package jp.ac.example.imageeditor.contract.ai;

import java.util.Objects;

public record RegisteredImage(
        String imageId, String sourceRevision, int width, int height, String serviceInstanceId) {
    public RegisteredImage {
        Objects.requireNonNull(imageId);
        Objects.requireNonNull(sourceRevision);
        Objects.requireNonNull(serviceInstanceId);
    }
}
