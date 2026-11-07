package dev.dialogmenu;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import com.google.gson.Gson;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

/** 原生命中几何保留在目标 Y；alpha=1 的字格由原版 text.fsh 丢弃。 */
final class RgbaHits {
    private record Location(int top, TextStyle style) {}
    private record Cell(int height, int width) {
        String texture() { return "generated/hit_" + height + "_" + width + ".png"; }
    }
    private record Hit(Location location, Cell cell) {}
    private final Set<Location> locations = new LinkedHashSet<>();
    private final Map<TextStyle, Map<Integer, Cell>> byFont = new HashMap<>();
    private final Map<String, Cell> cells = new LinkedHashMap<>();
    private final Map<Hit, Integer> glyphs = new LinkedHashMap<>();

    void prepare(int top, TextStyle style) {
        if (top + style.height() < 7)
            throw new IllegalArgumentException("文字命中超出原生基线范围，请将该小字号元素下移至第7像素后");
        locations.add(new Location(top, style));
    }

    void source(TextStyle style, List<FontPack.TextAtlas> atlases) {
        Map<Integer, Cell> characters = byFont.computeIfAbsent(style, key -> new HashMap<>());
        for (var atlas : atlases) {
            int columns = atlas.chars().getFirst().codePointCount(0, atlas.chars().getFirst().length());
            int width = atlas.image().getWidth() / columns, height = atlas.sourceHeight();
            for (int row = 0; row < atlas.chars().size(); row++) {
                BuildBudget.checkpoint();
                int[] codes = atlas.chars().get(row).codePoints().toArray();
                for (int column = 0; column < codes.length; column++) {
                    if (codes[column] == 0) continue;
                    int used = 0;
                    for (int x = 0; x < width; x++) for (int y = 0; y < height; y++)
                        if (atlas.image().getRGB(column * width + x, row * height + y) >>> 24 != 0)
                            used = Math.max(used, x + 1);
                    int hitWidth = Math.max(1, used);
                    Cell cell = cells.computeIfAbsent(height + "/" + hitWidth, key -> new Cell(height, hitWidth));
                    characters.put(codes[column], cell);
                }
            }
        }
    }

    void finish() {
        for (Location location : locations) {
            BuildBudget.checkpoint();
            for (Cell cell : cells.values())
                if (byFont.get(location.style()).containsValue(cell))
                    glyphs.computeIfAbsent(new Hit(location, cell), key -> 0xF0000 + glyphs.size());
        }
    }

    Component text(int top, int size, Component value, FontPack fonts) {
        return text(top, new TextStyle(size, 1, 1), value, fonts);
    }
    Component text(int top, TextStyle style, Component value, FontPack fonts) {
        Location location = new Location(top, style);
        if (!locations.contains(location)) throw new IllegalArgumentException("RGBA hit position changed; rebuild resources");
        var builder = Component.text();
        for (var glyph : MenuText.glyphs(value)) {
            int advance = fonts.glyphWidth(glyph, style);
            if (glyph.code() == ' ') {
                builder.append(Component.text(SpriteCanvas.move(advance)).font(SpriteCanvas.SPACE));
                continue;
            }
            Cell cell = byFont.get(style).get(glyph.code());
            if (cell == null) throw new IllegalArgumentException("RGBA hit character not prepared");
            builder.append(Component.text(Character.toString(glyphs.get(new Hit(location, cell)))));
            int proxyAdvance = (int) (.5 + cell.width() * (style.height() / (float) cell.height())) + 1;
            if (advance != proxyAdvance)
                builder.append(Component.text(SpriteCanvas.move(advance - proxyAdvance)).font(SpriteCanvas.SPACE));
        }
        return builder.build().font(Key.key("dialogmenu", "text_hit_all"))
            .decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false);
    }

    void write(Path pack) throws IOException {
        Path base = pack.resolve("assets/dialogmenu");
        for (Cell cell : cells.values()) {
            BuildBudget.checkpoint();
            BufferedImage image = new BufferedImage(cell.width(), cell.height(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < cell.height(); y++)
                for (int x = 0; x < cell.width(); x++) image.setRGB(x, y, 0x01FFFFFF);
            FontPack.writePng(image, base.resolve("textures/" + cell.texture()));
        }
        var json = new Gson();
        List<Object> providers = new ArrayList<>();
        for (var entry : glyphs.entrySet()) {
            BuildBudget.checkpoint();
            Location location = entry.getKey().location();
            Cell cell = entry.getKey().cell();
            providers.add(FontPack.bitmap(cell.texture(), location.style().height(), 7 - location.top(),
                List.of(Character.toString(entry.getValue()))));
        }
        Files.writeString(base.resolve("font/text_hit_all.json"),
            json.toJson(Map.of("providers", providers)) + "\n");
    }
}
