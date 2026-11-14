"""Build versioned initialization libraries from local, matching vanilla clients."""
import argparse
import hashlib
from io import BytesIO
import json
import os
from pathlib import Path
import subprocess
import sys
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

from PIL import Image

VERSIONS = ("1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
            "26.1", "26.1.1", "26.1.2", "26.2", "26.3")
ROOT = Path(__file__).resolve().parents[1]


def archive(files):
    output = BytesIO()
    with ZipFile(output, "w") as target:
        for name, content in sorted(files.items()):
            info = ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            target.writestr(info, content)
    return output.getvalue()


def icons(client):
    files = {}
    prefix = "assets/minecraft/textures/"
    with ZipFile(client) as source:
        names = set(source.namelist())
        for name in sorted(names):
            if not (name.startswith((prefix + "block/", prefix + "item/")) and name.endswith(".png")):
                continue
            content = source.read(name)
            if name + ".mcmeta" in names:
                animation = json.loads(source.read(name + ".mcmeta")).get("animation")
                if animation is not None:
                    with Image.open(BytesIO(content)) as image:
                        w, h = image.size
                        width, height = animation.get("width"), animation.get("height")
                        if width is None and height is None:
                            width = height = min(w, h)
                        width, height = width or w, height or h
                        first = animation.get("frames", [0])[0]
                        first = first["index"] if isinstance(first, dict) else first
                        x, y = first % (w // width) * width, first // (w // width) * height
                        output = BytesIO()
                        image.crop((x, y, x + width, y + height)).save(output, format="PNG")
                        content = output.getvalue()
            files[name[len(prefix):]] = content
    # Legacy helper images are plugin assets, not version-specific vanilla textures.
    with ZipFile(ROOT / "src/main/resources/vanilla-icons.zip") as source:
        for name in source.namelist():
            if not name.startswith(("block/", "item/")):
                files[name] = source.read(name)
    return archive(files)


def build(clients, output):
    output.mkdir(parents=True, exist_ok=True)
    manifest_dir = output / "manifests"
    manifest_dir.mkdir(exist_ok=True)
    shared_glyphs = (ROOT / "src/main/resources/glyphs.bin.gz").read_bytes()
    groups, report = {}, {}
    for version in VERSIONS:
        client = clients / (version + "-client.jar")
        with ZipFile(client) as source:
            actual = json.loads(source.read("version.json"))["id"]
            if actual != version:
                raise ValueError(f"Expected client {version}, found {actual}: {client}")
        directory = output / version
        directory.mkdir(exist_ok=True)
        inventory = directory / "vanilla-inventory.zip"
        with (directory / "inventory.log").open("w") as log:
            subprocess.run([sys.executable, str(ROOT / "tools/models/inventory.py"),
                            "--output", str(inventory), "--cache", str(directory / "cache")],
                           env={**os.environ, "DIALOGMENU_CLIENT_JAR": str(client)},
                           stdout=log, stderr=subprocess.STDOUT, check=True)
        files = {"glyphs.bin.gz": shared_glyphs, "vanilla-icons.zip": icons(client),
                 "vanilla-inventory.zip": inventory.read_bytes()}
        if version == "26.3":
            files = {name: (ROOT / "src/main/resources" / name).read_bytes() for name in files}
        identity = tuple(hashlib.sha256(files[name]).hexdigest() for name in sorted(files))
        canonical = groups.setdefault(identity, version)
        library = "vanilla-" + canonical + ("-v1" if canonical == "26.3" else "-v2")
        lines = [f"version={library}", f"minecraft-version={version}", "glyph-source=26.3",
                 f"base-url=https://github.com/palkane/DialogMenu/releases/download/assets-{library}/"]
        for name, content in files.items():
            (directory / name).write_bytes(content)
            lines += [f"{name}.size={len(content)}",
                      f"{name}.sha256={hashlib.sha256(content).hexdigest()}"]
        (manifest_dir / (version + ".properties")).write_text("\n".join(lines) + "\n")
        report[version] = {"canonical": canonical, "library": library,
                           **json.loads((directory / "cache/summary.json").read_text()),
                           "bytes": len(files["vanilla-inventory.zip"])}
        print(version, canonical, report[version]["total"], flush=True)
    (output / "libraries.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--clients", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    build(args.clients.resolve(), args.output.resolve())
