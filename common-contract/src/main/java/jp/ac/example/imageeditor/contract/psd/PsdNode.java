package jp.ac.example.imageeditor.contract.psd;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record PsdNode(
        String id,
        Optional<String> parentId,
        int siblingIndex,
        String name,
        String kind,
        int x,
        int y,
        int width,
        int height,
        boolean selfVisible,
        boolean effectiveVisible,
        double opacity,
        double effectiveOpacity,
        String blendMode,
        Optional<String> assetId,
        ImportDisposition importDisposition,
        Map<String, MetadataValue> originalMetadata) {
    public PsdNode {
        Objects.requireNonNull(parentId);
        Objects.requireNonNull(assetId);
        originalMetadata = Map.copyOf(originalMetadata);
    }
}
