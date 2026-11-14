"""Build the default inventory-only library from the matching local client assets."""
from io import BytesIO
import json
from pathlib import Path
import tempfile

from PIL import Image, ImageChops

from item_catalog import ItemCatalog
from model_catalog import Unsupported, path_for, resource
from model_cache import PreparedCatalog, write_pack_changed
from prepare import entry, partition_files, table, allocate


def flat_model(catalog, name):
    own = catalog.json(path_for(name, "models"))
    parent = resource(own.get("parent", "builtin/generated"))
    inherited = {} if parent == "minecraft:builtin/generated" else flat_model(catalog, parent)
    return {**inherited, **own, "textures": {**inherited.get("textures", {}), **own.get("textures", {})}}


def icon(catalog, nodes):
    layers = []
    for node in nodes:
        if node["type"] != "minecraft:model":
            continue
        model = flat_model(catalog, node["model"])
        for name in sorted(model["textures"]):
            if not name.startswith("layer") or not name[5:].isdigit():
                continue
            index = int(name[5:])
            sprite = catalog.texture(model["textures"][name], model["textures"])
            path = path_for(sprite, "textures", ".png")
            image = Image.open(BytesIO(catalog.archive.read(path))).convert("RGBA")
            width, height = catalog.sprite_frame(sprite)
            metadata = (catalog.json(path + ".mcmeta").get("animation", {})
                        if path + ".mcmeta" in catalog.names else {})
            first = metadata.get("frames", [0])[0]
            first = first["index"] if isinstance(first, dict) else first
            x, y = first % (image.width // width) * width, first // (image.width // width) * height
            image = image.crop((x, y, x + width, y + height))
            tints = node.get("tints", [])
            if index < len(tints):
                tint = tints[index]
                value = tint.get("value", tint.get("default", 0xFFFFFF)) & 0xFFFFFF
                if tint.get("type") == "minecraft:grass":
                    rgb = tuple(round(c * 255) for c in catalog.tint(node, index, sprite))
                else:
                    rgb = tuple((value >> shift) & 255 for shift in (16, 8, 0))
                alpha = image.getchannel("A")
                image = ImageChops.multiply(image, Image.new("RGBA", image.size, (*rgb, 255)))
                image.putalpha(alpha)
            layers.append(image)
    if not layers:
        raise Unsupported("No generated inventory layers")
    width, height = max(i.width for i in layers), max(i.height for i in layers)
    result = Image.new("RGBA", (width, height))
    for layer in layers:
        result.alpha_composite(layer.resize((width, height), Image.Resampling.NEAREST))
    stream = BytesIO()
    result.save(stream, format="PNG")
    return stream.getvalue()


def build(output, cache):
    output, cache = Path(output), Path(cache)
    cache.mkdir(parents=True, exist_ok=True)
    registry = cache / "empty-registry.json"
    registry.write_text('{"version":1,"items":{}}')
    raw = ItemCatalog(registry=registry)
    prepared = PreparedCatalog(raw, cache / "geometry")
    catalog, models, images, errors = {}, {}, {}, {}
    prefix = "assets/minecraft/items/"
    try:
        for path in sorted(raw.names):
            if not (path.startswith(prefix) and path.endswith(".json")):
                continue
            name = "minecraft:" + path[len(prefix):-5]
            try:
                faces, source = raw.block(name)
                if source.get("source") == "inventory-2d":
                    relative = "images/" + name.split(":", 1)[1] + ".png"
                    raw._strict_dynamic = False
                    images[relative] = icon(raw, raw.item_node(raw.json(path)["model"]))
                    catalog[name] = {"kind": "image", "path": relative,
                                     "source": "minecraft:inventory/" + name.split(":", 1)[1]}
                    result = Image.open(BytesIO(images[relative])).convert("RGBA")
                    for node in raw.item_node(raw.json(path)["model"]):
                        if node["type"] != "minecraft:model":
                            continue
                        model = flat_model(raw, node["model"])
                        for value in model["textures"].values():
                            sprite = raw.texture(value, model["textures"])
                            asset = path_for(sprite, "textures", ".png")
                            if asset + ".mcmeta" in raw.names:
                                continue
                            original = Image.open(BytesIO(raw.archive.read(asset))).convert("RGBA")
                            if original.size == result.size and original.tobytes() == result.tobytes():
                                catalog[name]["sprite"] = sprite
                                break
                    raw._strict_dynamic = True
                elif faces:
                    models[name] = entry(prepared, name, "vanilla")
                    catalog[name] = {"kind": "model", "source": "model:" + name}
                else:
                    catalog[name] = {"kind": "empty"}
            except (Unsupported, KeyError, ValueError) as error:
                errors[name] = str(error)
                catalog[name] = {"kind": "unsupported", "reason": str(error)}
        if errors:
            raise Unsupported("Inventory library contains unsupported entries: " + json.dumps(errors))
        files = partition_files(models, "vanilla", raw.archive.vanilla)
        slots, faces = allocate(models, [], 0)
        files["assets/dialogmenu_vanilla/shaders/include/models.glsl"] = table(slots, "vanilla")
        files["pack.mcmeta"] = json.dumps({"pack": {"description": "DialogMenu vanilla inventory",
                                                   "pack_format": 97}}).encode()
        model_file = cache / "resourcepack.zip"
        write_pack_changed(model_file, files)
        bounds = {
            name: [min(p["geometry"][i] for p in value["parts"] for i in (0, 4, 8, 12)),
                   min(p["geometry"][i] for p in value["parts"] for i in (1, 5, 9, 13)),
                   max(p["geometry"][i] for p in value["parts"] for i in (0, 4, 8, 12)),
                   max(p["geometry"][i] for p in value["parts"] for i in (1, 5, 9, 13))]
            for name, value in models.items()}
        state = {"entries": {name: {"parts": value["parts"], "report": value["report"]}
                             for name, value in models.items()}}
        preview = {}
        for value in models.values():
            for part in value["parts"]:
                path = path_for(part["sprite"], "textures", ".png")
                for asset in (path, path + ".mcmeta"):
                    if asset in raw.names:
                        preview[asset] = raw.archive.read(asset)
        library = {**images, **preview, **{k: v for k, v in files.items() if k.startswith("assets/")},
                   **{path: bytes.fromhex(content) for value in models.values()
                      for path, content in value["assets"].items()},
                   "catalog.json": json.dumps(catalog, separators=(",", ":"), sort_keys=True).encode(),
                   "models.json": json.dumps({"models": faces, "bounds": bounds}, separators=(",", ":"), sort_keys=True).encode(),
                   "state.json": json.dumps(state, separators=(",", ":"), sort_keys=True).encode(),
                   "resourcepack.zip": model_file.read_bytes()}
        write_pack_changed(output, library)
        report = {"total": len(catalog), "models": len(models), "images": len(images),
                  "unsupported": errors, "bytes": output.stat().st_size,
                  "scope": "Default inventory appearances; no GUI/entity/particle texture catalog",
                  "dynamic_items": "Default state/first declared frame, not live item-data animation"}
        (cache / "summary.json").write_text(json.dumps(report, indent=2))
        return report
    finally:
        raw.archive.close()


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(build(args.output, args.cache), indent=2))
