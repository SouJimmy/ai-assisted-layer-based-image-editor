package jp.ac.example.imageeditor.contract.psd;

import java.util.Objects;

public record MetadataNumber(java.math.BigDecimal value) implements MetadataValue {
    public MetadataNumber {
        Objects.requireNonNull(value);
    }
}
