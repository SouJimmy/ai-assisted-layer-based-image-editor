package editor.integration;

import editor.processing.ImageProcessor;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Validates a PSD result ZIP in memory without extracting untrusted paths. */
final class PsdArchiveReader {
    private PsdArchiveReader() {}

    static HelperClient.PsdImport read(byte[] bytes) {
        try {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            long total = 0;
            try (ZipInputStream zip =
                    new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entries.size() >= 502
                            || entry.isDirectory()
                            || name.contains("\\")
                            || name.startsWith("/")
                            || Arrays.asList(name.split("/")).contains("..")
                            || entries.containsKey(name)
                            || !(name.equals("manifest.json")
                                    || name.matches("assets/[a-zA-Z0-9-]+\\.png")))
                        throw new IOException("Invalid ZIP entry");
                    int max = name.equals("manifest.json") ? 4 * 1024 * 1024 : 128 * 1024 * 1024;
                    byte[] data = zip.readNBytes(max + 1);
                    if (data.length > max) throw new IOException("Entry too large");
                    total += data.length;
                    if (total > 256L * 1024 * 1024) throw new IOException("ZIP expansion limit");
                    entries.put(name, data);
                }
            }
            Map<String, Object> manifest =
                    parseObject(Objects.requireNonNull(entries.get("manifest.json")));
            if (Json.integer(manifest, "schemaVersion") != 1)
                throw new IOException("Unknown schema version");
            int width = Json.integer(manifest, "canvasWidth"),
                    height = Json.integer(manifest, "canvasHeight");
            ImageProcessor.checkDimensions(width, height);
            Map<String, BufferedImage> assets = new LinkedHashMap<>();
            Set<String> paths = new HashSet<>();
            long pixels = 0;
            for (Object item : Json.list(manifest, "assets")) {
                Map<String, Object> asset = Json.object(item);
                String id = Json.string(asset, "assetId"), path = Json.string(asset, "path");
                if (assets.containsKey(id)
                        || !path.equals("assets/" + id + ".png")
                        || !paths.add(path)) throw new IOException("Duplicate/invalid asset");
                int w = Json.integer(asset, "width"), h = Json.integer(asset, "height");
                pixels += (long) w * h * 4;
                if (pixels > 256L * 1024 * 1024) throw new IOException("Raster budget");
                BufferedImage decoded =
                        PngDecoder.read(Objects.requireNonNull(entries.get(path)), w, h, false);
                BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
                var graphics = image.createGraphics();
                try {
                    graphics.setComposite(java.awt.AlphaComposite.Src);
                    graphics.drawImage(decoded, 0, 0, null);
                } finally {
                    graphics.dispose();
                }
                assets.put(id, image);
            }
            if (entries.size() != assets.size() + 1)
                throw new IOException("Unreferenced archive entry");
            List<Map<String, Object>> nodes = new ArrayList<>(), warnings = new ArrayList<>();
            Map<String, Map<String, Object>> byId = new HashMap<>();
            for (Object item : Json.list(manifest, "nodes")) {
                Map<String, Object> node = Json.object(item);
                String id = Json.string(node, "id");
                if (byId.put(id, node) != null || byId.size() > 501)
                    throw new IOException("Duplicate node");
                Json.string(node, "name");
                Json.string(node, "kind");
                Json.integer(node, "x");
                Json.integer(node, "y");
                if (Json.integer(node, "width") < 0
                        || Json.integer(node, "height") < 0
                        || Json.integer(node, "siblingIndex") < 0)
                    throw new IOException("Invalid bounds");
                for (String key : List.of("opacity", "effectiveOpacity")) {
                    if (!(node.get(key) instanceof Number n)
                            || !Double.isFinite(n.doubleValue())
                            || n.doubleValue() < 0
                            || n.doubleValue() > 1) throw new IOException("Opacity");
                }
                if (!(node.get("selfVisible") instanceof Boolean)
                        || !(node.get("effectiveVisible") instanceof Boolean))
                    throw new IOException("Visibility");
                String disposition = Json.string(node, "importDisposition");
                if (!Set.of(
                                "RASTER",
                                "GROUP_METADATA",
                                "REPLACEMENT_RASTER",
                                "METADATA_ONLY",
                                "EMPTY_METADATA")
                        .contains(disposition)) throw new IOException("Disposition");
                Object assetId = node.get("assetId");
                if (disposition.equals("RASTER") || disposition.equals("REPLACEMENT_RASTER")) {
                    BufferedImage image = assets.get(assetId);
                    if (image == null
                            || image.getWidth() != Json.integer(node, "width")
                            || image.getHeight() != Json.integer(node, "height"))
                        throw new IOException("Node image mismatch");
                } else if (assetId != null)
                    throw new IOException("Metadata node has drawable asset");
                nodes.add(freezeMap(node));
            }
            Set<String> siblingKeys = new HashSet<>();
            for (var node : byId.values()) {
                Object parent = node.get("parentId");
                Set<Object> seen = new HashSet<>();
                seen.add(node.get("id"));
                if (!siblingKeys.add(
                        String.valueOf(parent) + ":" + Json.integer(node, "siblingIndex")))
                    throw new IOException("Duplicate order");
                while (parent != null) {
                    if (!seen.add(parent) || !byId.containsKey(parent))
                        throw new IOException("Invalid parent tree");
                    parent = byId.get(parent).get("parentId");
                }
            }
            for (Object item : Json.list(manifest, "warnings"))
                warnings.add(freezeMap(Json.object(item)));
            return new HelperClient.PsdImport(width, height, nodes, assets, warnings);
        } catch (Exception e) {
            throw invalidResponse(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put((String) k, freeze(v)));
            return Collections.unmodifiableMap(out);
        }
        if (value instanceof List<?> list)
            return list.stream().map(PsdArchiveReader::freeze).toList();
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> freezeMap(Map<String, Object> value) {
        return (Map<String, Object>) freeze(value);
    }

    private static Map<String, Object> parseObject(byte[] bytes) {
        return Json.object(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
    }

    private static ServiceFailure invalidResponse(Throwable cause) {
        return new ServiceFailure("INVALID_RESPONSE", "サービスの応答が不正です", cause);
    }
}
