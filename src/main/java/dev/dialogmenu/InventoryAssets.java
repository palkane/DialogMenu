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
    private static Map<String, byte[]> files;
    private static Map<String, ImageRegistry.Entry> images;
    private static Map<String, String> nativeSprites;

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
        if (files == null) {
            var next = new TreeMap<String, byte[]>();
            try (var zip = new ZipInputStream(new ByteArrayInputStream(library.read("vanilla-inventory.zip")))) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null;)
                    if (!entry.isDirectory()) next.put(entry.getName(), zip.readAllBytes());
            }
            files = Collections.unmodifiableMap(next);
        }
    }

    static synchronized Map<String, byte[]> files() throws IOException {
        if (files == null) throw new IOException("Vanilla inventory library has not been prepared");
        return files;
    }

    static JsonObject json(String name) throws IOException {
        byte[] bytes = files().get(name);
        if (bytes == null) throw new IOException("Missing inventory entry: " + name);
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    static synchronized Map<String, ImageRegistry.Entry> images() throws IOException {
        if (images != null) return images;
        var next = new TreeMap<String, ImageRegistry.Entry>();
        for (var entry : json("catalog.json").entrySet()) {
            var row = entry.getValue().getAsJsonObject();
            if (!row.get("kind").getAsString().equals("image")) continue;
            String source = row.get("source").getAsString();
            var image = ImageIO.read(new ByteArrayInputStream(files().get(row.get("path").getAsString())));
            var value = new ImageRegistry.Entry(image, false);
            next.put(ImageRegistry.PREFIX + source, value);
            next.put(ImageRegistry.PREFIX + "minecraft:item/" + entry.getKey().substring("minecraft:".length()), value);
        }
        images = Collections.unmodifiableMap(next);
        return images;
    }
    static synchronized Map<String, String> nativeSprites() throws IOException {
        if (nativeSprites != null) return nativeSprites;
        var next = new TreeMap<String, String>();
        for (var entry : json("catalog.json").entrySet()) {
            var row = entry.getValue().getAsJsonObject();
            if (!row.has("sprite")) continue;
            String sprite = row.get("sprite").getAsString();
            next.put(ImageRegistry.PREFIX + row.get("source").getAsString(), sprite);
            next.put(ImageRegistry.PREFIX + "minecraft:item/" + entry.getKey().substring(10), sprite);
        }
        return nativeSprites = Collections.unmodifiableMap(next);
    }
}
