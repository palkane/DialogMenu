package dev.dialogmenu;

import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.*;
import static dev.dialogmenu.MenuModel.*;

/** 共享源 PNG；RGB 携带 9 位宽、9 位高和 5 位有符号行内 Y。 */
final class RgbaImages {
    record Glyph(int code, int advance) {}
    record Tile(int x, int y, int size) {}
    private record Entry(Glyph glyph, String texture, BufferedImage image, int top) {}
    private record Hit(int top, int size) {}
    private final Map<String, Entry> images = new LinkedHashMap<>();
    private final Map<Hit, Integer> hits = new LinkedHashMap<>();
    private final Map<BufferedImage, String> hashes = new WeakHashMap<>();

    static boolean eligible(Sprite sprite) {
        return sprite.texture() == null && sprite.original() != null
            && sprite.original().getWidth() <= 254 && sprite.original().getHeight() <= 254
            && sprite.width() <= 512 && sprite.height() <= 512;
    }

    static int payload(int width, int height, int localY) {
        if (width < 1 || width > 512 || height < 1 || height > 512 || localY < -8 || localY > 8)
            throw new IllegalArgumentException("Shader图片须宽高1..512、行内Y=-8..8");
        return 0x800000 | (width - 1) << 14 | (height - 1) << 5 | localY & 31;
    }

    private String key(Sprite sprite, int tint) {
        return hashes.computeIfAbsent(sprite.original(), FontPack::hash) + ":" + wholeTop(sprite.y()) + ":" + tint;
    }
    private static int wholeTop(int top) { return top - top % CanvasRows.HEIGHT; }

    void prepare(Sprite sprite, boolean interactive, int tint) {
        images.computeIfAbsent(key(sprite, tint), key -> {
            BufferedImage image = wrap(sprite.original(), tint);
            return new Entry(new Glyph(0xE000 + images.size(), 2),
                "generated/rgba_image_" + hashes.get(sprite.original()) + "_" + tint + ".png",
                image, wholeTop(sprite.y()));
        });
        if (interactive) for (Tile tile : tiles(sprite.width(), sprite.height()))
            hits.computeIfAbsent(new Hit(sprite.y() + tile.y(), tile.size()), key -> 0xF0000 + hits.size());
    }

    Glyph glyph(Sprite sprite, int tint) {
        Entry entry = images.get(key(sprite, tint));
        if (entry == null) throw new IllegalArgumentException("图片已变化，请重新生成资源包");
        return entry.glyph();
    }

    Component hit(int top, int size, ClickEvent click, HoverEvent<Component> hover) {
        Hit hit = new Hit(top, size);
        Integer code = hits.get(hit);
        if (code == null) throw new IllegalArgumentException("图片命中尺寸已变化，请重新生成资源包");
        return Component.text(Character.toString(code)).font(Key.key("dialogmenu", "image_hit_all"))
            .clickEvent(click).hoverEvent(hover);
    }

    // 正方形的原生命中边界精确；用欧几里得分块覆盖非正方形图片。
    static List<Tile> tiles(int width, int height) {
        List<Tile> result = new ArrayList<>();
        tile(result, 0, 0, width, height);
        return result;
    }
    private static void tile(List<Tile> result, int x, int y, int width, int height) {
        if (width == 0 || height == 0) return;
        int size = Math.min(256, Math.min(width, height));
        for (int py = 0; py + size <= height; py += size)
            for (int px = 0; px + size <= width; px += size) result.add(new Tile(x + px, y + py, size));
        int usedWidth = width / size * size, usedHeight = height / size * size;
        tile(result, x + usedWidth, y, width - usedWidth, usedHeight);
        tile(result, x, y + usedHeight, width, height - usedHeight);
    }

    static boolean exactHits(Sprite sprite) {
        for (Tile tile : tiles(sprite.width(), sprite.height()))
            if (sprite.y() + tile.y() + tile.size() < 7) return false;
        return true;
    }

