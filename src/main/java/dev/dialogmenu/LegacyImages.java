package dev.dialogmenu;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/** Bitmap-only image transport, including complete 256px sources without downsampling. */
final class LegacyImages {
    private final Map<Integer, BufferedImage> cells = new LinkedHashMap<>();
    private final Map<String, List<Integer>> glyphs = new LinkedHashMap<>();

    void prepare(String key, BufferedImage image, int tint) {
        if (glyphs.containsKey(key)) return;
        if (image.getWidth() < 1 || image.getHeight() < 1 || image.getWidth() > 256 || image.getHeight() > 256)
            throw new IllegalArgumentException("Registered source dimensions must be 1..256");
        List<Integer> codes = new ArrayList<>();
        for (int y = 0; y < image.getHeight(); y += 128) for (int x = 0; x < image.getWidth(); x += 128) {
            int w = Math.min(128, image.getWidth() - x), h = Math.min(128, image.getHeight() - y);
            int size = Math.max(w, h) + 6;
            BufferedImage cell = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            // Preserve neighboring source pixels when a scaled seam falls on a texel boundary.
            for (int py = 0; py < size; py++) for (int px = 0; px < size; px++)
                cell.setRGB(px, py, image.getRGB(Math.clamp(x + px - 3, 0, image.getWidth() - 1),
                    Math.clamp(y + py - 3, 0, image.getHeight() - 1)));
            int[][] corners = {{0, 0}, {0, size - 1}, {size - 1, size - 1}, {size - 1, 0}};
            for (int i = 0; i < 4; i++) {
                int px = corners[i][0], py = corners[i][1], step = px == 0 ? 1 : -1;
                cell.setRGB(px, py, (size - 1) << 24 | 0x001D5800 | 149 + i);
                cell.setRGB(px + step, py, tint << 24 | (image.getWidth() - 1) << 16
                    | (image.getHeight() - 1) << 8 | 177);
                cell.setRGB(px + 2 * step, py, h << 24 | x << 16 | y << 8 | w);
            }
            int code = 0xF2000 + cells.size();
            cells.put(code, cell);
            codes.add(code);
        }
        glyphs.put(key, List.copyOf(codes));
    }

    Component component(String key, int height, int localY, int band) {
        List<Integer> codes = glyphs.get(key);
        if (codes == null) throw new IllegalArgumentException("Unprepared bitmap image: " + key);
        JsonArray parts = new JsonArray();
        for (int code : codes) {
            JsonObject glyph = new JsonObject();
            glyph.addProperty("text", Character.toString(code));
            glyph.addProperty("font", "dialogmenu:registered_bitmap");
            glyph.addProperty("color", "#beef01");
            glyph.addProperty("shadow_color", RgbaImages.imagePayload(height, localY, band));
            parts.add(glyph);
            JsonObject reset = new JsonObject();
            reset.addProperty("text", SpriteCanvas.move(-2));
            reset.addProperty("font", "dialogmenu:space");
            parts.add(reset);
        }
        // Registered-image callers account for the original object glyph's eight-pixel advance.
        JsonObject advance = new JsonObject();
        advance.addProperty("text", SpriteCanvas.move(8));
        advance.addProperty("font", "dialogmenu:space");
        parts.add(advance);
        JsonObject result = new JsonObject();
        result.addProperty("text", "");
        result.add("extra", parts);
        return GsonComponentSerializer.gson().deserialize(result.toString());
    }

    void write(Path pack) throws IOException { BitmapAtlas.write(pack, "registered_bitmap", cells); }
}
