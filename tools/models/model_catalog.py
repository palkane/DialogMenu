"""Read vanilla model geometry without baking block images."""
from copy import deepcopy
from dataclasses import dataclass, replace
from functools import lru_cache
import hashlib
from io import BytesIO
import json
import math
import os
from pathlib import Path
from zipfile import ZipFile

from PIL import Image

JAR = Path(os.environ["DIALOGMENU_CLIENT_JAR"])
EPS = 1e-7


class Unsupported(ValueError):
    pass


def resource(value):
    return value if ":" in value else "minecraft:" + value


def path_for(value, folder, suffix=".json"):
    namespace, name = resource(value).split(":", 1)
    return f"assets/{namespace}/{folder}/{name}{suffix}"


def rotate(point, axis, angle):
    x, y, z = point
    c, s = math.cos(math.radians(angle)), math.sin(math.radians(angle))
    if axis == "x":
        return x, c * y - s * z, s * y + c * z
    if axis == "y":
        return c * x + s * z, y, -s * x + c * z
    return c * x - s * y, s * x + c * y, z


def project(point):
    return rotate(rotate(point, "y", 45), "x", 30)


def quaternion(point, rotation):
    x, y, z, w = rotation
    q = (x, y, z)
    cross = (q[1] * point[2] - q[2] * point[1],
             q[2] * point[0] - q[0] * point[2],
             q[0] * point[1] - q[1] * point[0])
    second = (q[1] * cross[2] - q[2] * cross[1],
              q[2] * cross[0] - q[0] * cross[2],
              q[0] * cross[1] - q[1] * cross[0])
    return tuple(point[i] + 2 * (w * cross[i] + second[i]) for i in range(3))


def item_transform(faces, transforms):
    result = []
    for face in faces:
        points = []
        for point in face.points:
            p = tuple(v + 0.5 for v in point)
            for transform in reversed(transforms):
                if not isinstance(transform, dict):
                    raise Unsupported("matrix item transformation")
                p = quaternion(p, transform.get("right_rotation", (0, 0, 0, 1)))
                p = tuple(p[i] * transform.get("scale", (1, 1, 1))[i] for i in range(3))
                p = quaternion(p, transform.get("left_rotation", (0, 0, 0, 1)))
                p = tuple(p[i] + transform.get("translation", (0, 0, 0))[i] for i in range(3))
            points.append(tuple(v - 0.5 for v in p))
        result.append(replace(face, points=tuple(points)))
    return result


def normal(points):
    a = [points[1][i] - points[0][i] for i in range(3)]
    b = [points[2][i] - points[0][i] for i in range(3)]
    n = (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2],
         a[0] * b[1] - a[1] * b[0])
    length = math.sqrt(sum(v * v for v in n))
    return tuple(v / length for v in n) if length > EPS else (0, 0, 0)


@dataclass(frozen=True)
class Face:
    sprite: str
    points: tuple
    uv: tuple
    tint: tuple = (1.0, 1.0, 1.0)
    shade: bool = True
    shade_direction: str = ""
    frame: tuple = (16, 16)
    atlas: str = "minecraft:blocks"


def face_corners(direction, low, high):
    x, y, z = low
    X, Y, Z = high
    return {
        "north": ((X, Y, z), (X, y, z), (x, y, z), (x, Y, z)),
        "south": ((x, Y, Z), (x, y, Z), (X, y, Z), (X, Y, Z)),
        "east": ((X, Y, Z), (X, y, Z), (X, y, z), (X, Y, z)),
        "west": ((x, Y, z), (x, y, z), (x, y, Z), (x, Y, Z)),
        "up": ((x, Y, z), (x, Y, Z), (X, Y, Z), (X, Y, z)),
        "down": ((x, y, Z), (x, y, z), (X, y, z), (X, y, Z)),
    }[direction]


def default_uv(direction, low, high):
    x, y, z = low
    X, Y, Z = high
    return {
        "down": (x, 16 - Z, X, 16 - z), "up": (x, z, X, Z),
        "north": (16 - X, 16 - Y, 16 - x, 16 - y),
        "south": (x, 16 - Y, X, 16 - y),
        "west": (z, 16 - Y, Z, 16 - y),
        "east": (16 - Z, 16 - Y, 16 - z, 16 - y),
    }[direction]


