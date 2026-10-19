package jp.ac.example.imageeditor.contract.ai;

import java.util.List;
import java.util.UUID;

public record AiMaskResult(
        RegisteredImage image,
        UUID requestId,
        List<MaskCandidate> candidates,
        List<PromptPoint> sourcePoints,
        DeviceKind deviceUsed) {
    public AiMaskResult {
        candidates = List.copyOf(candidates);
        sourcePoints = List.copyOf(sourcePoints);
    }
}
