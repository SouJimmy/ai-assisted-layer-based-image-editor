package jp.ac.example.imageeditor.contract.psd;

import java.util.List;
import java.util.Map;

public record PsdImportResult(
        int schemaVersion,
        int canvasWidth,
        int canvasHeight,
        List<PsdNode> nodes,
        Map<String, RasterAsset> rasterAssets,
        List<ImportWarning> warnings) {
    public PsdImportResult {
        nodes = List.copyOf(nodes);
        rasterAssets = Map.copyOf(rasterAssets);
        warnings = List.copyOf(warnings);
    }
}