class Catalog:
    def __init__(self, jar=JAR):
        self.archive = ZipFile(jar)
        self.names = set(self.archive.namelist())
        self.init_tracking()

    def init_tracking(self):
        self.dependencies = {}
        self.fingerprints = {}

    def fingerprint(self, path):
        if path not in self.fingerprints:
            if path == "@native":
                file = Path(__file__).resolve().parent / "inventory/special-research/native-geometry.json"
                data = file.read_bytes() if file.exists() else None
            else:
                data = self.archive.read(path) if path in self.names else None
            self.fingerprints[path] = hashlib.sha256(data).hexdigest() if data is not None else None
        return self.fingerprints[path]

    def exists(self, path):
        self.dependencies[path] = self.fingerprint(path)
        return path in self.names

    def json(self, path):
        self.dependencies[path] = self.fingerprint(path)
        return self._json(path)

    @lru_cache(maxsize=None)
    def _json(self, path):
        if path not in self.names:
            raise Unsupported(f"missing resource: {path}")
        return json.loads(self.archive.read(path))

    def blocks(self):
        prefix = "assets/minecraft/blockstates/"
        return sorted(name[len(prefix):-5] for name in self.names
                      if name.startswith(prefix) and name.endswith(".json"))

    def model(self, name, parents=()):
        name = resource(name)
        if name in parents:
            raise Unsupported(f"model inheritance cycle: {name}")
        own = self.json(path_for(name, "models"))
        if own.get("parent", "").startswith(("builtin/", "minecraft:builtin/")):
            raise Unsupported(f"builtin model: {own['parent']}")
        merged = self.model(own["parent"], (*parents, name)) if own.get("parent") else {}
        merged = deepcopy(merged)
        textures = {**merged.get("textures", {}), **own.get("textures", {})}
        merged.update(own)
        merged["textures"] = textures
        return merged

    def generated_item(self, name, parents=()):
        name = resource(name)
        if name in parents:
            raise Unsupported(f"model inheritance cycle: {name}")
        parent = self.json(path_for(name, "models")).get("parent")
        if not parent:
            return False
        if resource(parent) == "minecraft:builtin/generated":
            return True
        if resource(parent).startswith("minecraft:builtin/"):
            return False
        return self.generated_item(parent, (*parents, name))

    def sprite_frame(self, sprite):
        path = path_for(sprite, "textures", ".png")
        if not self.exists(path):
            raise Unsupported(f"missing texture: {sprite}")
        self.exists(path + ".mcmeta")
        return self._sprite_frame(sprite)

    @lru_cache(maxsize=None)
    def _sprite_frame(self, sprite):
        path = path_for(sprite, "textures", ".png")
        with Image.open(BytesIO(self.archive.read(path))) as image:
            w, h = image.size
        if path + ".mcmeta" not in self.names:
            return w, h
        animation = self.json(path + ".mcmeta").get("animation")
        if animation is None:
            return w, h
        width, height = animation.get("width"), animation.get("height")
        if width is None and height is None:
            return min(w, h), min(w, h)
        return width or w, height or h

    def texture(self, name, textures):
        if isinstance(name, str) and name in textures:
            name = "#" + name
        seen = set()
        while isinstance(name, str) and name.startswith("#"):
            if name in seen:
                raise Unsupported(f"texture reference cycle: {name}")
            seen.add(name)
            name = textures.get(name[1:])
        if isinstance(name, dict):
            name = name.get("sprite")
        if not isinstance(name, str):
            raise Unsupported("unresolved face texture")
        return resource(name)

    def tint(self, item, index, sprite):
        tints = item.get("tints", [])
        source = tints[index] if 0 <= index < len(tints) else {}
        kind = source.get("type", "")
        if kind == "minecraft:constant":
            value = source["value"] & 0xFFFFFF
            return tuple(((value >> shift) & 255) / 255 for shift in (16, 8, 0))
        if kind == "minecraft:grass":
            t = max(0, min(1, source.get("temperature", 0.5)))
            h = max(0, min(1, source.get("downfall", 1))) * t
            path = "assets/minecraft/textures/colormap/grass.png"
            self.dependencies[path] = self.fingerprint(path)
            with Image.open(BytesIO(self.archive.read(path))) as image:
                rgb = image.convert("RGB").getpixel((int((1 - t) * 255), int((1 - h) * 255)))
            return tuple(v / 255 for v in rgb)
        if "redstone" in sprite:
            return 0.6, 0.0, 0.0
        return 0.4863, 0.7412, 0.4196

    def faces(self, name, item=None, state_rotation=(0, 0), uvlock=False, model_data=None):
        model = self.model(name) if model_data is None else model_data
        if "elements" not in model:
            raise Unsupported(f"no JSON elements: {resource(name)}")
        result = []
        for element in model["elements"]:
            low, high = element["from"], element["to"]
            for direction, face in element["faces"].items():
                sprite = self.texture(face["texture"], model["textures"])
                points = face_corners(direction, low, high)
                if "rotation" in element:
                    rotation = element["rotation"]
                    axis, angle = rotation["axis"], rotation["angle"]
                    center = rotation.get("origin", (8, 8, 8))
                    transformed = []
                    for point in points:
                        relative = rotate(tuple(point[i] - center[i] for i in range(3)), axis, angle)
                        if rotation.get("rescale"):
                            scale = 1 / abs(math.cos(math.radians(angle)))
                            relative = tuple(v * (1 if "xyz"[i] == axis else scale)
                                             for i, v in enumerate(relative))
                        transformed.append(tuple(relative[i] + center[i] for i in range(3)))
                    points = transformed
                points = tuple(tuple((v - 8) / 16 for v in point) for point in points)
                original = points
                points = tuple(rotate(rotate(point, "x", -state_rotation[0]),
                                      "y", -state_rotation[1]) for point in points)
                uv = face.get("uv", default_uv(direction, low, high))
                u, v, U, V = (value / 16 for value in uv)
                coords = ((u, v), (u, V), (U, V), (U, v))
                turns = face.get("rotation", 0) // 90
                if uvlock and any(state_rotation):
                    after_normal = normal(points)
                    # Map world-aligned face axes back onto the rotated face.
                    target_dir = max(("east", "west", "up", "down", "south", "north"),
                                     key=lambda d: sum(a * b for a, b in zip(
                                         after_normal, {"east": (1, 0, 0), "west": (-1, 0, 0),
                                                        "up": (0, 1, 0), "down": (0, -1, 0),
                                                        "south": (0, 0, 1), "north": (0, 0, -1)}[d])))
                    target = face_corners(target_dir, (-0.5,) * 3, (0.5,) * 3)
                    edge = tuple(points[3][i] - points[0][i] for i in range(3))
                    # Target UV right is TL->TR; cycle is TL,BL,BR,TR.
                    target_right = tuple(target[3][i] - target[0][i] for i in range(3))
                    target_down = tuple(target[1][i] - target[0][i] for i in range(3))
                    dot_right = sum(a * b for a, b in zip(edge, target_right))
                    dot_down = sum(a * b for a, b in zip(edge, target_down))
                    if abs(dot_right) + abs(dot_down) > EPS:
                        turns += (0 if dot_right > EPS else 2 if dot_right < -EPS
                                  else 1 if dot_down > 0 else 3)
                coords = tuple(coords[(corner + turns) % 4] for corner in range(4))
                tint = self.tint(item or {}, face["tintindex"], sprite) if "tintindex" in face else (1, 1, 1)
                result.append(Face(sprite, points, coords, tint,
                                   element.get("shade", True),
                                   element.get("shade_direction_override", ""),
                                   self.sprite_frame(sprite)))
        return result

    def item_node(self, node, transforms=()):
        transforms = (*transforms, node["transformation"]) if "transformation" in node else transforms
        kind = node["type"].split(":")[-1]
        if kind in ("model", "special", "empty"):
            return [{**node, "_transforms": transforms}]
        if kind == "composite":
            return [child for part in node["models"] for child in self.item_node(part, transforms)]
        if kind == "select" and node.get("property") == "minecraft:display_context":
            for case in node.get("cases", []):
                values = case["when"] if isinstance(case["when"], list) else [case["when"]]
                if "gui" in values:
                    return self.item_node(case["model"], transforms)
        if kind == "range_dispatch" and "fallback" not in node and node.get("entries"):
            return self.item_node(node["entries"][0]["model"], transforms)
        if kind in ("select", "range_dispatch"):
            if "fallback" not in node:
                raise Unsupported(f"item {kind} without fallback")
            return self.item_node(node["fallback"], transforms)
        if kind == "condition":
            return self.item_node(node["on_false"], transforms)
        raise Unsupported(f"item model type: {kind}")

    def native_models(self):
        self.dependencies["@native"] = self.fingerprint("@native")
        return self._native_models()

    @lru_cache(maxsize=None)
    def _native_models(self):
        path = Path(__file__).resolve().parent / "inventory/special-research/native-geometry.json"
        return json.loads(path.read_text())

    def native_faces(self, model, atlas, sprite, tint=(1, 1, 1)):
        result = []
        for face in self.native_models()["models"][model]["faces"]:
            vertices = face["vertices"]
            points = tuple(tuple(v[i] - 0.5 for i in range(3)) for v in vertices)
            uv = tuple(tuple(v[3:5]) for v in vertices)
            result.append(Face(sprite, points, uv, tint, frame=self.sprite_frame(sprite), atlas=atlas))
        return result

    def special_faces(self, node):
        spec = node["model"]
        kind = spec["type"].split(":")[-1]
        if spec.get("openness", 0) != 0:
            raise Unsupported("Only closed inventory special models are supported")
        if kind in ("chest", "shulker_box"):
            atlas = "minecraft:chests" if kind == "chest" else "minecraft:shulker_boxes"
            namespace, texture = resource(spec["texture"]).split(":", 1)
            folder = "chest" if kind == "chest" else "shulker"
            faces = self.native_faces(kind, atlas, f"{namespace}:entity/{folder}/{texture}")
        elif kind == "bed":
            namespace, texture = resource(spec["texture"]).split(":", 1)
            sprite = f"{namespace}:entity/bed/{texture}"
            part = spec.get("part")
            if part is not None:
                if part not in ("head", "foot"):
                    raise Unsupported("Unknown bed part: " + str(part))
                faces = self.native_faces("bed_" + part, "minecraft:beds", sprite)
            else:
                # Before 26.1 the special renderer, not the item JSON, placed both halves.
                faces = []
                for half, offset in (("head", 0), ("foot", -1)):
                    for face in self.native_faces("bed_" + half, "minecraft:beds", sprite):
                        points = tuple((-p[0], -0.4375 - p[2], offset - p[1])
                                       for p in face.points)
                        faces.append(replace(face, points=points))
        elif kind == "banner":
            atlas = "minecraft:banner_patterns"
            color = self.native_models()["dyes"][spec["color"]]
            tint = tuple(((color >> shift) & 255) / 255 for shift in (16, 8, 0))
            base = "minecraft:entity/banner/banner_base"
            if not self.exists(path_for(base, "textures", ".png")):
                base = "minecraft:entity/banner_base"
            faces = self.native_faces("banner_stand", atlas, base)
            faces += self.native_faces("banner_flag", atlas, base)
            faces += self.native_faces("banner_flag", atlas, "minecraft:entity/banner/base", tint)
        elif kind in ("head", "player_head"):
            head = spec.get("kind", "player")
            model = {"player": "player_head", "zombie": "player_head",
                     "dragon": "dragon_head", "piglin": "piglin_head"}.get(head, "skull")
            texture = {"skeleton": "skeleton/skeleton", "wither_skeleton": "skeleton/wither_skeleton",
                       "zombie": "zombie/zombie", "creeper": "creeper/creeper",
                       "dragon": "enderdragon/dragon", "piglin": "piglin/piglin",
                       "player": "player/wide/steve"}[head]
            faces = self.native_faces(model, "minecraft:blocks", "minecraft:entity/" + texture)
        elif kind == "conduit":
            faces = self.native_faces("conduit", "minecraft:blocks", "minecraft:entity/conduit/base")
        elif kind == "copper_golem_statue":
            if spec.get("pose", "standing") != "standing":
                raise Unsupported("Default inventory statue requires standing pose")
            sprite = spec["texture"].replace(":textures/", ":").removesuffix(".png")
            faces = self.native_faces("copper_golem_statue", "minecraft:blocks", sprite)
        elif kind == "decorated_pot":
            faces = self.native_faces("pot_base", "minecraft:decorated_pot",
                                      "minecraft:entity/decorated_pot/decorated_pot_base")
            faces += self.native_faces("pot_sides", "minecraft:decorated_pot",
                                       "minecraft:entity/decorated_pot/decorated_pot_side")
        elif kind == "shield":
            sprite = "minecraft:entity/shield/shield_base_nopattern"
            if not self.exists(path_for(sprite, "textures", ".png")):
                sprite = "minecraft:entity/shield_base_nopattern"
            faces = self.native_faces("shield", "minecraft:blocks", sprite)
        else:
            raise Unsupported("special renderer: " + spec["type"])
        return item_transform(faces, node["_transforms"])

    def state(self, name, overrides):
        data = self.json(path_for(name, "blockstates"))
        values = {}

        def collect(condition):
            if isinstance(condition, dict):
                for key, value in condition.items():
                    if key in ("OR", "AND"):
                        for child in value:
                            collect(child)
                    else:
                        values.setdefault(key, set()).update(str(value).split("|"))

        for key in data.get("variants", {}):
            collect(dict(part.split("=") for part in key.split(",") if part))
        for part in data.get("multipart", []):
            collect(part.get("when", {}))
        preferences = {"facing": "north", "axis": "y", "half": "bottom", "type": "bottom",
                       "shape": "straight", "face": "floor", "attachment": "floor",
                       "part": "foot", "hinge": "left", "vertical_direction": "up",
                       "thickness": "tip"}
        state = {}
        for key, options in values.items():
            wanted = preferences.get(key)
            state[key] = (wanted if wanted in options else "false" if "false" in options
                          else "0" if "0" in options else sorted(options)[0])
        for key, value in overrides.items():
            if key not in values or value not in values[key]:
                raise Unsupported(f"invalid state property: {key}={value}")
            state[key] = value

        def matches(condition):
            if "OR" in condition:
                return any(matches(child) for child in condition["OR"])
            if "AND" in condition:
                return all(matches(child) for child in condition["AND"])
            return all(state.get(key) in str(value).split("|") for key, value in condition.items())

        selected = []
        for key, entry in data.get("variants", {}).items():
            if matches(dict(part.split("=") for part in key.split(",") if part)):
                selected.append(entry[0] if isinstance(entry, list) else entry)
                break
        for part in data.get("multipart", []):
            if matches(part.get("when", {})):
                entry = part["apply"]
                selected.append(entry[0] if isinstance(entry, list) else entry)
        if not selected:
            raise Unsupported("no matching blockstate variant")
        return state, selected

    def block(self, name, overrides=None, inventory=True):
        name = resource(name)
        if inventory and not overrides:
            item_path = path_for(name, "items")
            if not self.exists(item_path):
                return [], {"source": "no-inventory-item", "skip_reason": "no independent inventory item"}
            nodes = self.item_node(self.json(item_path)["model"])
            special = [node for node in nodes if node["type"] == "minecraft:special"]
            if special:
                if any(node["type"] not in ("minecraft:special", "minecraft:empty") for node in nodes):
                    raise Unsupported("mixed ordinary/special inventory composite")
                return [face for node in special for face in self.special_faces(node)], {
                    "source": "inventory-special", "models": [node["base"] for node in special],
                    "renderers": [node["model"]["type"] for node in special]}
            models = [node for node in nodes if node["type"] == "minecraft:model"]
            flat = [self.generated_item(node["model"]) for node in models]
            if flat and all(flat):
                return [], {"source": "inventory-2d", "skip_reason": "generated 2D inventory icon",
                            "models": [node["model"] for node in models]}
            if any(flat):
                raise Unsupported("mixed 2D/3D inventory composite")
            if name == "minecraft:air":
                return [], {"source": "inventory-empty"}
            return [face for node in models for face in item_transform(
                self.faces(node["model"], node), node["_transforms"])], {
                    "source": "inventory", "models": [node["model"] for node in models]}
        if name in ("minecraft:air", "minecraft:cave_air", "minecraft:void_air",
                    "minecraft:barrier", "minecraft:structure_void", "minecraft:light"):
            return [], {"source": "invisible"}
        state, entries = self.state(name, overrides or {})
        return [face for entry in entries for face in self.faces(
            entry["model"], None, (entry.get("x", 0), entry.get("y", 0)),
            entry.get("uvlock", False))], {
                "source": "blockstate", "state": state, "models": [entry["model"] for entry in entries]}


