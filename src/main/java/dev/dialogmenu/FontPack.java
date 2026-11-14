package dev.dialogmenu;

import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
    private record FrameJob(LayoutStore.Document doc, Context context, int width, double scale) {}
    private record PreparedFrame(FrameJob job, Rendered frame) {}
    private final Map<String, Glyph> sprites = new LinkedHashMap<>();
    private final Map<Integer, int[]> metrics = new HashMap<>();
    private final Map<String, List<TextAtlas>> text = new TreeMap<>();
    private record TextFont(TextStyle style, Set<Integer> characters) {}
    private final Map<String, TextFont> textFonts = new TreeMap<>();
    private String glyphSource;
    private ModelPack models;
    private transient boolean reusedGlyphs;
    private final RgbaOffsets rgba = new RgbaOffsets();
    private final RgbaHits rgbaHits = new RgbaHits();
    private final RgbaImages rgbaImages = new RgbaImages();
    private final NativeInk nativeInk = new NativeInk();
    private final RegisteredImages registeredImages = new RegisteredImages();
    private final transient Map<BufferedImage, String> imageKeys = new WeakHashMap<>();
    private final transient CanvasRows.Cache spriteParts = new CanvasRows.Cache();
    private final transient Gson json = new Gson();

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
        return create(docs, renderer, context, widths, maxPixels, extraCharacters, textScales,
            canvasScales, colors, progress, null);
    }
    static FontPack create(Collection<LayoutStore.Document> docs, MenuRenderer renderer, Context context,
                           Collection<Integer> widths, int maxPixels, String extraCharacters,
                           Collection<Double> textScales, Collection<Double> canvasScales,
                           Collection<Integer> colors, IntConsumer progress, BuildTasks tasks) throws IOException {
        return create(docs, renderer, context, widths, maxPixels, extraCharacters, textScales,
            canvasScales, colors, progress, tasks, null);
    }
    static FontPack create(Collection<LayoutStore.Document> docs, MenuRenderer renderer, Context context,
                           Collection<Integer> widths, int maxPixels, String extraCharacters,
                           Collection<Double> textScales, Collection<Double> canvasScales,
                           Collection<Integer> colors, IntConsumer progress, BuildTasks tasks,
                           FontPack previous) throws IOException {
        return create(docs, renderer, context, widths, maxPixels, extraCharacters, textScales,
            canvasScales, colors, progress, tasks, previous, null);
    }
    static FontPack create(Collection<LayoutStore.Document> docs, MenuRenderer renderer, Context context,
                           Collection<Integer> widths, int maxPixels, String extraCharacters,
                           Collection<Double> textScales, Collection<Double> canvasScales,
                           Collection<Integer> colors, IntConsumer progress, BuildTasks tasks,
                           FontPack previous, ModelPack models) throws IOException {
        FontPack pack = new FontPack();
        pack.models = models;
        pack.glyphSource = renderer.assets.glyphSource();
        FontPack cached = previous != null && pack.glyphSource.equals(previous.glyphSource) ? previous : null;
        if (cached != null) pack.metrics.putAll(cached.metrics);
        colors.forEach(pack.rgba::add);
        Set<Integer> commonCodes = codes(List.of(RgbaOffsets.commonCharacters()));
        Set<String> commonFonts = new HashSet<>();
        Map<String, Set<Integer>> dynamicCodes = new HashMap<>();
        Map<String, Set<String>> declarations = new HashMap<>();
        List<FrameJob> jobs = new ArrayList<>();
        Map<String, ImageRegistry.Entry> imageSources = new TreeMap<>(renderer.assets.registeredImages());
        for (var doc : docs.stream().sorted(Comparator.comparing(d -> d.menu().id())).toList())
            if (!doc.controls().empty() && doc.controls().overlay()) {
                var characters = new TreeSet<>(commonCodes);
                for (int cp = 33; cp <= 126; cp++) characters.add(cp);
                for (var button : doc.controls().buttons()) {
                    String label = MenuTemplate.preview(doc, context).expand(button.label());
                    characters.addAll(codes(List.of(label)));
                }
                for (var field : doc.controls().inputs())
                    characters.addAll(codes(List.of(MenuTemplate.preview(doc, context).expand(field.label()))));
                var font = pack.textFonts.computeIfAbsent(RgbaText.FONT,
                    key -> new TextFont(new TextStyle(8, 1), new TreeSet<>()));
                font.characters().addAll(characters);
                for (int cp : characters) pack.measure(cp, renderer.assets);
                pack.rgba.add(0xFFFFFF);
            }
        for (var doc : docs.stream().sorted(Comparator.comparing(d -> d.menu().id())).toList())
            for (double canvasScale : new TreeSet<>(canvasScales))
            for (int requestedWidth : MenuRenderer.preparedWidths(doc.menu(), widths, canvasScale, maxPixels))
                jobs.add(new FrameJob(doc, CanvasScale.context(MenuTemplate.preview(doc, context), canvasScale),
                    requestedWidth, canvasScale));
        var calibration = ScaleCalibration.document();
        jobs.add(new FrameJob(calibration, ScaleCalibration.reference(context), calibration.menu().width(), 1));
        int totalFrames = jobs.size();
        int[] completedFrames = {0};
        BuildTasks.ordered(tasks, jobs,
            job -> new PreparedFrame(job, renderer.drawRuntime(
                job.doc().menu(), job.context(), job.width(),
                ScaleCalibration.is(job.doc()) ? ScaleCalibration.PIXELS : maxPixels)), prepared -> {
                BuildBudget.checkpoint();
                var doc = prepared.job().doc();
                Context active = prepared.job().context();
                double canvasScale = prepared.job().scale();
                Rendered frame = prepared.frame();
                int width = frame.width();
                pack.nativeInk.prepare(doc.controls(), doc.menu(), width, frame.height());
                for (Sprite sprite : frame.sprites()) {
                    BuildBudget.checkpoint();
                    try {
                        Sprite local = RgbaRows.local(sprite);
                        Node node = doc.menu().nodes().stream().filter(n -> n.id().equals(sprite.id())).findFirst().orElseThrow();
                        int argb = node.type().equals("image")
                            ? renderer.assets.imageColor(node.source(), node.argb()) : node.argb();
                        pack.rgba.add(argb);
                        if (ImageRegistry.source(local.texture())) {
                            imageSources.merge(local.texture(),
                                new ImageRegistry.Entry(local.original(),
                                    !node.type().equals("image") || SymbolImages.source(node.source())),
                                (existing, added) -> existing.tintable() ? existing : added);
                            continue;
                        }
                        if (pack.shaderImage(local)) {
                            pack.rgbaImages.prepare(local, pack.rgba.add(argb));
                            continue;
                        }
                        for (Sprite part : pack.parts(sprite)) pack.register(RgbaRows.local(part));
                    }
                    catch (IOException error) { throw new IOException(doc.menu().id()+"/"+width+": "+error.getMessage(), error); }
                }
                for (Node node : frame.textNodes()) {
                    BuildBudget.checkpoint();
                    pack.rgba.prepare(MenuText.parse(active.expand(node.text())), node.argb());
                    List<String> values = MenuTemplate.textValues(doc, node, active);
                    for (String value : values)
                        pack.rgba.prepare(MenuText.parse(value), node.argb());
                    boolean dynamic = MenuTemplate.dynamicText(node);
                    Set<Integer> characters = codes(values);
                    boolean obfuscated = values.stream().map(MenuText::parse).flatMap(value -> MenuText.glyphs(value).stream())
                        .anyMatch(glyph -> glyph.style().hasDecoration(net.kyori.adventure.text.format.TextDecoration.OBFUSCATED));
                    if (dynamic || obfuscated) {
                        for (int cp = 33; cp <= 126; cp++) characters.add(cp);
                    }
                    if (obfuscated) characters.addAll(commonCodes);
                    String declaration = dynamic ? MenuTemplate.dynamicCharacters(doc, node, extraCharacters) : "";
                    for (int cp : characters) pack.measure(cp, renderer.assets);
                    for (double scale : ScaleCalibration.is(doc) ? List.of(1d) : textScales) {
                        int size = SpriteCanvas.textSize(doc.menu(), node, width, scale, canvasScale);
                        TextStyle style;
                        List<net.kyori.adventure.text.Component> lines;
                        try {
                            style = SpriteCanvas.fitTextStyle(node, size, width / (double) doc.menu().width(),
                                active.expand(node.text()), pack);
                            lines = dynamic ? List.of() : TextFlow.lines(node, MenuText.parse(active.expand(node.text())),
                                width / (double) doc.menu().width(),
                                glyph -> pack.glyphWidth(glyph, style));
                        } catch (IllegalArgumentException error) {
                            // 可选倍率可能放不下当前文字，玩家选择时仍会校验。
                            continue;
                        }
                        int count = dynamic ? (node.maxLines() > 0 ? node.maxLines()
                            : frame.height() / style.stride() + 1) : lines.size();
                        boolean registeredShaderCharacters = false;
                        for (int line = 0; line < count; line++) {
                            int top = SpriteCanvas.textTop(doc.menu(), node, width, line, style);
                            if (top >= 0 && top + style.height() <= frame.height()) {
                                String name = RgbaText.FONT;
                                TextFont font = pack.textFonts.computeIfAbsent(name,
                                    key -> new TextFont(new TextStyle(8, 1), new TreeSet<>()));
                                pack.measure(RgbaText.RULE, renderer.assets);
                                font.characters().add(RgbaText.RULE);
                                if (ScaleCalibration.is(doc) && node.id().equals(ScaleCalibration.VALUE_NODE)) {
                                    for (int cp : "0123456789.".codePoints().toArray()) {
                                        pack.measure(cp, renderer.assets);
                                        font.characters().add(cp);
                                    }
                                }
                                if (dynamic) {
                                    if (!registeredShaderCharacters) {
                                        font.characters().addAll(characters);
                                        if (declarations.computeIfAbsent(name, key -> new HashSet<>()).add(declaration)) {
                                            Set<Integer> declared = dynamicCodes.computeIfAbsent(declaration,
                                                value -> codes(List.of(value)));
                                            for (int cp : declared) pack.measure(cp, renderer.assets);
                                            font.characters().addAll(declared);
                                        }
                                    }
                                    registeredShaderCharacters = true;
                                    if (commonFonts.add(name)) {
                                        for (int cp : commonCodes) pack.measure(cp, renderer.assets);
                                        font.characters().addAll(commonCodes);
                                    }
                                } else {
                                    font.characters().addAll(codes(lines.get(line)));
                                    if (obfuscated) font.characters().addAll(characters);
                                }
                            }
                        }
                    }
                }
                progress.accept(totalFrames == 0 ? 55 : ++completedFrames[0] * 55 / totalFrames);
            });
        if (docs.stream().flatMap(doc -> doc.menu().nodes().stream()).anyMatch(node ->
            SymbolImages.source(node.source()) || Set.of("rectangle", "frame", "line").contains(node.type())))
            for (String symbol : SymbolImages.SYMBOLS) {
                BufferedImage source = renderer.assets.image("symbol:" + symbol);
                String alias = RegisteredImages.sourceKey(source);
                imageSources.put(alias, new ImageRegistry.Entry(source, true));
            }
        pack.registeredImages.prepare(imageSources, pack.rgba.indices(), pack.rgba.index(0xFFFFFF));
        if (cached != null && pack.textFonts.equals(cached.textFonts)) {
            pack.text.putAll(cached.text);
            pack.reusedGlyphs = true;
            progress.accept(85);
            return pack;
        }
        Map<TextCell, Set<Integer>> used = new LinkedHashMap<>();
        Map<String, List<TextCell>> cells = new HashMap<>();
        int fontCount = pack.textFonts.size();
        int completedFonts = 0;
        for (var entry : pack.textFonts.entrySet()) {
            BuildBudget.checkpoint();
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
        int totalCells = used.size();
        int[] completedCells = {0};
        BuildTasks.ordered(tasks, new ArrayList<>(used.entrySet()),
            entry -> Map.entry(entry.getKey(), pack.characters(entry.getValue(), renderer.assets)), entry -> {
                shared.put(entry.getKey(), entry.getValue());
                progress.accept(65 + (totalCells == 0 ? 10 : ++completedCells[0] * 10 / totalCells));
            });
        int[] finishedFonts = {0};
        BuildTasks.ordered(tasks, new ArrayList<>(pack.textFonts.entrySet()), entry -> {
            BuildBudget.checkpoint();
            TextFont font = entry.getValue();
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
            return Map.entry(entry.getKey(), List.copyOf(atlases));
        }, entry -> {
            pack.text.put(entry.getKey(), entry.getValue());
            progress.accept(75 + (fontCount == 0 ? 10 : ++finishedFonts[0] * 10 / fontCount));
        });
        progress.accept(85);
        return pack;
    }
    boolean reusedGlyphs() { return reusedGlyphs; }
    NativeInk nativeInk() { return nativeInk; }

    /** One validation pass owns this cache; it is not persisted or retained by the snapshot. */
    static final class Validation {
        private record Requirement(String font, String characters) {}
        private final Set<Integer> commonCodes = FontPack.codes(List.of(RgbaOffsets.commonCharacters()));
        private final Map<String, Set<Integer>> codes = new ConcurrentHashMap<>();
        private final Map<String, Boolean> common = new ConcurrentHashMap<>();
        private final Map<Requirement, Boolean> covered = new ConcurrentHashMap<>();

        Validation() throws IOException {}

        boolean covers(String name, TextFont font, String characters) {
            return common.computeIfAbsent(name, key -> font.characters().containsAll(commonCodes))
                && covered.computeIfAbsent(new Requirement(name, characters),
                    key -> font.characters().containsAll(codes.computeIfAbsent(characters,
                        value -> FontPack.codes(List.of(value)))));
        }
    }
    private static Set<Integer> codes(Collection<String> values) {
        Set<Integer> result = new TreeSet<>();
        for (String value : values) result.addAll(codes(MenuText.parse(value)));
        return result;
    }
    void validateDynamic(LayoutStore.Document doc, Context context, int width, String extra,
                         Validation validation) throws IOException {
        width = doc.menu().requestedWidth(width);
        for (Node node : doc.menu().nodes()) if (node.type().equals("text") && MenuTemplate.dynamicText(node)) {
            int size = SpriteCanvas.textSize(doc.menu(), node, width, context.textScale(), context.canvasScale());
            var key = textFont(new TextStyle(size, node.scale()));
            TextFont font = textFonts.get(key.value());
            if (font == null || !validation.covers(key.value(), font,
                MenuTemplate.dynamicCharacters(doc, node, extra)))
                throw new IllegalArgumentException("动态文字字符范围发生变化，请执行 /dialogmenu reload all");
        }
    }
    private static Set<Integer> codes(net.kyori.adventure.text.Component value) {
        Set<Integer> result = new TreeSet<>();
        for (var glyph : MenuText.glyphs(value))
            if (glyph.code() != ' ' && glyph.code() != '\n') result.add(glyph.code());
        return result;
    }
    private synchronized String key(BufferedImage image) {
        return imageKeys.computeIfAbsent(image, FontPack::hash);
    }
    List<Sprite> parts(Sprite sprite) { return spriteParts.parts(sprite); }
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
                int height = sprite.height();
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
        metrics.put(cp, new int[] {used, glyph.height(), glyph.width()});
    }
    private List<TextAtlas> characters(Set<Integer> characters, VanillaAssets assets) throws IOException {
        List<TextAtlas> result = new ArrayList<>();
        Map<Integer, List<Integer>> byHeight = new TreeMap<>();
        for (int cp : characters) {
            // 指标已在顺序注册阶段准备；并行图集阶段只读共享状态。
            int cell = Integer.highestOneBit(Math.max(metrics.get(cp)[1], metrics.get(cp)[2]) + 1) << 1;
            byHeight.computeIfAbsent(cell, h -> new ArrayList<>()).add(cp);
        }
        for (var group : byHeight.entrySet()) {
            int h = group.getKey(), cw = h;
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
                        BufferedImage wrapped = RgbaText.wrap(assets.glyph(cp));
                        if (wrapped.getWidth() != cw) throw new IOException("Mixed source glyph cell sizes");
                        image.setRGB(x * cw, y * h, cw, h, wrapped.getRGB(0, 0, cw, h, null, 0, cw), 0, cw);
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
    int textWidth(net.kyori.adventure.text.Component value, TextStyle style) {
        return MenuText.glyphs(value).stream().mapToInt(glyph -> glyphWidth(glyph, style)).sum();
    }
    int glyphWidth(MenuText.Glyph glyph, int size) { return glyphWidth(glyph, new TextStyle(size, 1)); }
    int glyphWidth(MenuText.Glyph glyph, TextStyle style) {
        if (glyph.code() == 32) return style.space() + (glyph.bold() ? 1 : 0);
        int[] metric = metrics.get(glyph.code());
        if (metric == null)
            throw new IllegalArgumentException("文字已变化，请重新生成字体资源：U+" + Integer.toHexString(glyph.code()));
        int advance = (int) Math.round(metric[0] * style.size() * style.scale() / metric[1])
            + (glyph.code() == RgbaText.RULE ? 0 : 1);
        return advance + (glyph.bold() ? 1 : 0);
    }
    net.kyori.adventure.key.Key textFont(int size) {
        return textFont(size, 1);
    }
    net.kyori.adventure.key.Key textFont(int size, double scale) {
        return textFont(new TextStyle(size, scale));
    }
    net.kyori.adventure.key.Key textFont(TextStyle style) {
        String name = RgbaText.FONT;
        if (!textFonts.containsKey(name))
            throw new IllegalArgumentException("文字位置已变化，请重新生成字体资源：" + name);
        return net.kyori.adventure.key.Key.key("dialogmenu", name);
    }
    net.kyori.adventure.key.Key textFont(int size, net.kyori.adventure.text.Component value) {
        return textFont(new TextStyle(size, 1), value);
    }
    net.kyori.adventure.key.Key textFont(TextStyle style, net.kyori.adventure.text.Component value) {
        var key = textFont(style);
        for (int cp : codes(value)) if (!textFonts.get(key.value()).characters().contains(cp))
            throw new IllegalArgumentException("文字已变化，请重新生成字体资源：U+" + Integer.toHexString(cp));
        return key;
    }
    net.kyori.adventure.text.Component rgbaText(net.kyori.adventure.text.Component text, int fallback, int top,
                                               TextStyle style, int band) {
        return RgbaDecorations.encode(text, fallback, top, style, this, rgba, band);
    }
    int glyphPixels(int cp, TextStyle style) {
        int[] metric = metrics.get(cp);
        if (metric == null) throw new IllegalArgumentException("未准备字形 U+" + Integer.toHexString(cp));
        return Math.max(1, (int) Math.round(metric[2] * style.size() * style.scale() / metric[1]));
    }
    net.kyori.adventure.key.Key obfuscatedFont(int cp) {
        int[] metric = metrics.get(cp);
        return net.kyori.adventure.key.Key.key("dialogmenu",
            RgbaText.FONT + "_obf_" + metric[0] + "_" + metric[1] + "_" + metric[2]);
    }
    net.kyori.adventure.text.Component rgbaHit(int top, int height, TextStyle style,
                                               net.kyori.adventure.text.Component text,
                                               net.kyori.adventure.text.event.ClickEvent click,
                                               net.kyori.adventure.text.event.HoverEvent<net.kyori.adventure.text.Component> hover) {
        return rgbaHits.text(top, height, style, text, this, click, hover);
    }
    net.kyori.adventure.text.Component rgbaBitmap(net.kyori.adventure.text.Component glyph,
                                                 int argb, int top, int height, int band) {
        return rgba.bitmap(glyph, argb, top, height, band);
    }
    RgbaImages rgbaImages() { return rgbaImages; }
    RegisteredImages registeredImages() { return registeredImages; }
    boolean shaderImage(Sprite sprite) {
        return RgbaImages.eligible(sprite);
    }
    int imageTint(int argb) { return rgba.index(argb); }
    ModelPack modelPack() { return models; }
    java.awt.Rectangle modelArea(Menu menu, Node node, int width, int height) {
        if (models == null) throw new IllegalArgumentException("模型未准备，请执行 /dialogmenu reload all");
        return models.area(menu, node, width, height);
    }
    net.kyori.adventure.text.Component model(String id, int size, int offset, int band) {
        if (models == null) throw new IllegalArgumentException("模型未准备，请执行 /dialogmenu reload all");
        return models.component(id, size, offset, band);
    }
    List<Path> modelPacks(Path data) throws IOException {
        return models == null ? List.of() : models.paths(data);
    }
    void write(Path target) throws IOException { write(target, ignored -> {}); }
    void write(Path target, IntConsumer progress) throws IOException {
        write(target, progress, null);
    }
    void write(Path target, IntConsumer progress, BuildTasks tasks) throws IOException {
        write(target, progress, tasks, false);
    }
    void writeBase(Path target, IntConsumer progress, BuildTasks tasks) throws IOException {
        // 快照包含模型表与纹理，独立导出方不需要推断额外的资源依赖。
        write(target, progress, tasks, false);
    }
    private void write(Path target, IntConsumer progress, BuildTasks tasks, boolean separateModels) throws IOException {
        Path base = target.resolve("assets/dialogmenu");
        if (Files.exists(base)) try (var paths = Files.walk(base)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        Files.createDirectories(base.resolve("font"));
        Files.createDirectories(base.resolve("textures/generated"));
        Set<String> written = new HashSet<>();
        List<BuildTasks.Task<Path, Void>> writes = new ArrayList<>();
        for (var entry : textFonts.entrySet())
            for (TextAtlas atlas : text.get(entry.getKey())) if (written.add(atlas.texture())) {
                writes.add(path -> {
                    writePng(atlas.image(), base.resolve("textures/" + atlas.texture()));
                    return null;
                });
            }
        Map<String, Integer> spaces = new LinkedHashMap<>();
        for (int x = -1024; x <= 1024; x++) spaces.put(SpriteCanvas.move(x), x);
        ModelPack.faceSpaces(spaces);
        RgbaOffsets.nativeSpaces(spaces);
        NativeInk.spaces(spaces);
        writeJson(base.resolve("font/space.json"), Map.of("providers", List.of(Map.of("type", "space", "advances", spaces))));
        writes.add(path -> {
            List<Object> graphics = SpriteAtlases.write(sprites.values(), base);
            writeJson(base.resolve("font/sprites.json"), Map.of("providers", graphics));
            return null;
        });
        for (var entry : textFonts.entrySet()) {
            writes.add(path -> {
                BuildBudget.checkpoint();
                TextFont font = entry.getValue();
                List<Object> characters = new ArrayList<>();
                for (TextAtlas atlas : text.get(entry.getKey()))
                    characters.add(bitmap(atlas.texture(), 1, 0, atlas.chars()));
                characters.add(Map.of("type", "space", "advances",
                    Map.of(" ", font.style().space())));
                writeJson(base.resolve("font/" + entry.getKey() + ".json"), Map.of("providers", characters));
                Map<String, Set<Integer>> groups = new TreeMap<>();
                for (int cp : font.characters())
                    groups.computeIfAbsent(obfuscatedFont(cp).value(), key -> new TreeSet<>()).add(cp);
                for (var group : groups.entrySet()) {
                    List<Object> providers = new ArrayList<>();
                    for (TextAtlas atlas : text.get(entry.getKey())) {
                        boolean present = false;
                        List<String> rows = new ArrayList<>();
                        for (String row : atlas.chars()) {
                            StringBuilder mask = new StringBuilder();
                            for (int cp : row.codePoints().toArray()) {
                                boolean allowed = group.getValue().contains(cp);
                                present |= allowed;
                                mask.appendCodePoint(allowed ? cp : 0);
                            }
                            rows.add(mask.toString());
                        }
                        if (present) providers.add(bitmap(atlas.texture(), 1, 0, rows));
                    }
                    writeJson(base.resolve("font/" + group.getKey() + ".json"), Map.of("providers", providers));
                }
                return null;
            });
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("pack", Map.of("description", "DialogMenu whole sprites", "pack_format", 64,
            "supported_formats", Map.of("min_inclusive", 64, "max_inclusive", 97),
            "min_format", List.of(64, 0), "max_format", List.of(97, 1)));
        metadata.put("overlays", RgbaShaders.metadata());
        writeJson(target.resolve("pack.mcmeta"), metadata);
        writes.add(path -> { rgbaImages.write(path); return null; });
        writes.add(path -> { rgbaHits.write(path); return null; });
        writes.add(path -> { rgba.write(path); nativeInk.write(path); return null; });
        int[] completed = {0};
        BuildTasks.ordered(tasks, writes, write -> write.apply(target),
            ignored -> progress.accept(++completed[0] * 100 / writes.size()));
        if (models != null) {
            ModelPack.shaders(target);
            if (!separateModels) models.write(target);
        }
        registeredImages.write(target);
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
