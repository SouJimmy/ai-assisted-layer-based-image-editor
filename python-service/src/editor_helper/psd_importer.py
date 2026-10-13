"""Conservative PSD importer: independent raster layers and bounded group composition."""

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
        by_id = {node["id"]: (node, layer) for node, layer in layers}

        def ancestors(node):
            while node is not None:
                yield node
                node = by_id[node["parentId"]][0] if node["parentId"] else None

        # Only normal, unclipped groups isolate their children from the backdrop.
        # Pass-through and unknown modes must ascend to an enclosing isolation boundary.
        scopes = set()
        for node_id in unsafe:
            node, layer = by_id[node_id]
            start = (
                node
                if layer.is_group()
                else (by_id[node["parentId"]][0] if node["parentId"] else None)
            )
            scope = None
            if start is not None:
                for candidate in ancestors(start):
                    group = by_id[candidate["id"]][1]
                    if candidate["blendMode"] == "norm" and not group.clipping:
                        scope = candidate["id"]
                        break
            scopes.add(scope)
        if not layers:
            scopes.add(None)
        if None in scopes:
            scopes = {None}
        else:
            scopes = {
                scope
                for scope in scopes
                if not any(n["id"] in scopes for n in list(ancestors(by_id[scope][0]))[1:])
            }
        raster_bytes = 0

        def add_asset(node, image):
            nonlocal raster_bytes
            if image is None or image.size != (node["width"], node["height"]):
                raise ValueError("raster unavailable or dimensions mismatch")
            raster_bytes += image.width * image.height * 4
            if raster_bytes > MAX_RASTER_BYTES:
                raise ServiceError("RESOURCE_LIMIT", "展開画像が上限を超えています", 413)
            asset_id = str(uuid.uuid4())
            assets[asset_id] = image.convert("RGBA")
            node["assetId"] = asset_id

        for scope in sorted(scopes, key=lambda value: value or ""):
            if scope is None:
                replacement = {
                    "id": str(uuid.uuid4()),
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
                    "assetId": None,
                    "originalMetadata": {},
                    "importDisposition": "REPLACEMENT_RASTER",
                }
                for node in nodes:
                    node["importDisposition"] = "METADATA_ONLY"
                nodes.append(replacement)
                if raster_bytes + psd.width * psd.height * 4 > MAX_RASTER_BYTES:
                    raise ServiceError("RESOURCE_LIMIT", "展開画像が上限を超えています", 413)
                image = psd.composite(force=True)
            else:
                replacement, group = by_id[scope]

                # Ignore visibility outside this scope, but preserve hidden descendants.
                def visible_within_scope(layer):
                    while layer is not group:
                        if not layer.visible:
                            return False
                        layer = layer.parent
                    return True

                # psd-tools computes group bounds through ancestor visibility. Temporarily
                # expose only this ancestry; descendants keep their original visibility.
                ancestry = [by_id[n["id"]][1] for n in ancestors(replacement)]
                visibility = [layer.visible for layer in ancestry]
                try:
                    for layer in ancestry:
                        layer.visible = True
                    x, y, right, bottom = group.bbox
                    if right <= x or bottom <= y:
                        x, y, right, bottom = group.extract_bbox(group, include_invisible=True)
                    if right <= x or bottom <= y:
                        replacement["importDisposition"] = "EMPTY_METADATA"
                        continue
                    dimensions(right - x, bottom - y)
                    replacement.update(x=x, y=y, width=right - x, height=bottom - y)
                    if raster_bytes + (right - x) * (bottom - y) * 4 > MAX_RASTER_BYTES:
                        raise ServiceError("RESOURCE_LIMIT", "展開画像が上限を超えています", 413)
                    image = group.composite(
                        force=True,
                        viewport=(x, y, right, bottom),
                        layer_filter=visible_within_scope,
                    )
                finally:
                    for layer, was_visible in zip(ancestry, visibility):
                        layer.visible = was_visible
                for node, _ in layers:
                    if node is not replacement and any(n["id"] == scope for n in ancestors(node)):
                        node["importDisposition"] = "METADATA_ONLY"
                replacement.update(
                    opacity=1.0, blendMode="norm", importDisposition="REPLACEMENT_RASTER"
                )
            add_asset(replacement, image)
            warnings.append(
                {
                    "code": "FLATTENED",
                    "nodeId": replacement["id"],
                    "sourceKind": "document" if scope is None else replacement["kind"],
                    "unsupportedFeature": "group composition, mask, effects or non-raster data"
                    if layers
                    else "no individual layers",
                    "message": "文書全体を1枚に合成しました。元レイヤーの個別編集はできません。"
                    if scope is None
                    else "このグループを1枚に合成しました。グループ内の個別編集はできません。",
                    "disposition": "REPLACEMENT_RASTER",
                }
            )
        for node, layer in layers:
            if node["importDisposition"] in ("METADATA_ONLY", "REPLACEMENT_RASTER"):
                continue
            if layer.is_group() or layer.width == 0 or layer.height == 0:
                continue
            if raster_bytes + layer.width * layer.height * 4 > MAX_RASTER_BYTES:
                raise ServiceError("RESOURCE_LIMIT", "展開画像が上限を超えています", 413)
            try:
                image = layer.topil()
                if image is None:
                    raise ValueError("raster unavailable")
                add_asset(node, image)
                node["importDisposition"] = "RASTER"
            except ServiceError:
                raise
            except Exception:
                # The layer is a normal independent raster at this point. Composite
                # can recover masks/channel decoding without including siblings.
                image = layer.composite(force=True, layer_filter=lambda _: True)
                add_asset(node, image)
                node.update(opacity=1.0, importDisposition="REPLACEMENT_RASTER")
                warnings.append(
                    {
                        "code": "EXTRACTION_FAILED",
                        "nodeId": node["id"],
                        "sourceKind": node["kind"],
                        "unsupportedFeature": "raw pixel extraction",
                        "message": "画素抽出に失敗したため、このレイヤーを合成画像に置き換えました。",
                        "disposition": "REPLACEMENT_RASTER",
                    }
                )
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
