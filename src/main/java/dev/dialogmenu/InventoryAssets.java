package dev.dialogmenu;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/** Precompiled default inventory appearances; never scans unrelated vanilla textures. */
final class InventoryAssets {
    private static final Map<ResourceVersion, InventoryAssets> libraries = new HashMap<>();
    private final Map<String, byte[]> files;
    private Map<String, ImageRegistry.Entry> images;
    private Map<String, String> nativeSprites;

    private InventoryAssets(Map<String, byte[]> files) { this.files = Collections.unmodifiableMap(files); }

    private static final boolean ITEMS_ATLAS = detectItemsAtlas();

    static boolean usesItemsAtlas() { return ITEMS_ATLAS; }

    // 按序列化能力选择协议分支；26.2 的 sprite 组件不再保留 atlas 字段。
    private static boolean detectItemsAtlas() {
        try {
            var serializer = GsonComponentSerializer.gson();
            String encoded = serializer.serialize(serializer.deserialize(
                "{\"type\":\"object\",\"atlas\":\"minecraft:blocks\",\"sprite\":\"minecraft:block/stone\"}"));
            return encoded.contains("\"sprite\"") && !encoded.contains("\"atlas\"");
        } catch (RuntimeException unsupported) {
            return false;
        }
    }

    static synchronized void prepare(AssetLibrary library) throws IOException {
        if (!libraries.containsKey(library.target())) {
            var next = new TreeMap<String, byte[]>();
            try (var zip = new ZipInputStream(new ByteArrayInputStream(library.read("vanilla-inventory.zip")))) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null;)
                    if (!entry.isDirectory()) next.put(entry.getName(), zip.readAllBytes());
            }
            libraries.put(library.target(), new InventoryAssets(next));
        }
    }

    static synchronized Map<String, byte[]> files() throws IOException {
        return files(ResourceVersion.DEFAULT);
    }

    private static InventoryAssets library(ResourceVersion version) throws IOException {
        InventoryAssets value = libraries.get(version);
        if (value == null) throw new IOException("Vanilla inventory library has not been prepared: " + version.id());
        return value;
    }

    static synchronized Map<String, byte[]> files(ResourceVersion version) throws IOException {
        return library(version).files;
    }

    static JsonObject json(String name) throws IOException {
        return json(ResourceVersion.DEFAULT, name);
    }

    static JsonObject json(ResourceVersion version, String name) throws IOException {
        byte[] bytes = files(version).get(name);
        if (bytes == null) throw new IOException("Missing inventory entry: " + name);
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    static synchronized Map<String, ImageRegistry.Entry> images() throws IOException {
        return images(ResourceVersion.DEFAULT);
    }

    static synchronized Map<String, ImageRegistry.Entry> images(ResourceVersion version) throws IOException {
        InventoryAssets library = library(version);
        if (library.images != null) return library.images;
        var next = new TreeMap<String, ImageRegistry.Entry>();
        for (var entry : json(version, "catalog.json").entrySet()) {
            var row = entry.getValue().getAsJsonObject();
            if (!row.get("kind").getAsString().equals("image")) continue;
            String source = row.get("source").getAsString();
            var image = ImageIO.read(new ByteArrayInputStream(library.files.get(row.get("path").getAsString())));
            var value = new ImageRegistry.Entry(image, false);
            next.put(ImageRegistry.PREFIX + source, value);
            next.put(ImageRegistry.PREFIX + "minecraft:item/" + entry.getKey().substring("minecraft:".length()), value);
        }
        return library.images = Collections.unmodifiableMap(next);
    }
    static synchronized Map<String, String> nativeSprites() throws IOException {
        return nativeSprites(ResourceVersion.DEFAULT);
    }
    static synchronized Map<String, String> nativeSprites(ResourceVersion version) throws IOException {
        InventoryAssets library = library(version);
        if (library.nativeSprites != null) return library.nativeSprites;
        var next = new TreeMap<String, String>();
        for (var entry : json(version, "catalog.json").entrySet()) {
            var row = entry.getValue().getAsJsonObject();
            if (!row.has("sprite")) continue;
            String sprite = row.get("sprite").getAsString();
            next.put(ImageRegistry.PREFIX + row.get("source").getAsString(), sprite);
            next.put(ImageRegistry.PREFIX + "minecraft:item/" + entry.getKey().substring(10), sprite);
        }
        return library.nativeSprites = Collections.unmodifiableMap(next);
    }
}
