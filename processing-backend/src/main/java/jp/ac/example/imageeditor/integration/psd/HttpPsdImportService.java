package jp.ac.example.imageeditor.integration.psd;

import jp.ac.example.imageeditor.contract.common.*;
import jp.ac.example.imageeditor.contract.psd.*;
import jp.ac.example.imageeditor.integration.shared.*;
import jp.ac.example.imageeditor.internal.integration.*;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

public final class HttpPsdImportService implements PsdImportService {
    private final Supplier<HelperClient> clients;
    private final Supplier<String> instances;

    public HttpPsdImportService(Supplier<HelperClient> clients, Supplier<String> instances) {
        this.clients = clients;
        this.instances = instances;
    }

    @Override
    public AsyncOperation<PsdImportResult> importPsd(Path file) {
        OperationPromise<PsdImportResult> result =
                new OperationPromise<>(
                        () -> new PsdImportException(ErrorCode.CANCELLED, "PSD取り込みを取り消しました", null));
        HelperClient client = clients.get();
        String instance = instances.get();
        if (client == null || instance == null) {
            result.fail(
                    new PsdImportException(ErrorCode.SERVICE_UNAVAILABLE, "サービスが起動していません", null));
            return result;
        }
        if (file == null) {
            result.fail(new PsdImportException(ErrorCode.INVALID_ARGUMENT, "ファイルがありません", null));
            return result;
        }
        var future = client.importPsd(file);
        result.onCancel(() -> future.cancel(true));
        future.whenComplete(
                (value, error) -> {
                    if (error != null) {
                        result.fail(Failures.psd(error));
                        return;
                    }
                    if (!instance.equals(instances.get())) {
                        result.cancel();
                        return;
                    }
                    try {
                        result.succeed(convert(value));
                    } catch (RuntimeException e) {
                        result.fail(
                                new PsdImportException(
                                        ErrorCode.INVALID_RESPONSE, "PSD応答が不正です", e));
                    }
                });
        return result;
    }

    public static PsdImportResult convert(HelperClient.PsdImport value) {
        if (value.nodes().size() > 500) throw new IllegalArgumentException("Node limit");
        List<PsdNode> nodes =
                value.nodes().stream()
                        .map(
                                n -> {
                                    Map<String, MetadataValue> metadata = new LinkedHashMap<>();
                                    Json.object(n.get("originalMetadata"))
                                            .forEach(
                                                    (k, v) ->
                                                            metadata.put(k, MetadataValue.from(v)));
                                    return new PsdNode(
                                            Json.string(n, "id"),
                                            optional(n, "parentId"),
                                            Json.integer(n, "siblingIndex"),
                                            Json.string(n, "name"),
                                            Json.string(n, "kind"),
                                            Json.integer(n, "x"),
                                            Json.integer(n, "y"),
                                            Json.integer(n, "width"),
                                            Json.integer(n, "height"),
                                            (Boolean) n.get("selfVisible"),
                                            (Boolean) n.get("effectiveVisible"),
                                            ((Number) n.get("opacity")).doubleValue(),
                                            ((Number) n.get("effectiveOpacity")).doubleValue(),
                                            Json.string(n, "blendMode"),
                                            optional(n, "assetId"),
                                            ImportDisposition.valueOf(
                                                    Json.string(n, "importDisposition")),
                                            metadata);
                                })
                        .toList();
        Map<String, RasterAsset> assets = new LinkedHashMap<>();
        value.assets().forEach((k, v) -> assets.put(k, new RasterAsset(k, v)));
        List<ImportWarning> warnings =
                value.warnings().stream()
                        .map(
                                n -> {
                                    String raw = Json.string(n, "code");
                                    WarningCode code;
                                    try {
                                        code = WarningCode.valueOf(raw);
                                    } catch (IllegalArgumentException e) {
                                        code = WarningCode.UNKNOWN;
                                    }
                                    return new ImportWarning(
                                            code,
                                            raw,
                                            optional(n, "nodeId"),
                                            Json.string(n, "message"),
                                            ImportDisposition.valueOf(
                                                    Json.string(n, "disposition")),
                                            Json.string(n, "sourceKind"),
                                            Json.string(n, "unsupportedFeature"));
                                })
                        .toList();
        return new PsdImportResult(
                1, value.canvasWidth(), value.canvasHeight(), nodes, assets, warnings);
    }

    private static Optional<String> optional(Map<String, Object> n, String key) {
        Object value = n.get(key);
        return value == null ? Optional.empty() : Optional.of((String) value);
    }
}