def visible_faces(faces):
    result = []
    for face in faces:
        points = tuple(project(point) for point in face.points)
        # TL,BL,BR,TR gives an outward normal with this coordinate system.
        if normal(points)[2] > EPS:
            result.append(replace(face, points=points))
    return painter_order(result)


def clip_face(face, plane_point, plane_normal, keep_front):
    points, coords = [], []
    distances = [sum((p[i] - plane_point[i]) * plane_normal[i] for i in range(3))
                 for p in face.points]
    sign = 1 if keep_front else -1
    for index, point in enumerate(face.points):
        previous = (index - 1) % len(face.points)
        distance, before = distances[index] * sign, distances[previous] * sign
        if (distance > EPS and before < -EPS) or (distance < -EPS and before > EPS):
            ratio = before / (before - distance)
            points.append(tuple(face.points[previous][i] + ratio * (
                point[i] - face.points[previous][i]) for i in range(3)))
            coords.append(tuple(face.uv[previous][i] + ratio * (
                face.uv[index][i] - face.uv[previous][i]) for i in range(2)))
        if distance >= -EPS:
            points.append(point)
            coords.append(face.uv[index])
    if len(points) < 3:
        return None
    return replace(face, points=tuple(points), uv=tuple(coords))


def painter_order(faces):
    """BSP ordering splits intersecting faces instead of sorting their centers."""
    if not faces:
        return []
    splitter = faces[0]
    origin, direction = splitter.points[0], normal(splitter.points)
    back, front, coplanar = [], [], []
    for face in faces:
        distances = [sum((p[i] - origin[i]) * direction[i] for i in range(3)) for p in face.points]
        low, high = min(distances), max(distances)
        if low >= -EPS and high <= EPS:
            coplanar.append(face)
        elif low >= -EPS:
            front.append(face)
        elif high <= EPS:
            back.append(face)
        else:
            for output, keep in ((front, True), (back, False)):
                piece = clip_face(face, origin, direction, keep)
                if piece:
                    output.append(piece)
    ordered = painter_order(back) + coplanar + painter_order(front)
    quads = []
    for face in ordered:
        if len(face.points) == 4:
            quads.append(face)
        else:
            for index in range(1, len(face.points) - 1):
                indices = (0, index, index + 1, index + 1)
                quads.append(replace(face, points=tuple(face.points[i] for i in indices),
                                     uv=tuple(face.uv[i] for i in indices)))
    return quads


