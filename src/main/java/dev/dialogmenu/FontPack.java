package dev.dialogmenu;

import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.IntConsumer;
import javax.imageio.ImageIO;
import static dev.dialogmenu.MenuModel.*;

/** 按需生成 RGBA 字形和图形回退资源。 */
final class FontPack {
    record Glyph(int code, int top, int height, int advance, String texture, BufferedImage image,
                 TextureRegion textureRegion) {
        Glyph(int code, int top, int height, int advance, String texture, BufferedImage image) {
            this(code, top, height, advance, texture, image, null);
        }
    }
    record TextAtlas(int sourceHeight, List<String> chars, String texture, BufferedImage image) {}
    private record TextCell(int height, int width) {}
    private final Map<String, Glyph> sprites = new LinkedHashMap<>();
    private final Map<Integer, int[]> metrics = new HashMap<>();
    private final Map<String, List<TextAtlas>> text = new TreeMap<>();
    private record TextFont(TextStyle style, Set<Integer> characters) {}
    private record StretchedGlyph(BufferedImage image, int advance) {}
    private final Map<String, TextFont> textFonts = new TreeMap<>();
    private final Map<TextStyle, Map<Integer, StretchedGlyph>> stretchedGlyphs = new HashMap<>();
    private final RgbaOffsets rgba = new RgbaOffsets();
    private final RgbaHits rgbaHits = new RgbaHits();
    private final RgbaImages rgbaImages = new RgbaImages();
    private final Map<BufferedImage, String> imageKeys = new WeakHashMap<>();
    private final Gson json = new Gson();

