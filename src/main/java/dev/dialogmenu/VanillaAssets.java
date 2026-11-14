package dev.dialogmenu;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.zip.*;
import javax.imageio.ImageIO;
import com.google.gson.Gson;
import com.google.gson.JsonElement;

/** 读取原版单字和图标源数据，不将整句文字渲染成图。 */
final class VanillaAssets {
    record Glyph(byte[] alpha, int width, int height, float advance) {}
    private final Map<Integer, Glyph> glyphs = new HashMap<>();
    private final String glyphSource;
    private final Map<String, BufferedImage> icons = new HashMap<>();
    private final Path images;
    private final Map<String, BufferedImage> custom = new HashMap<>();
    private final Map<String, ImageRegistry.Entry> registered = new TreeMap<>();
    private final SourceFiles inputs = new SourceFiles();
    private final AssetLibrary library;
    final CraftEngineAssets craftEngine;
    record State(Map<String, BufferedImage> custom, JsonElement craftEngine,
                 Map<String, ImageRegistry.Entry> registered, ResourceVersion version) {}

    synchronized State state(Gson codec) {
        synchronized (craftEngine) {
            return new State(new HashMap<>(custom), codec.toJsonTree(craftEngine), new TreeMap<>(registered), version());
        }
    }
    static VanillaAssets restore(Path images, State state, Gson codec) throws IOException {
        VanillaAssets assets = new VanillaAssets(images, codec.fromJson(state.craftEngine(), CraftEngineAssets.class),
            state.version());
        assets.custom.putAll(state.custom());
        assets.registered.putAll(state.registered());
        return assets;
    }

    VanillaAssets(Path images) throws IOException {
        this(images, (Path) null);
    }

    VanillaAssets(Path images, Path craftEngineResources) throws IOException {
        this(images, craftEngineResources, List.of());
    }

    VanillaAssets(Path images, Path craftEngineResources, List<String> definitions) throws IOException {
        this(images, craftEngineResources, definitions, ResourceVersion.DEFAULT);
    }

    VanillaAssets(Path images, Path craftEngineResources, List<String> definitions, ResourceVersion version) throws IOException {
        this(images, new CraftEngineAssets(craftEngineResources, definitions), version);
        registered.putAll(InventoryAssets.images(version));
        registered.putAll(ImageRegistry.load(this.images, craftEngine, inputs));
    }

    private VanillaAssets(Path images, CraftEngineAssets craftEngine, ResourceVersion version) throws IOException {
        this.images = images.toAbsolutePath().normalize();
        Files.createDirectories(this.images);
        this.craftEngine = craftEngine;
        library = new AssetLibrary(this.images.getParent(), version);
        library.prepare(ignored -> {});
        InventoryAssets.prepare(library);
        byte[] source = library.read("glyphs.bin.gz");
        glyphSource = SourceFiles.digest(source);
        try (var input = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(source)))) {
            if (input.readInt() != 0x534D4631) throw new IOException("Unknown glyph source format");
            int count = input.readInt();
            for (int i = 0; i < count; i++) {
                if (i % 128 == 0) BuildBudget.checkpoint();
                int cp = input.readInt(), w = input.readUnsignedShort(), h = input.readUnsignedShort();
                float advance = input.readFloat();
                byte[] alpha = input.readNBytes(w * h);
                if (alpha.length != w * h) throw new EOFException("Incomplete vanilla glyph");
                glyphs.put(cp, new Glyph(alpha, w, h, advance));
            }
        }
        try (var input = new ZipInputStream(new ByteArrayInputStream(library.read("vanilla-icons.zip")))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                BuildBudget.checkpoint();
                byte[] bytes = input.readAllBytes();
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                if (image != null) icons.put(entry.getName().replaceFirst("\\.png$", ""), image);
            }
        }
    }

    synchronized BufferedImage image(String source) throws IOException {
        if (SymbolImages.source(source)) {
            if (!custom.containsKey(source)) custom.put(source, SymbolImages.image(source, this));
            return custom.get(source);
        }
        String registeredSource = ImageRegistry.key(source);
        if (source.startsWith("minecraft:")) {
            String candidate = registeredSource(source);
            if (candidate != null) registeredSource = candidate;
        }
        if (ImageRegistry.source(registeredSource)) {
            ImageRegistry.Entry entry = registered.get(registeredSource);
            if (entry == null) throw new IOException("未准备注册材质：" + source + "；请检查 images/registry.json 后执行 /dialogmenu reload all");
            return entry.image();
        }
        if (source.startsWith("minecraft:")) {
            return vanillaImage(source);
        }
        if (source.startsWith("craftengine:")) {
            BufferedImage image = craftEngine.image(source);
            if (image != null) return image;
            String id = source.substring(source.indexOf('/') + 1);
            String file = craftEngine.imageFile(id);
            if (file != null && (file.startsWith("item/") || file.startsWith("block/")))
                return vanillaImage("minecraft:" + file);
            throw new IOException("Unresolved CraftEngine image: " + source);
        }
        throw new IOException("Unknown image source: " + source);
    }

    CraftEngineAssets.Reference reference(String source, MenuModel.TextureRegion atlas) throws IOException {
        if (!source.startsWith("minecraft:")) return craftEngine.reference(source, atlas);
        if (source.startsWith("minecraft:preview/"))
            throw new IOException("预览合成图不是原版纹理路径，请使用已有CE方块图标：" + source);
        String texture = source.endsWith(".png") ? source : source + ".png";
        return new CraftEngineAssets.Reference(texture, vanillaImage(source), null);
    }

    private BufferedImage vanillaImage(String source) throws IOException {
        BufferedImage image = icons.get(source.substring("minecraft:".length()).replaceFirst("\\.png$", ""));
        if (image == null) throw new IOException("Unknown vanilla texture: " + source);
        return image;
    }

    Glyph glyph(int codepoint) {
        return codepoint == RgbaText.RULE ? new Glyph(new byte[]{(byte) 255}, 1, 1, 1) : glyphs.get(codepoint);
    }
    String glyphSource() { return glyphSource; }
    synchronized Map<String, ImageRegistry.Entry> registeredImages() { return Map.copyOf(registered); }
    synchronized String registeredSource(String source) {
        String key = ImageRegistry.key(source);
        if (source != null && source.startsWith("minecraft:"))
            key = ImageRegistry.PREFIX + source.replaceFirst("\\.png$", "");
        return registered.containsKey(key) ? key : null;
    }
    synchronized int imageColor(String source, int argb) {
        if (SymbolImages.source(source)) return argb;
        ImageRegistry.Entry entry = registered.get(ImageRegistry.key(source));
        return entry != null && entry.tintable() ? argb : argb | 0xFFFFFF;
    }

    List<Path> libraryPaths() { return library.paths(); }
    ResourceVersion version() { return library.target(); }
    void verifyInputs() throws IOException { library.verify(); inputs.verify(); craftEngine.verifyInputs(); }

    List<String> sources() throws IOException {
        var result = new TreeSet<String>();
        for (String source : icons.keySet()) result.add("minecraft:" + source);
        for (String source : registered.keySet()) {
            String id = source.substring(ImageRegistry.PREFIX.length());
            result.add(id.contains(":") ? source : id);
        }
        result.addAll(craftEngine.sources());
        return List.copyOf(result);
    }

}
