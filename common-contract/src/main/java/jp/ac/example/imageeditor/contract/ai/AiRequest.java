package jp.ac.example.imageeditor.contract.ai;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AiRequest(RegisteredImage image, UUID requestId, List<PromptPoint> points) {
    public AiRequest {
        Objects.requireNonNull(image);
        Objects.requireNonNull(requestId);
        points = List.copyOf(points);
    }
}
