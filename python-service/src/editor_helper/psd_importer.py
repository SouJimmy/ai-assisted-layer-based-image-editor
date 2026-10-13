"""Conservative PSD importer: independent plain raster layers, or explicit document fallback."""

import json
import uuid
import zipfile
from io import BytesIO

from .errors import ServiceError
from .image_cache import dimensions

MAX_RASTER_BYTES = 256 * 1024 * 1024


def import_psd(data):
    if len(data) > 128 * 1024 * 1024:
        raise ServiceError("RESOURCE_LIMIT", "PSDファイルが上限を超えています", 413)
    if data[:6] != b"8BPS\x00\x01":
        raise ServiceError("UNSUPPORTED_FORMAT", "Version 1のPSDファイルを指定してください", 415)
    try:
        from psd_tools import PSDImage

        psd = PSDImage.open(BytesIO(data))
        dimensions(psd.width, psd.height)
        nodes, assets, warnings, layers = [], {}, [], []
        unsafe = []

        def walk(parent, parent_id=None, visible=True, opacity=1.0):
            if len(nodes) > 500:
                raise ServiceError("RESOURCE_LIMIT", "レイヤー数が上限を超えています", 413)
            # psd-tools iteration order is bottom-to-top; preserve and expose explicitly.
            for index, layer in enumerate(parent):
                if len(nodes) >= 500:
                    raise ServiceError("RESOURCE_LIMIT", "レイヤー数が上限を超えています", 413)
                if layer.width and layer.height:
                    dimensions(layer.width, layer.height)
                node_id = str(uuid.uuid4())
                own_opacity = layer.opacity / 255
                mode = layer.blend_mode.value.decode("ascii", errors="replace")
                original = {
                    "kind": layer.kind,
                    "x": layer.left,
                    "y": layer.top,
                    "opacity": own_opacity,
                    "selfVisible": layer.visible,
                    "blendMode": mode,
                }
                node = {
                    "id": node_id,
                    "parentId": parent_id,
                    "siblingIndex": index,
                    "name": layer.name,
                    "kind": layer.kind,
                    "x": layer.left,
                    "y": layer.top,
                    "width": layer.width,
                    "height": layer.height,
                    "selfVisible": layer.visible,
                    "effectiveVisible": visible and layer.visible,
                    "opacity": own_opacity,
                    "effectiveOpacity": opacity * own_opacity,
                    "blendMode": mode,
                    "assetId": None,
                    "importDisposition": "GROUP_METADATA" if layer.is_group() else "EMPTY_METADATA",
                    "originalMetadata": original,
                }
                nodes.append(node)
                layers.append((node, layer))
                has_effects = bool(layer.effects)
                if layer.has_mask() or layer.has_vector_mask() or has_effects or layer.clipping:
                    unsafe.append(node_id)
                if layer.is_group():
                    if mode != "pass" or layer.opacity != 255:
                        unsafe.append(node_id)
                    walk(layer, node_id, node["effectiveVisible"], opacity * own_opacity)
                elif layer.kind != "pixel" or mode != "norm":
                    unsafe.append(node_id)

        walk(psd)
        raster_bytes = 0
        if unsafe or not layers:
            # Whole-document fallback includes backdrop dependencies. Do not pretend children remain editable.
            image = psd.composite(force=True)
            if image is None:
                raise ValueError("composite unavailable")
            replacement_id = str(uuid.uuid4())
            asset_id = str(uuid.uuid4())
            assets[asset_id] = image.convert("RGBA")
            for node in nodes:
                node["importDisposition"] = "METADATA_ONLY"
            nodes.append(
                {
                    "id": replacement_id,
                    "parentId": None,
                    "siblingIndex": len(psd),
                    "name": "PSD composite",
                    "kind": "composite",
                    "x": 0,
                    "y": 0,
                    "width": psd.width,
                    "height": psd.height,
                    "selfVisible": True,
                    "effectiveVisible": True,
                    "opacity": 1.0,
                    "effectiveOpacity": 1.0,
                    "blendMode": "norm",
                    "assetId": asset_id,
                    "importDisposition": "REPLACEMENT_RASTER",
                    "originalMetadata": {},
                }
            )
            if not layers:
                warnings.append(
                    {
                        "code": "FLATTENED",
                        "nodeId": replacement_id,
                        "sourceKind": "document",
                        "unsupportedFeature": "no individual layers",
                        "message": "個別レイヤーのないPSDを合成画像として取り込みました。",
                        "disposition": "REPLACEMENT_RASTER",
                    }
                )
            for node_id in dict.fromkeys(unsafe):
                node = next(n for n in nodes if n["id"] == node_id)
                warnings.append(
                    {
                        "code": "FLATTENED",
                        "nodeId": node_id,
                        "sourceKind": node["kind"],
                        "unsupportedFeature": "group composition, mask, effects or non-raster data",
                        "message": "未対応要素の影響を含めて文書全体を1枚に合成しました。元レイヤーの個別編集はできません。",
                        "disposition": "REPLACEMENT_RASTER",
                    }
                )
        else:
            for node, layer in layers:
                if layer.is_group() or layer.width == 0 or layer.height == 0:
                    continue
                dimensions(layer.width, layer.height)
                raster_bytes += layer.width * layer.height * 4
                if raster_bytes > MAX_RASTER_BYTES:
                    raise ServiceError("RESOURCE_LIMIT", "展開画像が上限を超えています", 413)
                image = layer.topil()
                if image is None:
                    raise ValueError("raster unavailable")
                image = image.convert("RGBA")
                if image.size != (layer.width, layer.height):
                    raise ValueError("raster dimensions mismatch")
                asset_id = str(uuid.uuid4())
                assets[asset_id] = image
                node.update(assetId=asset_id, importDisposition="RASTER")
        if sum(im.width * im.height * 4 for im in assets.values()) > MAX_RASTER_BYTES:
            raise ServiceError("RESOURCE_LIMIT", "展開画像が上限を超えています", 413)
        manifest = {
            "schemaVersion": 1,
            "canvasWidth": psd.width,
            "canvasHeight": psd.height,
            "nodes": nodes,
            "assets": [],
            "warnings": warnings,
        }
        out = BytesIO()
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
            for asset_id, image in assets.items():
                buffer = BytesIO()
                image.save(buffer, format="PNG")
                path = f"assets/{asset_id}.png"
                archive.writestr(path, buffer.getvalue())
                manifest["assets"].append(
                    {
                        "assetId": asset_id,
                        "path": path,
                        "width": image.width,
                        "height": image.height,
                    }
                )
            archive.writestr(
                "manifest.json", json.dumps(manifest, ensure_ascii=False, allow_nan=False)
            )
        if out.tell() > MAX_RASTER_BYTES:
            raise ServiceError("RESOURCE_LIMIT", "ZIP応答が上限を超えています", 413)
        return out.getvalue()
    except ServiceError:
        raise
    except Exception as exc:
        raise ServiceError(
            "PSD_PARSE_FAILED", "PSDを読み込めません。別のファイルを試してください", 422
        ) from exc