def geometry(face):
    direction = normal(face.points)
    if face.shade_direction:
        direction = project({"up": (0, 1, 0), "down": (0, -1, 0),
                             "north": (0, 0, -1), "south": (0, 0, 1),
                             "east": (1, 0, 0), "west": (-1, 0, 0)}[face.shade_direction])
    light = (0.5 + 0.5 * max(0, sum(a * b for a, b in zip(direction, (-0.2281, 0.6082, 0.7603))))
             if face.shade else 1)
    vertices = []
    for index, (point, uv) in enumerate(zip(face.points, face.uv)):
        edge = (1 if index >= 2 else 0, 1 if index in (1, 2) else 0)
        vertices.append((point[0], -point[1],
                         (uv[0] - edge[0]) * face.frame[0],
                         (uv[1] - edge[1]) * face.frame[1]))
    return tuple(round(value, 6) for vertex in vertices for value in vertex) + tuple(
        round(channel * light, 6) for channel in face.tint) + (1.0,)


def audit(catalog, names=None):
    rows = {}
    for name in catalog.blocks() if names is None else names:
        try:
            if hasattr(catalog, "prepared"):
                faces, source, visible = catalog.prepared(name)
            else:
                faces, source = catalog.block(name)
                visible = visible_faces(faces)
            status = "skipped" if "skip_reason" in source else "geometry" if faces else "empty"
            rows[name] = {**source, "status": status,
                          "faces": len(faces), "visible": len(visible)}
        except (Unsupported, KeyError, ValueError) as error:
            rows[name] = {"status": "unsupported", "reason": str(error)}
    return rows