    static FontPack create(Collection<LayoutStore.Document> docs, MenuRenderer renderer, Context context,
                           Collection<Integer> widths, int maxPixels) throws IOException {
        return create(docs, renderer, context, widths, maxPixels, "");
    }
    static FontPack create(Collection<LayoutStore.Document> docs, MenuRenderer renderer, Context context,
                           Collection<Integer> widths, int maxPixels, String extraCharacters) throws IOException {
        return create(docs, renderer, context, widths, maxPixels, extraCharacters, List.of(1.0));
    }
    static FontPack create(Collection<LayoutStore.Document> docs, MenuRenderer renderer, Context context,
                           Collection<Integer> widths, int maxPixels, String extraCharacters,
                           Collection<Double> textScales) throws IOException {
        return create(docs, renderer, context, widths, maxPixels, extraCharacters, textScales, List.of(context.canvasScale()));
    }
    static FontPack create(Collection<LayoutStore.Document> docs, MenuRenderer renderer, Context context,
                           Collection<Integer> widths, int maxPixels, String extraCharacters,
                           Collection<Double> textScales, Collection<Double> canvasScales) throws IOException {
        return create(docs, renderer, context, widths, maxPixels, extraCharacters, textScales, canvasScales, List.of());
    }
    static FontPack create(Collection<LayoutStore.Document> docs, MenuRenderer renderer, Context context,
                           Collection<Integer> widths, int maxPixels, String extraCharacters,
                           Collection<Double> textScales, Collection<Double> canvasScales,
                           Collection<Integer> colors) throws IOException {
        return create(docs, renderer, context, widths, maxPixels, extraCharacters, textScales,
            canvasScales, colors, ignored -> {});
    }
    static FontPack create(Collection<LayoutStore.Document> docs, MenuRenderer renderer, Context context,
                           Collection<Integer> widths, int maxPixels, String extraCharacters,
                           Collection<Double> textScales, Collection<Double> canvasScales,
                           Collection<Integer> colors, IntConsumer progress) throws IOException {
        FontPack pack = new FontPack();
        colors.forEach(pack.rgba::add);
        String commonCharacters = RgbaOffsets.commonCharacters();
        Set<Integer> commonCodes = codes(List.of(commonCharacters));
        Set<String> commonFonts = new HashSet<>();
        Map<String, Set<Integer>> dynamicCodes = new HashMap<>();
        int totalFrames = docs.size() * new HashSet<>(canvasScales).size() * new HashSet<>(widths).size();
        int completedFrames = 0;
        for (var doc : docs.stream().sorted(Comparator.comparing(d -> d.menu().id())).toList())
            for (double canvasScale : new TreeSet<>(canvasScales))
            for (int requestedWidth : new TreeSet<>(widths)) {
                BuildBudget.checkpoint();
                Context active = CanvasScale.context(MenuTemplate.preview(doc, context), canvasScale);
                Rendered frame = renderer.draw(doc.menu(), active, requestedWidth, maxPixels);
                int width = frame.image().getWidth();
                Map<String, Integer> nativeRows = RgbaRows.plan(doc.menu(), frame);
                for (Sprite sprite : frame.sprites()) {
                    BuildBudget.checkpoint();
                    try {
                        Sprite local = RgbaRows.relative(sprite, nativeRows.get(sprite.id()));
                        Node node = doc.menu().nodes().stream().filter(n -> n.id().equals(sprite.id())).findFirst().orElseThrow();
                        if (pack.shaderImage(local, node.interaction() != null)) {
                            pack.rgbaImages.prepare(local, node.interaction() != null,
                                pack.rgba.add(node.argb()));
                            continue;
                        }
                        for (Sprite part : CanvasRows.parts(local)) pack.register(part);
                    }
                    catch (IOException error) { throw new IOException(doc.menu().id()+"/"+width+": "+error.getMessage(), error); }
                }
                for (Node node : frame.textNodes()) {
                    BuildBudget.checkpoint();
                    pack.rgba.prepare(MenuText.parse(active.expand(node.text())), node.argb());
                    for (String value : MenuTemplate.textValues(doc, node, active))
                        pack.rgba.prepare(MenuText.parse(value), node.argb());
                    boolean dynamic = MenuTemplate.dynamicText(node);
                    Set<Integer> characters = codes(MenuTemplate.textValues(doc, node, active));
                    if (dynamic) characters.addAll(dynamicCodes.computeIfAbsent(
                        MenuTemplate.dynamicCharacters(doc, node, extraCharacters), value -> codes(List.of(value))));
                    for (int cp : characters) pack.measure(cp, renderer.assets);
                    for (double scale : textScales) {
                        int size = SpriteCanvas.textSize(doc.menu(), node, width, scale, canvasScale);
                        TextStyle style = new TextStyle(size, node.scaleX(), node.scaleY());
                        pack.prepareStretch(style, characters, renderer.assets);
                        List<net.kyori.adventure.text.Component> lines;
                        try {
                            lines = dynamic ? List.of() : TextFlow.lines(node, MenuText.parse(active.expand(node.text())),
                                width / (double) doc.menu().width(),
                                glyph -> pack.glyphWidth(glyph, style));
                        } catch (IllegalArgumentException error) {
                            // 可选倍率可能放不下当前文字，玩家选择时仍会校验。
                            continue;
                        }
                        int count = dynamic ? (node.maxLines() > 0 ? node.maxLines()
                            : frame.image().getHeight() / style.stride() + 1) : lines.size();
                        boolean registeredShaderCharacters = false;
                        for (int line = 0; line < count; line++) {
                            int top = SpriteCanvas.textTop(doc.menu(), node, width, line, style);
                            if (top >= 0 && top + style.height() <= frame.image().getHeight()) {
                                String name = style.name();
                                if (node.interaction() != null)
                                    pack.rgbaHits.prepare(top - nativeRows.get(node.id()) * 9, style);
                                TextFont font = pack.textFonts.computeIfAbsent(name,
                                    key -> new TextFont(style, new TreeSet<>()));
                                if (dynamic) {
                                    if (!registeredShaderCharacters) font.characters().addAll(characters);
                                    registeredShaderCharacters = true;
                                    if (commonFonts.add(name)) {
                                        for (int cp : commonCodes) pack.measure(cp, renderer.assets);
                                        pack.prepareStretch(style, commonCodes, renderer.assets);
                                        font.characters().addAll(commonCodes);
                                    }
                                } else font.characters().addAll(dynamic ? characters : codes(lines.get(line)));
                            }
                        }
                    }
                }
                completedFrames++;
                progress.accept(totalFrames == 0 ? 55 : completedFrames * 55 / totalFrames);
            }
        Map<TextCell, Set<Integer>> used = new LinkedHashMap<>();
        Map<String, List<TextCell>> cells = new HashMap<>();
        int fontCount = pack.textFonts.size();
        int completedFonts = 0;
        for (var entry : pack.textFonts.entrySet()) {
            BuildBudget.checkpoint();
            if (!entry.getValue().style().plain()) continue;
            Map<Integer, Set<Integer>> byHeight = new TreeMap<>();
            for (int cp : entry.getValue().characters())
                byHeight.computeIfAbsent(pack.metrics.get(cp)[1], h -> new TreeSet<>()).add(cp);
            List<TextCell> layout = new ArrayList<>();
            for (var group : byHeight.entrySet()) {
                int width = group.getValue().stream().mapToInt(cp -> renderer.assets.glyph(cp).width()).max().orElseThrow();
                TextCell cell = new TextCell(group.getKey(), width);
                used.computeIfAbsent(cell, key -> new TreeSet<>()).addAll(group.getValue());
                layout.add(cell);
            }
            cells.put(entry.getKey(), layout);
            completedFonts++;
            progress.accept(55 + (fontCount == 0 ? 10 : completedFonts * 10 / fontCount));
        }
        Map<TextCell, List<TextAtlas>> shared = new HashMap<>();
        int totalCells = used.size(), completedCells = 0;
        for (var entry : used.entrySet()) {
            shared.put(entry.getKey(), pack.characters(entry.getValue(), renderer.assets));
            completedCells++;
            progress.accept(65 + (totalCells == 0 ? 10 : completedCells * 10 / totalCells));
        }
        completedFonts = 0;
        for (var entry : pack.textFonts.entrySet()) {
            BuildBudget.checkpoint();
            TextFont font = entry.getValue();
            if (!font.style().plain()) {
                List<TextAtlas> atlases = pack.stretchedCharacters(font);
                pack.text.put(entry.getKey(), atlases);
                pack.rgbaHits.source(font.style(), atlases);
                completedFonts++;
                progress.accept(75 + (fontCount == 0 ? 10 : completedFonts * 10 / fontCount));
                continue;
            }
            List<TextAtlas> atlases = new ArrayList<>();
            for (TextCell cell : cells.get(entry.getKey())) for (TextAtlas atlas : shared.get(cell)) {
                List<String> chars = new ArrayList<>();
                boolean visible = false;
                for (String row : atlas.chars()) {
                    StringBuilder masked = new StringBuilder();
                    for (int cp : row.codePoints().toArray()) {
                        boolean present = cp != 0 && font.characters().contains(cp);
                        masked.appendCodePoint(present ? cp : 0);
                        visible |= present;
                    }
                    chars.add(masked.toString());
                }
                if (visible) atlases.add(new TextAtlas(atlas.sourceHeight(), List.copyOf(chars),
                    atlas.texture(), atlas.image()));
            }
            pack.text.put(entry.getKey(), List.copyOf(atlases));
            pack.rgbaHits.source(font.style(), atlases);
            completedFonts++;
            progress.accept(75 + (fontCount == 0 ? 10 : completedFonts * 10 / fontCount));
        }
        pack.rgbaHits.finish();
        progress.accept(85);
        return pack;
    }
    private static Set<Integer> codes(Collection<String> values) {
        Set<Integer> result = new TreeSet<>();
        for (String value : values) result.addAll(codes(MenuText.parse(value)));
        return result;
    }
    void validateDynamic(LayoutStore.Document doc, Context context, int width, String extra) throws IOException {
        for (Node node : doc.menu().nodes()) if (node.type().equals("text") && MenuTemplate.dynamicText(node)) {
            int size = SpriteCanvas.textSize(doc.menu(), node, width, context.textScale(), context.canvasScale());
            Set<Integer> required = codes(List.of(RgbaOffsets.commonCharacters(),
                MenuTemplate.dynamicCharacters(doc, node, extra)));
            var key = textFont(new TextStyle(size, node.scaleX(), node.scaleY()));
            if (!textFonts.get(key.value()).characters().containsAll(required))
                throw new IllegalArgumentException("动态文字字符范围发生变化，请执行 /dialogmenu reload all");
        }
    }
    private static Set<Integer> codes(net.kyori.adventure.text.Component value) {
        Set<Integer> result = new TreeSet<>();
        for (var glyph : MenuText.glyphs(value))
            if (glyph.code() != ' ' && glyph.code() != '\n') result.add(glyph.code());
        return result;
    }
    private String key(BufferedImage image) {
        return imageKeys.computeIfAbsent(image, FontPack::hash);
    }
    static String hash(BufferedImage image) {
        try {
            ByteBuffer bytes = ByteBuffer.allocate(8 + image.getWidth() * image.getHeight() * 4);
            bytes.putInt(image.getWidth()).putInt(image.getHeight());
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
                bytes.putInt(image.getRGB(x, y));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private void register(Sprite sprite) throws IOException {
        if (sprite.texture() != null) {
            String key = spriteKey(sprite);
            if (!sprites.containsKey(key)) {
                int height = sprite.image().getHeight();
                BufferedImage image = sprite.original();
                int advance = bitmapAdvance(image, 0, 0, image.getWidth(), image.getHeight(), height);
                sprites.put(key, new Glyph(0xF0000 + sprites.size(), sprite.y(), height,
                    advance, sprite.texture(), null, sprite.textureRegion()));
            }
            return;
        }
        BufferedImage source = sprite.image();
        String hash = key(source), key = hash + ":" + sprite.y();
        if (sprites.containsKey(key)) return;
        int minimumHeight = Math.max(1, 7 - sprite.y());
        if (source.getHeight() < minimumHeight) {
            BufferedImage padded = new BufferedImage(source.getWidth(), minimumHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D padding = padded.createGraphics();
            padding.drawImage(source, 0, 0, null);
            padding.dispose();
            source = padded;
        }
        int divisor = 1;
        while (source.getWidth() / divisor > 256 || source.getHeight() / divisor > 256
            || source.getWidth() % divisor != 0 || source.getHeight() % divisor != 0) {
            divisor++;
            if (divisor > Math.min(source.getWidth(), source.getHeight()))
                throw new IOException(sprite.id() + " 单图无法在256内保持原比例，请调整该元素尺寸");
        }
        BufferedImage image = new BufferedImage(source.getWidth() / divisor, source.getHeight() / divisor,
            BufferedImage.TYPE_INT_ARGB);
        // 整张大图缩到不超过 256 的纹理时，保留细线。
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            long a = 0, r = 0, g = 0, b = 0;
            for (int py = 0; py < divisor; py++) for (int px = 0; px < divisor; px++) {
                int color = source.getRGB(x * divisor + px, y * divisor + py), alpha = color >>> 24;
                a += alpha;
                r += (color >> 16 & 255) * alpha;
                g += (color >> 8 & 255) * alpha;
                b += (color & 255) * alpha;
            }
            if (a > 0) image.setRGB(x, y, (int) ((a + divisor * divisor / 2) / (divisor * divisor)) << 24
                | (int) ((r + a / 2) / a) << 16 | (int) ((g + a / 2) / a) << 8 | (int) ((b + a / 2) / a));
        }
        int advance = bitmapAdvance(image, 0, 0, image.getWidth(), image.getHeight(), source.getHeight());
        sprites.put(key, new Glyph(0xF0000 + sprites.size(), sprite.y(), source.getHeight(), advance,
            "generated/sprite_" + hash + ".png", image));
    }
    Glyph sprite(Sprite sprite) {
        Glyph glyph = sprites.get(spriteKey(sprite));
        if (glyph == null) throw new IllegalArgumentException("图形已变化，请重新生成并接收字体资源包");
        return glyph;
    }
    private String spriteKey(Sprite sprite) {
        return (sprite.texture() == null ? key(sprite.image())
            : sprite.texture() + ":" + sprite.textureRegion() + ":" + sprite.height()) + ":" + sprite.y();
    }
    private void measure(int cp, VanillaAssets assets) throws IOException {
        if (metrics.containsKey(cp)) return;
        var glyph = assets.glyph(cp);
        if (glyph == null) throw new IOException("原版字体缺少字符 U+" + Integer.toHexString(cp));
        int used = 0;
        for (int y = 0; y < glyph.height(); y++) for (int x = 0; x < glyph.width(); x++)
            if (glyph.alpha()[y * glyph.width() + x] != 0) used = Math.max(used, x + 1);
        metrics.put(cp, new int[] {used, glyph.height()});
    }
    private void prepareStretch(TextStyle style, Set<Integer> codes, VanillaAssets assets) throws IOException {
        if (style.plain()) return;
        Map<Integer, StretchedGlyph> prepared = stretchedGlyphs.computeIfAbsent(style, key -> new TreeMap<>());
        for (int cp : codes) {
            BuildBudget.checkpoint();
            if (prepared.containsKey(cp)) continue;
            var glyph = assets.glyph(cp);
            long scaledWidth = Math.round(glyph.width() * style.size() * style.x() / glyph.height());
            if (scaledWidth > 256)
                throw new IllegalArgumentException("拉伸后单字纹理宽度须不超过256");
            int width = Math.max(1, (int) scaledWidth);
            BufferedImage image = new BufferedImage(width, style.height(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < width; x++) {
                int px = Math.min(glyph.width() - 1, (int) ((x + .5) * glyph.width() / width));
                int py = Math.min(glyph.height() - 1, (int) ((y + .5) * glyph.height() / image.getHeight()));
                int alpha = Byte.toUnsignedInt(glyph.alpha()[py * glyph.width() + px]);
                image.setRGB(x, y, alpha << 24 | RgbaOffsets.TEXTURE_RGB);
            }
            prepared.put(cp, new StretchedGlyph(image,
                bitmapAdvance(image, 0, 0, width, image.getHeight(), style.height())));
        }
    }
    private List<TextAtlas> stretchedCharacters(TextFont font) {
        TextStyle style = font.style();
        Map<Integer, StretchedGlyph> glyphs = stretchedGlyphs.get(style);
        Map<Integer, List<Integer>> byWidth = new TreeMap<>();
        for (int cp : font.characters())
            byWidth.computeIfAbsent(glyphs.get(cp).image().getWidth(), key -> new ArrayList<>()).add(cp);
        List<TextAtlas> atlases = new ArrayList<>();
        for (var group : byWidth.entrySet()) {
            int cellWidth = group.getKey(), cellHeight = style.height();
            int columns = Math.min(256 / cellWidth, group.getValue().size());
            int capacity = columns * (256 / cellHeight);
            for (int start = 0; start < group.getValue().size(); start += capacity) {
                BuildBudget.checkpoint();
                List<Integer> chunk = group.getValue().subList(start, Math.min(start + capacity, group.getValue().size()));
                int rows = (chunk.size() + columns - 1) / columns;
                BufferedImage image = new BufferedImage(columns * cellWidth, rows * cellHeight,
                    BufferedImage.TYPE_INT_ARGB);
                List<String> chars = new ArrayList<>();
                for (int row = 0; row < rows; row++) {
                    StringBuilder line = new StringBuilder();
                    for (int column = 0; column < columns; column++) {
                        int i = row * columns + column;
                        if (i >= chunk.size()) { line.append('\0'); continue; }
                        int cp = chunk.get(i);
                        BufferedImage glyph = glyphs.get(cp).image();
                        image.setRGB(column * cellWidth, row * cellHeight, cellWidth, cellHeight,
                            glyph.getRGB(0, 0, cellWidth, cellHeight, null, 0, cellWidth), 0, cellWidth);
                        line.appendCodePoint(cp);
                    }
                    chars.add(line.toString());
                }
                atlases.add(new TextAtlas(cellHeight, List.copyOf(chars),
                    "generated/text_" + cellHeight + "_" + hash(image) + ".png", image));
            }
        }
        return List.copyOf(atlases);
    }
    private List<TextAtlas> characters(Set<Integer> characters, VanillaAssets assets) throws IOException {
        List<TextAtlas> result = new ArrayList<>();
        Map<Integer, List<Integer>> byHeight = new TreeMap<>();
        for (int cp : characters) {
            measure(cp, assets);
            byHeight.computeIfAbsent(metrics.get(cp)[1], h -> new ArrayList<>()).add(cp);
        }
        for (var group : byHeight.entrySet()) {
            int h = group.getKey(), cw = group.getValue().stream().mapToInt(cp -> assets.glyph(cp).width()).max().orElseThrow();
            int columns = Math.min(256 / cw, group.getValue().size()), capacity = columns * (256 / h);
            for (int start = 0; start < group.getValue().size(); start += capacity) {
                List<Integer> chunk = group.getValue().subList(start, Math.min(start + capacity, group.getValue().size()));
                int rowCount = (chunk.size() + columns - 1) / columns;
                BufferedImage image = new BufferedImage(columns * cw, rowCount * h, BufferedImage.TYPE_INT_ARGB);
                List<String> rows = new ArrayList<>();
                for (int y = 0; y < rowCount; y++) {
                    BuildBudget.checkpoint();
                    StringBuilder row = new StringBuilder();
                    for (int x = 0; x < columns; x++) {
                        int index = y * columns + x;
                        if (index >= chunk.size()) { row.append('\0'); continue; }
                        int cp = chunk.get(index);
                        var glyph = assets.glyph(cp);
                        for (int py = 0; py < h; py++) for (int px = 0; px < cw; px++) {
                            int alpha = px < glyph.width()
                                ? Byte.toUnsignedInt(glyph.alpha()[py * glyph.width() + px]) : 0;
                            image.setRGB(x * cw + px, y * h + py, alpha << 24 | RgbaOffsets.TEXTURE_RGB);
                        }
                        row.appendCodePoint(cp);
                    }
                    rows.add(row.toString());
                }
                result.add(new TextAtlas(h, List.copyOf(rows), "generated/text_" + h + "_" + hash(image) + ".png", image));
            }
        }
        return List.copyOf(result);
    }
    private static int bitmapAdvance(BufferedImage image, int ox, int oy, int width, int height, int displayHeight) {
        int used = 0;
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++)
            if (image.getRGB(ox + x, oy + y) >>> 24 != 0) used = Math.max(used, x + 1);
        return (int) (.5 + used * (displayHeight / (float) height)) + 1;
    }
    int textWidth(String value) { return textWidth(MenuText.parse(value), 8); }
    int textWidth(String value, int size) { return textWidth(MenuText.parse(value), size); }
    int textWidth(net.kyori.adventure.text.Component value, int size) {
        return MenuText.glyphs(value).stream().mapToInt(glyph -> glyphWidth(glyph, size)).sum();
    }
    int textWidth(net.kyori.adventure.text.Component value, int size, double stretchX, double stretchY) {
        return textWidth(value, new TextStyle(size, stretchX, stretchY));
    }
    int textWidth(net.kyori.adventure.text.Component value, TextStyle style) {
        return MenuText.glyphs(value).stream().mapToInt(glyph -> glyphWidth(glyph, style)).sum();
    }
    int glyphWidth(MenuText.Glyph glyph, int size) { return glyphWidth(glyph, new TextStyle(size, 1, 1)); }
    int glyphWidth(MenuText.Glyph glyph, int size, double stretchX, double stretchY) {
        return glyphWidth(glyph, new TextStyle(size, stretchX, stretchY));
    }
    int glyphWidth(MenuText.Glyph glyph, TextStyle style) {
        if (glyph.code() == 32) return style.space() + (glyph.bold() ? 1 : 0);
        if (!style.plain()) {
            StretchedGlyph prepared = stretchedGlyphs.getOrDefault(style, Map.of()).get(glyph.code());
            if (prepared == null) throw new IllegalArgumentException("文字拉伸或字符已变化，请执行 /dialogmenu reload all");
            return prepared.advance() + (glyph.bold() ? 1 : 0);
        }
        int[] metric = metrics.get(glyph.code());
        if (metric == null)
            throw new IllegalArgumentException("文字已变化，请重新生成字体资源：U+" + Integer.toHexString(glyph.code()));
        int advance = (int) (.5 + metric[0] * (style.size() / (float) metric[1])) + 1;
        return advance + (glyph.bold() ? 1 : 0);
    }
    net.kyori.adventure.key.Key textFont(int size) {
        return textFont(size, 1, 1);
    }
    net.kyori.adventure.key.Key textFont(int size, double stretchX, double stretchY) {
        return textFont(new TextStyle(size, stretchX, stretchY));
    }
    net.kyori.adventure.key.Key textFont(TextStyle style) {
        String name = style.name();
        if (!textFonts.containsKey(name))
            throw new IllegalArgumentException("文字位置已变化，请重新生成字体资源：" + name);
        return net.kyori.adventure.key.Key.key("dialogmenu", name);
    }
    net.kyori.adventure.key.Key textFont(int size, net.kyori.adventure.text.Component value) {
        return textFont(size, 1, 1, value);
    }
    net.kyori.adventure.key.Key textFont(int size, double stretchX, double stretchY,
                                         net.kyori.adventure.text.Component value) {
        return textFont(new TextStyle(size, stretchX, stretchY), value);
    }
    net.kyori.adventure.key.Key textFont(TextStyle style, net.kyori.adventure.text.Component value) {
        var key = textFont(style);
        for (int cp : codes(value)) if (!textFonts.get(key.value()).characters().contains(cp))
            throw new IllegalArgumentException("文字已变化，请重新生成字体资源：U+" + Integer.toHexString(cp));
        return key;
    }
    net.kyori.adventure.text.Component rgbaText(net.kyori.adventure.text.Component text, int fallback, int top) {
        return rgba.encode(text, fallback, top);
    }
    net.kyori.adventure.text.Component rgbaHit(int top, int size, net.kyori.adventure.text.Component text) {
        return rgbaHits.text(top, size, text, this);
    }
    net.kyori.adventure.text.Component rgbaHit(int top, TextStyle style,
                                               net.kyori.adventure.text.Component text) {
        return rgbaHits.text(top, style, text, this);
    }
    RgbaImages rgbaImages() { return rgbaImages; }
    boolean shaderImage(Sprite sprite, boolean interactive) {
        return RgbaImages.eligible(sprite) && (!interactive || RgbaImages.exactHits(sprite));
    }
    int imageTint(int argb) { return rgba.index(argb); }
    void write(Path target) throws IOException { write(target, ignored -> {}); }
    void write(Path target, IntConsumer progress) throws IOException {
        Path base = target.resolve("assets/dialogmenu");
        if (Files.exists(base)) try (var paths = Files.walk(base)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        Files.createDirectories(base.resolve("font"));
        Files.createDirectories(base.resolve("textures/generated"));
        Set<String> written = new HashSet<>();
        long atlasCount = text.values().stream().flatMap(Collection::stream)
            .map(TextAtlas::texture).distinct().count();
        int total = Math.max(1, Math.toIntExact(atlasCount) + textFonts.size() + 5), completed = 0;
        for (var entry : textFonts.entrySet())
            for (TextAtlas atlas : text.get(entry.getKey())) if (written.add(atlas.texture())) {
                BuildBudget.checkpoint();
                writePng(atlas.image(), base.resolve("textures/" + atlas.texture()));
                progress.accept(++completed * 100 / total);
            }
        Map<String, Integer> spaces = new LinkedHashMap<>();
        for (int x = -1024; x <= 1024; x++) spaces.put(SpriteCanvas.move(x), x);
        writeJson(base.resolve("font/space.json"), Map.of("providers", List.of(Map.of("type", "space", "advances", spaces))));
        List<Object> graphics = SpriteAtlases.write(sprites.values(), base);
        writeJson(base.resolve("font/sprites.json"), Map.of("providers", graphics));
        progress.accept(++completed * 100 / total);
        for (var entry : textFonts.entrySet()) {
            BuildBudget.checkpoint();
            TextFont font = entry.getValue();
            List<Object> characters = new ArrayList<>();
            for (TextAtlas atlas : text.get(entry.getKey()))
                characters.add(bitmap(atlas.texture(), font.style().height(), 0, atlas.chars()));
            characters.add(Map.of("type", "space", "advances",
                Map.of(" ", font.style().space())));
            writeJson(base.resolve("font/" + entry.getKey() + ".json"), Map.of("providers", characters));
            progress.accept(++completed * 100 / total);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("pack", Map.of("description", "DialogMenu whole sprites", "pack_format", 64,
            "supported_formats", Map.of("min_inclusive", 64, "max_inclusive", 97),
            "min_format", List.of(64, 0), "max_format", List.of(97, 1)));
        metadata.put("overlays", RgbaShaders.metadata());
        writeJson(target.resolve("pack.mcmeta"), metadata);
        progress.accept(++completed * 100 / total);
        rgbaImages.write(target);
        progress.accept(++completed * 100 / total);
        rgbaHits.write(target);
        progress.accept(++completed * 100 / total);
        rgba.write(target);
        progress.accept(++completed * 100 / total);
    }
    static void writePng(BufferedImage image, Path path) throws IOException {
        if (!ImageIO.write(image, "png", path.toFile()))
            throw new IOException("没有PNG编码器：" + path);
    }
    static Object bitmap(String texture, int height, int ascent, List<String> chars) {
        return Map.of("type", "bitmap", "file", texture.contains(":") ? texture : "dialogmenu:" + texture,
            "height", height, "ascent", ascent, "chars", chars);
    }
    private void writeJson(Path path, Object value) throws IOException { Files.writeString(path, json.toJson(value) + "\n"); }
}
