package jp.ac.example.imageeditor.contract.psd;

import java.util.Map;

public record MetadataObject(Map<String, MetadataValue> values) implements MetadataValue {
    public MetadataObject {
        values = Map.copyOf(values);
    }
}
