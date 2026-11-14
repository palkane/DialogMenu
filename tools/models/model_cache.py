"""Persist resolved inventory geometry by its actual resource dependencies."""
from dataclasses import asdict
import hashlib
import json
from pathlib import Path
import tempfile
from zipfile import BadZipFile, ZIP_DEFLATED, ZipFile, ZipInfo

from model_catalog import Face, geometry, resource, visible_faces


def digest(data):
    return hashlib.sha256(data).hexdigest()


def write_changed(path, data):
    path = Path(path)
    if path.exists() and path.read_bytes() == data:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(data)
    try:
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)
    return True


def write_json_changed(path, value):
    return write_changed(path, json.dumps(value, separators=(",", ":"), sort_keys=True).encode())


def write_pack_changed(path, files):
    path = Path(path)
    if path.exists():
        try:
            with ZipFile(path) as previous:
                if (set(previous.namelist()) == set(files)
                        and all(previous.read(name) == data for name, data in files.items())):
                    return False
        except BadZipFile:
            pass
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
    try:
        with ZipFile(temporary, "w", ZIP_DEFLATED) as archive:
            for name, data in sorted(files.items()):
                info = ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                info.compress_type = ZIP_DEFLATED
                archive.writestr(info, data)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)
    return True


class PreparedCatalog:
    def __init__(self, catalog, directory):
        self.catalog = catalog
        self.directory = Path(directory)
        root = Path(__file__).resolve().parent
        self.compiler = digest(b"".join((root / name).read_bytes() for name in
                                       ("model_catalog.py", "item_catalog.py", "model_cache.py")))
        self.memory = {}
        self.hits, self.misses = set(), set()

    def __getattr__(self, name):
        return getattr(self.catalog, name)

    @property
    def cache_stats(self):
        return {"reused": len(self.hits), "rebuilt": len(self.misses)}

    def prepared(self, name):
        # Registration is checked even for a warm cache, including revocations.
        registration = (self.catalog.registration(name)
                        if hasattr(self.catalog, "registration") else {})
        identity = {"compiler": self.compiler, "catalog": type(self.catalog).__name__,
                    "registration": registration}
        key = resource(name)
        if key in self.memory and self.memory[key][0] == identity:
            return self.memory[key][1]
        path = self.directory / (digest(key.encode()) + ".json")
        saved = None
        if path.exists():
            try:
                saved = json.loads(path.read_text())
            except (ValueError, UnicodeError):
                pass
        if (isinstance(saved, dict) and saved.get("identity") == identity
                and all(field in saved for field in ("dependencies", "faces", "source", "parts"))
                and all(self.catalog.fingerprint(dependency) == expected
                        for dependency, expected in saved["dependencies"].items())):
            faces = [Face(**{**face,
                             "points": tuple(tuple(p) for p in face["points"]),
                             "uv": tuple(tuple(p) for p in face["uv"]),
                             "tint": tuple(face["tint"]), "frame": tuple(face["frame"])})
                     for face in saved["faces"]]
            result = faces, saved["source"], saved["parts"]
            self.hits.add(key)
        else:
            self.catalog.dependencies = {}
            faces, source = self.catalog.block(name)
            parts = [{"atlas": face.atlas, "sprite": face.sprite, "geometry": list(geometry(face))}
                     for face in visible_faces(faces)]
            result = faces, source, parts
            write_json_changed(path, {"identity": identity,
                                     "dependencies": self.catalog.dependencies,
                                     "faces": [asdict(face) for face in faces],
                                     "source": source, "parts": parts})
            self.misses.add(key)
        self.memory[key] = identity, result
        return result

    def block(self, name):
        faces, source, _ = self.prepared(name)
        return faces, source
