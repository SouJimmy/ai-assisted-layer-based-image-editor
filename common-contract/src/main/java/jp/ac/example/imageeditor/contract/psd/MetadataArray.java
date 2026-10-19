package jp.ac.example.imageeditor.contract.psd;

import java.util.List;

public record MetadataArray(List<MetadataValue> values) implements MetadataValue {
    public MetadataArray {
        values = List.copyOf(values);
    }
}
