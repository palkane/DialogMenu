package dev.dialogmenu;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/** Whole native-atlas sprites; display geometry never changes the prepared image. */
final class RegisteredImages {
    private record Entry(String sprite, BufferedImage source, int tint, boolean nativeSprite) {}
    private record Alias(String hash, boolean tintable) {}
    private final Map<String, Alias> aliases = new TreeMap<>();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private int whiteTint;

    // 菜单复用原图的像素，但必须隔离精灵名；否则 items 图集会抢占世界方块同名精灵。
    static String nativeSpriteId(String resource) {
        return "dialogmenu:native/" + (resource.contains(":") ? resource : "minecraft:" + resource).replace(':', '/');
    }

    private static String spriteId(Entry entry) {
        return entry.nativeSprite() ? nativeSpriteId(entry.sprite()) : entry.sprite();
    }

    static String sourceKey(BufferedImage image) { return ImageRegistry.PREFIX + "~" + FontPack.hash(image); }

    void prepare(Map<String, ImageRegistry.Entry> images, Collection<Integer> tints, int whiteTint) throws IOException {
        this.whiteTint = whiteTint;
        Map<String, String> nativeSprites = InventoryAssets.nativeSprites();
        for (var image : new TreeMap<>(images).entrySet()) {
            String hash = FontPack.hash(image.getValue().image());
            aliases.put(image.getKey(), new Alias(hash, image.getValue().tintable()));
            for (int tint : image.getValue().tintable() ? tints : List.of(whiteTint)) {
                String nativeSprite = tint == whiteTint ? nativeSprites.get(image.getKey()) : null;
                if (image.getValue().image().getWidth() != image.getValue().image().getHeight())
                    nativeSprite = null;
                if (nativeSprite != null && !hash.equals(FontPack.hash(InventoryAssets.images().get(image.getKey()).image())))
                    nativeSprite = null;
                String sprite = nativeSprite;
                entries.computeIfAbsent(hash + ":" + tint,
                    key -> new Entry(sprite != null ? sprite : "dialogmenu:registered/" + hash + "_" + tint,
                        image.getValue().image(), tint, sprite != null));
            }
        }
    }

    int color(String alias, int argb) {
        if (SymbolImages.source(alias)) return argb;
        Alias entry = aliases.get(ImageRegistry.key(alias));
        return entry != null && entry.tintable() ? argb : argb | 0xFFFFFF;
    }

    Component component(String alias, int tint, int height, int localY, int band) {
        Alias registered = aliases.get(alias);
        Entry entry = registered == null ? null
            : entries.get(registered.hash() + ":" + (registered.tintable() ? tint : whiteTint));
        if (entry == null) throw new IllegalArgumentException("未准备注册材质或颜色：" + alias + "；请执行 /dialogmenu reload all");
        JsonObject object = new JsonObject();
        object.addProperty("type", "object");
        object.addProperty("atlas", "minecraft:items");
        object.addProperty("sprite", spriteId(entry));
        object.addProperty("color", "#beef01");
        int payload = RgbaImages.imagePayload(height, localY, band);
        object.addProperty("shadow_color", entry.nativeSprite()
            ? (payload & 0x0FFFFFFF) | 0xE0000000 | (band == 9 ? 1 << 28 : 0)
            : payload);
        var serializer = GsonComponentSerializer.gson();
        Component result = serializer.deserialize(object.toString());
        if (!serializer.serialize(result).contains("\"sprite\""))
            throw new IllegalStateException("注册材质需要支持object文本的Minecraft/Adventure（26.3）");
        return entry.nativeSprite() ? RgbaOffsets.nativeScope(result) : result;
    }

    static BufferedImage wrap(BufferedImage source, int tint) {
        int width = source.getWidth(), height = source.getHeight();
        if (width < 1 || height < 1 || width > 256 || height > 256 || tint < 0 || tint > 255)
            throw new IllegalArgumentException("Registered source dimensions must be 1..256");
        int cellWidth = Integer.highestOneBit(width + 3) << 1;
        int cellHeight = Integer.highestOneBit(height + 3) << 1;
        BufferedImage image = new BufferedImage(cellWidth, cellHeight, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < cellHeight; y++) for (int x = 0; x < cellWidth; x++)
            image.setRGB(x, y, source.getRGB(Math.clamp(x - 2, 0, width - 1), Math.clamp(y - 2, 0, height - 1)));
        int[][] corners = {{0, 0}, {0, cellHeight - 1}, {cellWidth - 1, cellHeight - 1}, {cellWidth - 1, 0}};
        for (int corner = 0; corner < 4; corner++) {
            int x = corners[corner][0], y = corners[corner][1];
            image.setRGB(x, y, (252 + corner) << 24 | 0x001D5400 | 149 + corner);
            image.setRGB(x == 0 ? 1 : x - 1, y,
                tint << 24 | (width - 1) << 16 | (height - 1) << 8 | 177);
        }
        return image;
    }

