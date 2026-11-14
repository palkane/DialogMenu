"""Render actual bitmap components with exported shaders and vanilla includes, without a client."""
import argparse
from io import BytesIO
import json
import math
from pathlib import Path
import re
import struct
from zipfile import ZipFile

import moderngl
import numpy as np
from PIL import Image


def shader(pack, client, name):
    seen = set()
    metadata = json.loads(client.read("version.json"))["pack_version"]
    version = (metadata.get("resource", metadata.get("resource_major")), metadata.get("resource_minor", 0))
    roots = [pack]
    for entry in json.loads((pack / "pack.mcmeta").read_text())["overlays"]["entries"]:
        low, high = entry["min_format"], entry["max_format"]
        low = tuple(low) if isinstance(low, list) else (low, 0)
        high = tuple(high) if isinstance(high, list) else (high, 0)
        if low <= version <= high:
            roots.append(pack / entry["directory"])

    def load(resource):
        namespace, path = resource.split(":", 1)
        relative = f"assets/{namespace}/shaders/{path}"
        for root in reversed(roots):
            file = root / relative
            if file.exists():
                return file.read_text()
        return client.read(relative).decode()

    def expand(text):
        def include(match):
            resource = match[1]
            if ":" not in resource:
                resource = "minecraft:" + resource
            namespace, path = resource.split(":", 1)
            resource = namespace + ":include/" + path
            if resource in seen:
                return ""
            seen.add(resource)
            return expand(re.sub(r"(?m)^#version[^\n]*\n", "", load(resource)))
        return re.sub(r"#(?:moj_import|include)\s+<([^>]+)>", include, text)

    return expand(load("minecraft:core/" + name))


