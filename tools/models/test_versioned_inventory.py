"""Checks against the real local client JARs and generated version libraries."""
from io import BytesIO
import json
import math
import os
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from item_catalog import ItemCatalog
from model_catalog import path_for
from inventory import build

CLIENTS = Path(os.environ["DIALOGMENU_CLIENTS"])
LIBRARIES = Path(os.environ["DIALOGMENU_VERSIONED_LIBRARIES"])
VERSIONS = ("1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
            "26.1", "26.1.1", "26.1.2", "26.2", "26.3")
COLORS = ("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
          "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black")


class VersionedInventoryTests(unittest.TestCase):
    def test_cold_and_warm_inventory_are_byte_identical(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build(root / "cold.zip", root / "cache")
            build(root / "warm.zip", root / "cache")
            self.assertEqual((root / "cold.zip").read_bytes(), (root / "warm.zip").read_bytes())

    def test_each_library_has_exact_target_inventory_and_original_textures(self):
        for version in VERSIONS:
            with self.subTest(version=version), \
                    ZipFile(CLIENTS / (version + "-client.jar")) as client, \
                    ZipFile(LIBRARIES / version / "vanilla-inventory.zip") as library:
                catalog = json.loads(library.read("catalog.json"))
                expected = {"minecraft:" + name[len("assets/minecraft/items/"):-5]
                            for name in client.namelist()
                            if name.startswith("assets/minecraft/items/") and name.endswith(".json")}
                self.assertEqual(set(catalog), expected)
                self.assertTrue(all(row["kind"] in ("model", "image", "empty") for row in catalog.values()))
                state = json.loads(library.read("state.json"))["entries"]
                for item in ("shield", *(c + "_bed" for c in COLORS), *(c + "_banner" for c in COLORS)):
                    self.assertEqual(catalog["minecraft:" + item]["kind"], "model")
                    parts = state["minecraft:" + item]["parts"]
                    self.assertTrue(parts)
                    for part in parts:
                        self.assertTrue(all(math.isfinite(n) for n in part["geometry"]))
                        texture = path_for(part["sprite"], "textures", ".png")
                        self.assertEqual(library.read(texture), client.read(texture))
                # Embedded geometry packs are usable archives, not opaque renamed blobs.
                with ZipFile(BytesIO(library.read("resourcepack.zip"))) as pack:
                    self.assertIn("assets/dialogmenu_vanilla/shaders/include/models.glsl", pack.namelist())

    def test_legacy_bed_occupies_two_blocks_and_keeps_the_old_texture(self):
        with tempfile.TemporaryDirectory() as directory:
            registry = Path(directory) / "registry.json"
            registry.write_text('{"version":1,"items":{}}')
            for version in ("1.21.7", "1.21.11", "26.1"):
                with self.subTest(version=version):
                    catalog = ItemCatalog(jar=CLIENTS / (version + "-client.jar"), registry=registry)
                    try:
                        faces, _ = catalog.block("minecraft:red_bed")
                        self.assertEqual({face.sprite for face in faces}, {"minecraft:entity/bed/red"})
                        points = [point for face in faces for point in face.points]
                        for axis, expected in enumerate(((-0.5, 0.5), (-0.5, 0.0625), (-1.5, 0.5))):
                            self.assertAlmostEqual(min(p[axis] for p in points), expected[0], places=5)
                            self.assertAlmostEqual(max(p[axis] for p in points), expected[1], places=5)
                    finally:
                        catalog.archive.close()

    def test_alias_libraries_are_byte_identical(self):
        for versions in (("1.21.7", "1.21.8"), ("1.21.9", "1.21.10"), ("26.1", "26.1.1", "26.1.2")):
            for name in ("glyphs.bin.gz", "vanilla-icons.zip", "vanilla-inventory.zip"):
                original = (LIBRARIES / versions[0] / name).read_bytes()
                for version in versions[1:]:
                    self.assertEqual(original, (LIBRARIES / version / name).read_bytes())


if __name__ == "__main__":
    unittest.main()