    void write(Path pack) throws IOException {
        Path atlas = pack.resolve("assets/minecraft/atlases/items.json");
        if (entries.isEmpty() && !Files.isRegularFile(atlas)) return;
        JsonObject definition = Files.isRegularFile(atlas)
            ? JsonParser.parseString(Files.readString(atlas)).getAsJsonObject() : new JsonObject();
        JsonArray sources = definition.has("sources") ? definition.getAsJsonArray("sources") : new JsonArray();
        Set<String> emitted = new HashSet<>();
        for (Entry entry : entries.values()) emitted.add(spriteId(entry));
        for (var iterator = sources.iterator(); iterator.hasNext();) {
            JsonObject source = iterator.next().getAsJsonObject();
            if (source.has("resource") && source.get("resource").getAsString().startsWith("dialogmenu:registered/")
                || source.has("sprite") && emitted.contains(source.get("sprite").getAsString()))
                iterator.remove();
        }
        for (Entry entry : entries.values()) {
            BuildBudget.checkpoint();
            if (!entry.nativeSprite()) {
                String relative = entry.sprite().substring("dialogmenu:".length());
                Path target = pack.resolve("assets/dialogmenu/textures/" + relative + ".png");
                Files.createDirectories(target.getParent());
                FontPack.writePng(wrap(entry.source(), entry.tint()), target);
            }
            JsonObject source = new JsonObject();
            source.addProperty("type", "single");
            source.addProperty("resource", entry.sprite());
            source.addProperty("sprite", spriteId(entry));
            sources.add(source);
        }
        definition.add("sources", sources);
        Files.createDirectories(atlas.getParent());
        Files.writeString(atlas, new Gson().toJson(definition) + "\n");
        write26_2Fallbacks(pack, definition);
    }
    /** 26.2 中不存在的新版本图标使用内置像素，已有原版图标继续复用客户端材质。 */
    private void write26_2Fallbacks(Path pack, JsonObject definition) throws IOException {
        Set<String> missing = new HashSet<>();
        try (var input = RegisteredImages.class.getResourceAsStream("/inventory/sprites-after-26_2.json")) {
            if (input == null) throw new IOException("Missing 26.2 sprite compatibility list");
            for (var sprite : JsonParser.parseReader(new java.io.InputStreamReader(input,
                java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray()) missing.add(sprite.getAsString());
        }
        Map<String, Entry> fallbacks = new HashMap<>();
        for (Entry entry : entries.values())
            if (entry.nativeSprite() && missing.contains(entry.sprite())) fallbacks.put(spriteId(entry), entry);
        Path overlay = pack.resolve("dialogmenu_26_2/assets/minecraft/atlases/items.json");
        if (fallbacks.isEmpty()) {
            Files.deleteIfExists(overlay);
            return;
        }
        JsonObject compatible = definition.deepCopy();
        for (var source : compatible.getAsJsonArray("sources")) {
            JsonObject row = source.getAsJsonObject();
            if (!row.has("sprite")) continue;
            Entry entry = fallbacks.get(row.get("sprite").getAsString());
            if (entry == null) continue;
            String relative = "inventory_26_2/" + FontPack.hash(entry.source());
            Path png = pack.resolve("assets/dialogmenu/textures/" + relative + ".png");
            Files.createDirectories(png.getParent());
            // nativeScope 的对象使用普通图集像素，不能写入 registered 图片的协议边框。
            FontPack.writePng(entry.source(), png);
            row.addProperty("resource", "dialogmenu:" + relative);
        }
        Files.createDirectories(overlay.getParent());
        Files.writeString(overlay, new Gson().toJson(compatible) + "\n");
    }
}