def text_parts(node, inherited=None):
    style = {**(inherited or {}), **{k: v for k, v in node.items() if k not in ("text", "extra")}}
    if node.get("text"):
        yield node["text"], style
    for child in node.get("extra", []):
        yield from text_parts(child, style)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", type=Path, required=True)
    parser.add_argument("--client", type=Path, required=True)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    components = json.loads((args.pack / "components.json").read_text())
    context = moderngl.create_standalone_context(require=330, backend="egl")
    size = 320
    framebuffer = context.simple_framebuffer((size, size), components=4)
    framebuffer.use()
    context.disable(moderngl.BLEND | moderngl.DEPTH_TEST | moderngl.CULL_FACE)
    with ZipFile(args.client) as client:
        vertex = shader(args.pack, client, "rendertype_text.vsh")
        fragment = shader(args.pack, client, "rendertype_text.fsh")
    (args.out / "vertex.glsl").write_text(vertex)
    (args.out / "fragment.glsl").write_text(fragment)
    program = context.program(vertex_shader=vertex, fragment_shader=fragment)
    projection = np.array([[2 / size, 0, 0, -1], [0, -2 / size, 0, 1],
                           [0, 0, 1, 0], [0, 0, 0, 1]], dtype="f4")
    blocks = dict(re.findall(r"layout\s*\(std140\)\s*uniform\s+(\w+)\s*\{([^}]+)\}", vertex + fragment))
    buffers = []

    def uniforms(ticks):
        for buffer in buffers:
            buffer.release()
        buffers.clear()
        for binding, (name, body) in enumerate(blocks.items()):
            if name not in program:
                continue
            data = bytearray()
            for kind, field in re.findall(r"\b(mat4|[iu]?vec[234]|float|int)\s+(\w+)\s*;", body):
                align = 4 if kind in ("float", "int") else 8 if kind.endswith("2") else 16
                data.extend(b"\0" * (-len(data) % align))
                if kind == "mat4":
                    matrix = projection if field == "ProjMat" else np.eye(4, dtype="f4")
                    data.extend(matrix.T.tobytes())
                elif "vec" in kind:
                    count = int(kind[-1])
                    values = [1] * count if field == "ColorModulator" else [0] * count
                    data.extend(struct.pack("<" + ("i" if kind.startswith("i") else "f") * count, *values))
                else:
                    value = ticks / 24000 if field == "GameTime" else (
                        20000 if "End" in field else 10000 if "Start" in field else 0)
                    data.extend(struct.pack("<i" if kind == "int" else "<f", value))
            data.extend(b"\0" * (-len(data) % 16))
            assert len(data) == program[name].size, (name, len(data), program[name].size)
            buffer = context.buffer(data)
            buffer.bind_to_uniform_block(binding)
            program[name].binding = binding
            buffers.append(buffer)
    program["Sampler0"].value = 0
    if "Sampler2" in program:
        program["Sampler2"].value = 2
    light = context.texture((16, 16), 4, b"\xff" * (16 * 16 * 4))
    light.use(2)
    fonts, glyphs, textures = {}, {}, {}

    def font(name):
        if name not in fonts:
            namespace, path = name.split(":")
            fonts[name] = json.loads((args.pack / f"assets/{namespace}/font/{path}.json").read_text())["providers"]
        return fonts[name]

    def glyph(name, char):
        key = name, char
        if key not in glyphs:
            for provider in font(name):
                if provider["type"] != "bitmap":
                    continue
                for row, line in enumerate(provider["chars"]):
                    if char not in line:
                        continue
                    column = line.index(char)
                    namespace, path = provider["file"].split(":")
                    source = Image.open(args.pack / f"assets/{namespace}/textures/{path}").convert("RGBA")
                    w, h = source.width // len(line), source.height // len(provider["chars"])
                    cell = source.crop((column * w, row * h, (column + 1) * w, (row + 1) * h))
                    # Simulate font stitching independently of the provider atlas positions.
                    x, y = min(13, 256 - w), min(23, 256 - h)
                    atlas = Image.new("RGBA", (256, 256))
                    atlas.paste(cell, (x, y))
                    texture = context.texture(atlas.size, 4, atlas.tobytes())
                    texture.filter = moderngl.NEAREST, moderngl.NEAREST
                    texture.repeat_x = texture.repeat_y = False
                    pixels = np.array(cell)
                    occupied = np.nonzero(pixels[:, :, 3])[1]
                    advance = math.floor((int(occupied.max()) + 1 if len(occupied) else 0) / h + 0.5) + 1
                    glyphs[key] = x, y, w, h, advance
                    textures[key] = texture
                    return glyphs[key]
            raise AssertionError(("Missing bitmap glyph", key))
        return glyphs[key]

    def draw(component, ticks=0, image=False, broken=False):
        uniforms(ticks)
        framebuffer.clear()
        for row in range(-12, 30):
            cursor = 0
            for text, style in text_parts(component):
                name = style["font"]
                for char in text:
                    if name == "dialogmenu:space":
                        cursor += next(p["advances"][char] for p in font(name) if char in p.get("advances", {}))
                        continue
                    x, y, w, h, advance = glyph(name, char)
                    payload = ((style["shadow_color"] & 0xFFFFFFFF) & ~2047) | ((-row * 9) & 2047)
                    if broken:
                        payload &= ~(7 << 29)
                    color = [(payload >> shift & 255) / 255 for shift in (16, 8, 0, 24)]
                    anchor_x, anchor_y = (40, 40) if image else (size / 2, size / 2)
                    values = []
                    for ex, ey in ((0, 0), (0, 1), (1, 1), (1, 0)):
                        values.append((anchor_x + cursor + 1 + ex * w / h, anchor_y + row * 9 + 8 + ey,
                                       0, *color, (x + ex * w + (0.01 if ex == 0 else -0.01)) / 256,
                                       (y + ey * h + (0.01 if ey == 0 else -0.01)) / 256))
                    vertex_buffer = context.buffer(np.array(values, dtype="f4").tobytes())
                    content = [(vertex_buffer, "3f 4f 2f", "Position", "Color", "UV0")]
                    lighting = None
                    if "UV2" in program:
                        lighting = context.buffer(np.zeros((4, 2), dtype="i4").tobytes())
                        content.append((lighting, "2i", "UV2"))
                    array = context.vertex_array(program, content)
                    textures[name, char].use(0)
                    array.render(mode=moderngl.TRIANGLE_FAN)
                    array.release()
                    vertex_buffer.release()
                    if lighting:
                        lighting.release()
                    cursor += advance
            assert cursor == (8 if image else 0), ("Unbalanced actual component cursor", cursor)
        return np.frombuffer(framebuffer.read(components=4), dtype=np.uint8).reshape(size, size, 4)[::-1].copy()

    checks = []
    full = draw(components["full_image"], image=True)
    original = np.array(Image.open(args.pack / "full-source.png").convert("RGBA"))
    indices = np.maximum(0, np.ceil((np.arange(173) + 0.5) * 256 / 173).astype(int) - 1)
    expected = np.zeros_like(full)
    expected[40:213, 40:213] = original[indices[:, None], indices]
    Image.fromarray(full).save(args.out / "image-256.png")
    assert np.array_equal(full, expected), ("256px source changed or tile seam", np.count_nonzero(np.any(full != expected, axis=2)))
    checks.append("256px original image, noninteger scaling, tiles and row slices: exact pixels")
    assert not np.array_equal(draw(components["full_image"], image=True, broken=True), expected)
    checks.append("negative control: broken payload fails image comparison")
    with ZipFile(args.inventory) as inventory:
        state = json.loads(inventory.read("state.json"))["entries"]

        def reference(model, ticks):
            pixels = np.zeros((size, size, 4), dtype=np.uint8)
            for part in state["minecraft:" + model]["parts"]:
                geometry = np.array(part["geometry"])
                vertices = geometry[:16].reshape(4, 4)
                pos = vertices[:, :2] * 64 + size / 2
                path = "assets/" + part["sprite"].replace(":", "/textures/") + ".png"
                source = np.array(Image.open(BytesIO(inventory.read(path))).convert("RGBA"))
                h, w = source.shape[:2]
                animation = json.loads(inventory.read(path + ".mcmeta")).get("animation") if path + ".mcmeta" in inventory.namelist() else None
                current, following, blend = 0, 0, 0
                if animation is not None:
                    fw, fh = animation.get("width"), animation.get("height")
                    if fw is None and fh is None:
                        fw = fh = min(w, h)
                    w, h = fw or w, fh or h
                    frames = animation.get("frames", list(range(source.shape[1] // w * (source.shape[0] // h))))
                    sequence = [(f["index"], f.get("time", animation.get("frametime", 1))) if isinstance(f, dict)
                                else (f, animation.get("frametime", 1)) for f in frames]
                    time = ticks % sum(t for _, t in sequence)
                    for i, (index, duration) in enumerate(sequence):
                        if time < duration:
                            current, following = index, sequence[(i + 1) % len(sequence)][0]
                            blend = time / duration if animation.get("interpolate") else 0
                            break
                        time -= duration
                edges = np.array(((0, 0), (0, 1), (1, 1), (1, 0)))
                uv = vertices[:, 2:] + edges * (w, h)
                for triangle in ((0, 1, 2), (0, 2, 3)):
                    points, coords = pos[list(triangle)], uv[list(triangle)]
                    lo = np.maximum(0, np.floor(points.min(axis=0)).astype(int))
                    hi = np.minimum(size, np.ceil(points.max(axis=0)).astype(int))
                    if np.any(hi <= lo):
                        continue
                    yy, xx = np.mgrid[lo[1]:hi[1], lo[0]:hi[0]]
                    query = np.stack((xx + 0.5, yy + 0.5), axis=-1)
                    matrix = np.stack((points[1] - points[0], points[2] - points[0]), axis=-1)
                    if abs(np.linalg.det(matrix)) < 1e-9:
                        continue
                    weights = (query - points[0]) @ np.linalg.inv(matrix).T
                    inside = (weights >= -1e-7).all(axis=-1) & (weights.sum(axis=-1) <= 1 + 1e-7)
                    texel = coords[0] + weights @ (coords[1:] - coords[0])
                    tx = np.clip(np.floor(texel[:, :, 0]).astype(int), 0, w - 1)
                    ty = np.clip(np.floor(texel[:, :, 1]).astype(int), 0, h - 1)
                    columns = source.shape[1] // w
                    color = source[ty + current // columns * h, tx + current % columns * w].astype(float)
                    next_color = source[ty + following // columns * h, tx + following % columns * w]
                    color[:, :, :3] = (color[:, :, :3] * (1 - blend) + next_color[:, :, :3] * blend) * geometry[16:19]
                    inside &= color[:, :, 3] >= 25.5
                    patch = pixels[lo[1]:hi[1], lo[0]:hi[0]]
                    patch[inside] = np.rint(color[inside]).clip(0, 255).astype(np.uint8)
            return pixels

        for model in ("stone", "oak_stairs", "red_bed", "red_banner", "shield", "dragon_head", "prismarine", "magma_block", "sculk_sensor"):
            for ticks in ((0, 5, 15) if model in ("prismarine", "magma_block") else (0,)):
                actual = draw(components[model], ticks=ticks)
                expected = reference(model, ticks)
                Image.fromarray(actual).save(args.out / f"{model}-{ticks}.png")
                difference = np.max(np.abs(actual.astype(int) - expected.astype(int)), axis=2) > 1
                count = np.count_nonzero(difference)
                footprint = max(1, np.count_nonzero(expected[:, :, 3]))
                if count > max(2, footprint * 0.005):
                    Image.fromarray(expected).save(args.out / f"{model}-{ticks}-expected.png")
                    raise AssertionError((model, ticks, "GPU/CPU pixel differences", count, footprint))
                assert footprint > 50
                checks.append(f"{model} ticks={ticks}: original geometry/UV/tint, <=0.5% edge differences")
        assert not np.array_equal(draw(components["stone"], broken=True), reference("stone", 0))
        checks.append("negative control: broken payload fails model comparison")
    (args.out / "results.json").write_text(json.dumps({"renderer": context.info["GL_RENDERER"], "checks": checks,
        "scope": "Offscreen exported shader/bitmap rendering, not Minecraft client or player acceptance"}, indent=2))
    print("PASS", len(checks), "GPU checks")


if __name__ == "__main__":
    main()
