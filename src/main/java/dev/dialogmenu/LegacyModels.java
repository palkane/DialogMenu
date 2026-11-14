package dev.dialogmenu;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Bitmap sprites carry original texture frames; the model table still supplies every face. */
final class LegacyModels {
    record Glyph(int code, int advance, int descriptor) {}
    private record Frame(int index, int ticks) {}
    private record Descriptor(int width, int height, int columns, int rows, List<Frame> frames, boolean interpolate) {}
    private final Map<String, Glyph> glyphs = new TreeMap<>();
    private final Map<Integer, BufferedImage> cells = new TreeMap<>();
    private final List<Descriptor> descriptors = new ArrayList<>();

    LegacyModels(Set<String> sprites, Map<String, byte[]> files) throws IOException {
        for (String sprite : new TreeSet<>(sprites)) {
            String path = "assets/" + sprite.replace(":", "/textures/") + ".png";
            byte[] content = files.get(path);
            if (content == null) throw new IOException("Missing bitmap model texture: " + sprite);
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(content));
            if (source == null) throw new IOException("Invalid model PNG: " + sprite);
            source = BitmapAtlas.originalPixels(source);
            JsonObject animation = null;
            if (files.containsKey(path + ".mcmeta")) {
                var meta = JsonParser.parseString(new String(files.get(path + ".mcmeta"),
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                if (meta.has("animation")) animation = meta.getAsJsonObject("animation");
            }
            int w = source.getWidth(), h = source.getHeight();
            if (animation != null) {
                if (!animation.has("width") && !animation.has("height")) w = h = Math.min(w, h);
                else {
                    if (animation.has("width")) w = animation.get("width").getAsInt();
                    if (animation.has("height")) h = animation.get("height").getAsInt();
                }
            }
            if (w <= 0 || h <= 0 || source.getWidth() % w != 0 || source.getHeight() % h != 0)
                throw new IOException("Invalid animation frame dimensions: " + sprite);
            int count = source.getWidth() / w * (source.getHeight() / h);
            if (w > 256 || h > 256) throw new IOException("Bitmap model frame exceeds 256px: " + sprite);
            int columns = Math.min(256 / w, Math.max(1, (int) Math.ceil(Math.sqrt(count * (double) h / w))));
            int rows = (count + columns - 1) / columns;
            if (rows * h > 256)
                throw new IOException("Animated bitmap model sheet exceeds the vanilla 256px font cell: " + sprite);
            List<Frame> frames = new ArrayList<>();
            int ticks = animation != null && animation.has("frametime") ? animation.get("frametime").getAsInt() : 1;
            if (animation != null && animation.has("frames")) {
                for (var frame : animation.getAsJsonArray("frames"))
                    frames.add(frame.isJsonObject()
                        ? new Frame(frame.getAsJsonObject().get("index").getAsInt(),
                            frame.getAsJsonObject().has("time") ? frame.getAsJsonObject().get("time").getAsInt() : ticks)
                        : new Frame(frame.getAsInt(), ticks));
            } else for (int i = 0; i < count; i++) frames.add(new Frame(i, ticks));
            if (frames.isEmpty() || frames.stream().anyMatch(f -> f.index() < 0 || f.index() >= count || f.ticks() <= 0))
                throw new IOException("Invalid animation sequence: " + sprite);
            Descriptor descriptor = new Descriptor(w, h, columns, rows, List.copyOf(frames),
                animation != null && animation.has("interpolate") && animation.get("interpolate").getAsBoolean());
            int index = descriptors.indexOf(descriptor);
            if (index < 0) { index = descriptors.size(); descriptors.add(descriptor); }
            if (index >= 128) throw new IOException("Bitmap model snapshot exceeds 128 distinct frame schedules");
            // A square one-pixel quad keeps the face-address cursor exact at large offsets.
            int side = Math.max(columns * w, rows * h);
            BufferedImage sheet = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
            int sourceColumns = source.getWidth() / w;
            for (int i = 0; i < count; i++)
                sheet.setRGB(i % columns * w, i / columns * h, w, h,
                    source.getRGB(i % sourceColumns * w, i / sourceColumns * h, w, h, null, 0, w), 0, w);
            int code = 0xF2000 + cells.size();
            cells.put(code, sheet);
            glyphs.put(sprite, new Glyph(code, BitmapAtlas.advance(sheet), index));
        }
    }

    Glyph glyph(String sprite) {
        Glyph result = glyphs.get(sprite);
        if (result == null) throw new IllegalArgumentException("Unprepared bitmap model sprite: " + sprite);
        return result;
    }

    void write(Path pack) throws IOException {
        BitmapAtlas.write(pack, "model_bitmap", cells);
        StringBuilder shader = new StringBuilder("vec4 dialogmenu_bitmap_size(int id) {\n");
        for (int i = 0; i < descriptors.size(); i++) {
            var d = descriptors.get(i);
            shader.append("if (id == ").append(i).append(") return vec4(")
                .append(d.width()).append(".0,").append(d.height()).append(".0,")
                .append(Math.max(d.width() * d.columns(), d.height() * d.rows())).append(".0,")
                .append(d.columns()).append(".0);\n");
        }
        shader.append("return vec4(1.0);\n}\nvec3 dialogmenu_bitmap_animation(int id) {\n");
        for (int i = 0; i < descriptors.size(); i++) {
            var d = descriptors.get(i);
            if (d.frames().size() == 1) {
                int frame = d.frames().getFirst().index();
                shader.append("if (id == ").append(i).append(") return vec3(")
                    .append(frame).append(".0,").append(frame).append(".0,0.0);\n");
                continue;
            }
            long duration = d.frames().stream().mapToLong(Frame::ticks).sum(), end = 0;
            shader.append("if (id == ").append(i).append(") {\nfloat time = mod(GameTime * 24000.0, ")
                .append(duration).append(".0);\n");
            for (int j = 0; j < d.frames().size(); j++) {
                Frame current = d.frames().get(j), next = d.frames().get((j + 1) % d.frames().size());
                shader.append("if (time < ").append(end + current.ticks()).append(".0) return vec3(")
                    .append(current.index()).append(".0,").append(next.index()).append(".0,");
                if (d.interpolate()) shader.append("(time - ").append(end).append(".0) / ").append(current.ticks()).append(".0");
                else shader.append("0.0");
                shader.append(");\n");
                end += current.ticks();
            }
            shader.append("}\n");
        }
        shader.append("return vec3(0.0);\n}\n");
        Path path = pack.resolve("assets/dialogmenu/shaders/include/model_bitmap_data.glsl");
        Files.createDirectories(path.getParent());
        Files.writeString(path, shader);
    }
}
