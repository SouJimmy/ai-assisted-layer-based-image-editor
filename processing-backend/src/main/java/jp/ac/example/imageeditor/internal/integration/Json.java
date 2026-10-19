package jp.ac.example.imageeditor.internal.integration;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;

import java.util.*;

/** Bounded wire codec; domain records are mapped explicitly rather than serialized as API DTOs. */
public final class Json {
    private static final ObjectMapper MAPPER =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .streamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxNestingDepth(64)
                                                    .maxStringLength(64 * 1024 * 1024)
                                                    .build())
                                    .build())
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Json() {}

    public static Object parse(String source) {
        try {
            Object value = MAPPER.readValue(source, Object.class);
            validate(value, 0);
            return value;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON", e);
        }
    }

    public static String write(Object value) {
        validate(value, 0);
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON value", e);
        }
    }

    private static void validate(Object value, int depth) {
        if (depth > 64) throw new IllegalArgumentException("JSON nesting limit");
        if (value == null || value instanceof String || value instanceof Boolean) return;
        if (value instanceof Number n) {
            if (!Double.isFinite(n.doubleValue()))
                throw new IllegalArgumentException("Non-finite JSON number");
            return;
        }
        if (value instanceof Map<?, ?> m) {
            m.forEach(
                    (k, v) -> {
                        if (!(k instanceof String)) throw new IllegalArgumentException("JSON key");
                        validate(v, depth + 1);
                    });
            return;
        }
        if (value instanceof List<?> l) {
            l.forEach(v -> validate(v, depth + 1));
            return;
        }
        throw new IllegalArgumentException("Unsupported JSON type");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>))
            throw new IllegalArgumentException("JSON object expected");
        return (Map<String, Object>) value;
    }

    public static String string(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof String s))
            throw new IllegalArgumentException("String expected: " + key);
        return s;
    }

    public static int integer(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof Number n)
                || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != n.intValue())
            throw new IllegalArgumentException("Integer expected: " + key);
        return n.intValue();
    }

    public static List<?> list(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof List<?> l))
            throw new IllegalArgumentException("List expected: " + key);
        return l;
    }
}