    static BufferedImage wrap(BufferedImage source) { return wrap(source, 0); }
    static BufferedImage wrap(BufferedImage source, int tint) {
        int width = Math.max(source.getWidth(), source.getHeight()) + 2, height = width;
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        result.setRGB(1, 1, source.getWidth(), source.getHeight(),
            source.getRGB(0, 0, source.getWidth(), source.getHeight(), null, 0, source.getWidth()),
            0, source.getWidth());
        int[][] corners = {{0, 0}, {0, height - 1}, {width - 1, height - 1}, {width - 1, 0}};
        for (int i = 0; i < corners.length; i++) {
            int x = corners[i][0], y = corners[i][1];
            result.setRGB(x, y, (width - 1) << 24 | 0x001D5300 | 149 + i);
            result.setRGB(x == 0 ? 1 : x - 1, y,
                tint << 24 | 0x000000B1 | source.getWidth() << 16 | source.getHeight() << 8);
        }
        return result;
    }

    void write(Path pack) throws IOException {
        Path base = pack.resolve("assets/dialogmenu");
        List<Object> providers = new ArrayList<>();
        Map<Integer, Map<String, List<Entry>>> groups = new LinkedHashMap<>();
        for (Entry entry : images.values()) {
            BuildBudget.checkpoint();
            groups.computeIfAbsent(entry.image().getWidth(), key -> new LinkedHashMap<>())
                .computeIfAbsent(entry.texture(), key -> new ArrayList<>()).add(entry);
        }
        int sequence = 0;
        for (var group : groups.entrySet()) {
            int size = group.getKey();
            List<List<Entry>> cells = new ArrayList<>(group.getValue().values());
            // 每张图集最多16×16格，单格不超过256px，整图不超过4096px。
            for (int start = 0; start < cells.size(); start += 256) {
                BuildBudget.checkpoint();
                var chunk = cells.subList(start, Math.min(start + 256, cells.size()));
                int columns = Math.min(16, chunk.size()), rows = (chunk.size() + columns - 1) / columns;
                BufferedImage atlas = new BufferedImage(columns * size, rows * size, BufferedImage.TYPE_INT_ARGB);
                Set<Integer> tops = new TreeSet<>();
                for (int i = 0; i < chunk.size(); i++) {
                    BufferedImage image = chunk.get(i).getFirst().image();
                    atlas.setRGB(i % columns * size, i / columns * size, size, size,
                        image.getRGB(0, 0, size, size, null, 0, size), 0, size);
                    for (Entry entry : chunk.get(i)) tops.add(entry.top());
                }
                String texture = "generated/rgba_image_atlas_" + sequence++ + ".png";
                FontPack.writePng(atlas, base.resolve("textures/" + texture));
                for (int top : tops) {
                    List<String> chars = new ArrayList<>();
                    for (int row = 0; row < rows; row++) {
                        StringBuilder line = new StringBuilder();
                        for (int column = 0; column < columns; column++) {
                            int index = row * columns + column, code = 0;
                            if (index < chunk.size())
                                for (Entry entry : chunk.get(index))
                                    if (entry.top() == top) code = entry.glyph().code();
                            line.appendCodePoint(code);
                        }
                        chars.add(line.toString());
                    }
                    providers.add(FontPack.bitmap(texture, 1, -top, chars));
                }
            }
        }
        Gson json = new Gson();
        Files.writeString(base.resolve("font/images_rgba.json"), json.toJson(Map.of("providers", providers)) + "\n");
        if (!hits.isEmpty()) {
            BufferedImage pixel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            pixel.setRGB(0, 0, 0x01FFFFFF);
            FontPack.writePng(pixel, base.resolve("textures/generated/image_hit.png"));
            List<Object> hitProviders = new ArrayList<>();
            for (var entry : hits.entrySet()) {
                BuildBudget.checkpoint();
                Hit hit = entry.getKey();
                hitProviders.add(FontPack.bitmap("generated/image_hit.png",
                    hit.size(), 7 - hit.top(), List.of(Character.toString(entry.getValue()))));
            }
            Files.writeString(base.resolve("font/image_hit_all.json"),
                json.toJson(Map.of("providers", hitProviders)) + "\n");
        }
    }
}
