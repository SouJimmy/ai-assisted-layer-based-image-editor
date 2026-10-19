package jp.ac.example.imageeditor.contract.psd;

import java.util.Objects;

public record MetadataString(String value) implements MetadataValue {
    public MetadataString {
        Objects.requireNonNull(value);
    }
}
