package jp.ac.example.imageeditor.contract.psd;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public sealed interface MetadataValue
        permits MetadataNull,
                MetadataBoolean,
                MetadataString,
                MetadataNumber,
                MetadataArray,
                MetadataObject {
    static MetadataValue from(Object value) {
        if (value == null) return MetadataNull.INSTANCE;
        if (value instanceof Boolean b) return new MetadataBoolean(b);
        if (value instanceof String s) return new MetadataString(s);
        if (value instanceof Number n)
            return new MetadataNumber(new java.math.BigDecimal(n.toString()));
        if (value instanceof List<?> list)
            return new MetadataArray(list.stream().map(MetadataValue::from).toList());
        if (value instanceof Map<?, ?> map) {
            Map<String, MetadataValue> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put((String) k, from(v)));
            return new MetadataObject(result);
        }
        throw new IllegalArgumentException("Unsupported metadata value");
    }
}
