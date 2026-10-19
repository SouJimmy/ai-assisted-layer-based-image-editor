package jp.ac.example.imageeditor.contract.psd;

import java.util.Objects;
import java.util.Optional;

public record ImportWarning(
        WarningCode code,
        String rawCode,
        Optional<String> nodeId,
        String message,
        ImportDisposition disposition,
        String sourceKind,
        String unsupportedFeature) {
    public ImportWarning {
        Objects.requireNonNull(nodeId);
    }
}
