package dev.dialogmenu;

import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Original pixels in bitmap glyphs; each cell fits the vanilla 256px font texture. */
final class BitmapAtlas {
    private record Size(int width, int height) {}

    static BufferedImage originalPixels(BufferedImage image) {
        if (image.getColorModel().getColorSpace().getType() != java.awt.color.ColorSpace.TYPE_GRAY) return image;
        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var raster = image.getRaster();
        int grayShift = Math.max(0, raster.getSampleModel().getSampleSize(0) - 8);
        int alphaBand = image.getColorModel().hasAlpha() ? raster.getNumBands() - 1 : -1;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int gray = raster.getSample(x, y, 0) >> grayShift;
            int alpha = alphaBand < 0 ? 255 : raster.getSample(x, y, alphaBand)
                >> Math.max(0, raster.getSampleModel().getSampleSize(alphaBand) - 8);
            result.setRGB(x, y, alpha << 24 | gray << 16 | gray << 8 | gray);
        }
        return result;
    }

    static int advance(BufferedImage image) {
        int right = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            if ((image.getRGB(x, y) >>> 24) != 0) right = Math.max(right, x + 1);
        return (int) (right / (float) image.getHeight() + 0.5f) + 1;
    }

    static void write(Path pack, String font, Map<Integer, BufferedImage> images) throws IOException {
        Path base = pack.resolve("assets/dialogmenu");
        Map<Size, List<Map.Entry<Integer, BufferedImage>>> groups = new LinkedHashMap<>();
        for (var entry : new TreeMap<>(images).entrySet()) {
            BufferedImage image = entry.getValue();
            if (image.getWidth() > 256 || image.getHeight() > 256)
                throw new IOException("Bitmap carrier exceeds the vanilla font cell: " + font);
            groups.computeIfAbsent(new Size(image.getWidth(), image.getHeight()), ignored -> new ArrayList<>()).add(entry);
        }
        List<Object> providers = new ArrayList<>();
        int sequence = 0;
        for (var group : groups.entrySet()) {
            int w = group.getKey().width(), h = group.getKey().height();
            var cells = group.getValue();
            for (int start = 0; start < cells.size(); start += 256) {
                var chunk = cells.subList(start, Math.min(start + 256, cells.size()));
                int columns = Math.min(16, chunk.size()), rows = (chunk.size() + columns - 1) / columns;
                BufferedImage atlas = new BufferedImage(columns * w, rows * h, BufferedImage.TYPE_INT_ARGB);
                List<String> chars = new ArrayList<>();
                for (int row = 0; row < rows; row++) {
                    StringBuilder line = new StringBuilder();
                    for (int column = 0; column < columns; column++) {
                        int index = row * columns + column;
                        if (index >= chunk.size()) { line.appendCodePoint(0); continue; }
                        var cell = chunk.get(index);
                        atlas.setRGB(column * w, row * h, w, h,
                            cell.getValue().getRGB(0, 0, w, h, null, 0, w), 0, w);
                        line.appendCodePoint(cell.getKey());
                    }
                    chars.add(line.toString());
                }
                String texture = "generated/" + font + "_" + sequence++ + ".png";
                FontPack.writePng(atlas, base.resolve("textures/" + texture));
                providers.add(FontPack.bitmap(texture, 1, 0, chars));
            }
        }
        Files.createDirectories(base.resolve("font"));
        Files.writeString(base.resolve("font/" + font + ".json"),
            new Gson().toJson(Map.of("providers", providers)) + "\n");
    }
}
