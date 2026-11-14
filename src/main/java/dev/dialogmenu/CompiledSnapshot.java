package dev.dialogmenu;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** 保存运行所需映射及源图，不在启动时重新绘制、校验或打包。 */
final class CompiledSnapshot {
    private static final int FORMAT = 1;
    private final Path data;
    private final Path file;
    private final String engine;
    private record State(Map<String, String> layouts, Set<String> pages, ReloadEngine.Settings settings,
                         FontPack fonts, VanillaAssets.State assets, String pack, String packDigest,
                         Map<Path, ReloadEngine.Stamp> inputs, Map<Path, ReloadEngine.Stamp> resources,
                         Map<Path, ReloadEngine.Stamp> builtInputs, long generation) {}
    private record FileData(int format, String engine, String directory, Map<String, String> images,
                            JsonElement state) {}
    record Loaded(ReloadEngine.Snapshot snapshot, Map<Path, ReloadEngine.Stamp> builtInputs) {}

    CompiledSnapshot(Path data) throws IOException {
        this.data = data.toAbsolutePath().normalize();
        file = this.data.resolve("pack-cache/compiled.json.gz");
        // 同版本号也可能改变编译器/资源，禁止用旧映射解释新渲染器。
        java.net.URL location = CompiledSnapshot.class.getProtectionDomain().getCodeSource().getLocation();
        try {
            Path binary = Path.of(location.toURI());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (Files.isRegularFile(binary)) Files.copy(binary, bytes);
            else {
                for (String name : List.of("CompiledSnapshot.class", "ReloadEngine.class", "FontPack.class",
                    "RgbaImages.class", "RegisteredImages.class", "ImageRegistry.class", "PrimitiveShapes.class",
                    "RgbaHits.class", "RgbaOffsets.class", "RgbaDecorations.class",
                    "CanvasScale.class", "ScaleCalibration.class", "NativeUiTextures.class", "MenuOpeners.class", "MenuRenderer.class",
                    "SpriteCanvas.class", "PreparedLayouts.class", "NativeInk.class", "NativeControls.class",
                    "LayoutStore.class", "MenuTemplate.class", "ModelPack.class",
                    "VanillaAssets.class", "CraftEngineAssets.class", "AssetLibrary.class",
                    "InventoryAssets.class", "ResourceVersion.class", "BitmapAtlas.class",
                    "LegacyImages.class", "LegacyModels.class", "/rgba/model_vertex_legacy.glsl",
                    "/rgba/vertex.glsl", "/rgba/characters-3500.txt")) {
                    try (var input = CompiledSnapshot.class.getResourceAsStream(name)) {
                        if (input == null) throw new IOException("缺少缓存兼容性标识：" + name);
                        input.transferTo(bytes);
                    }
                }
            }
            try (var manifest = CompiledSnapshot.class.getResourceAsStream("/vanilla-assets.properties")) {
                if (manifest == null) throw new IOException("缺少原版资源库版本标识");
                manifest.transferTo(bytes);
            }
            for (String version : ResourceVersion.SUPPORTED) {
                if (version.equals(ResourceVersion.DEFAULT.id())) continue;
                try (var manifest = CompiledSnapshot.class.getResourceAsStream(new ResourceVersion(version).manifest())) {
                    if (manifest == null) throw new IOException("Missing vanilla asset manifest: " + version);
                    manifest.transferTo(bytes);
                }
            }
            engine = SourceFiles.digest(bytes.toByteArray());
        } catch (java.net.URISyntaxException error) { throw new IOException(error); }
    }

    void prune(PackCache cache) throws IOException {
        Path protectedPack = null;
        if (Files.exists(file)) {
            // 兼容性变更或失败重建也不能提前删除磁盘上最后成功的 ZIP。
            try (var input = new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)),
                StandardCharsets.UTF_8)) {
                String name = JsonParser.parseReader(input).getAsJsonObject().getAsJsonObject("state")
                    .get("pack").getAsString();
                if (!name.matches("dialog-[0-9a-f-]{36}\\.zip")) return;
                protectedPack = file.getParent().resolve(name);
            } catch (IOException | RuntimeException error) {
                return;
            }
        }
        cache.prune(protectedPack);
    }

    void save(ReloadEngine.Snapshot snapshot, Map<Path, ReloadEngine.Stamp> builtInputs) throws IOException {
        Map<String, String> layouts = new LinkedHashMap<>();
        for (var doc : snapshot.documents().values()) {
            if (doc.sourceYaml() == null) throw new IOException("菜单缺少已应用的源快照");
            layouts.put(LayoutStore.source(doc), doc.sourceYaml());
        }
        Images images = new Images(new LinkedHashMap<>());
        Gson codec = codec(images);
        State state = new State(layouts, snapshot.documents().keySet(), snapshot.settings(), snapshot.fonts(),
            snapshot.assets().state(codec), snapshot.pack().getFileName().toString(),
            SourceFiles.digest(Files.readAllBytes(snapshot.pack())), snapshot.inputs(), snapshot.resourceInputs(),
            builtInputs, snapshot.generation());
        JsonElement payload = codec.toJsonTree(state);
        FileData saved = new FileData(FORMAT, engine, data.toString(), images.encoded, payload);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "compiled-", ".tmp");
        try {
            try (var output = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(temporary)),
                StandardCharsets.UTF_8)) {
                codec.toJson(saved, output);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (RuntimeException error) { throw new IOException("无法保存Dialog编译快照", error); }
        finally { Files.deleteIfExists(temporary); }
    }

    Loaded load() throws Exception {
        if (!Files.isRegularFile(file)) throw new IOException("没有已编译快照，请执行 /dialogmenu reload all");
        long start = System.nanoTime();
        try {
            FileData saved;
            try (var input = new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)),
                StandardCharsets.UTF_8)) {
                saved = new Gson().fromJson(input, FileData.class);
            }
            if (saved.format() != FORMAT || !engine.equals(saved.engine()) || !data.toString().equals(saved.directory()))
                throw new IOException("编译快照与当前引擎不兼容，请执行 /dialogmenu reload all");
            Gson codec = codec(new Images(saved.images()));
            State state = codec.fromJson(saved.state(), State.class);
            if (!state.pack().matches("dialog-[0-9a-f-]{36}\\.zip"))
                throw new IOException("编译快照资源包路径无效");
            Path pack = file.getParent().resolve(state.pack());
            if (!state.packDigest().equals(SourceFiles.digest(Files.readAllBytes(pack))))
                throw new IOException("已编译资源包损坏，请执行 /dialogmenu reload all");
            Map<String, LayoutStore.Document> docs = new LinkedHashMap<>();
            for (var entry : state.layouts().entrySet())
                for (var page : LayoutStore.loadText(entry.getKey(), entry.getValue()).entrySet())
                    if (state.pages().contains(page.getKey()) && docs.putIfAbsent(page.getKey(), page.getValue()) != null)
                        throw new IOException("编译快照含重复菜单");
            if (!docs.keySet().equals(state.pages()) || !docs.containsKey(state.settings().defaultLayout()))
                throw new IOException("编译快照菜单不完整");
            LayoutStore.validateLinks(docs);
            var assets = VanillaAssets.restore(data.resolve("images"), state.assets(), codec);
            var snapshot = new ReloadEngine.Snapshot(Collections.unmodifiableMap(docs), assets,
                new MenuRenderer(assets), Objects.requireNonNull(state.fonts()), state.settings(), pack,
                Map.copyOf(state.inputs()), Map.copyOf(state.resources()),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                Thread.currentThread().getName(), state.generation());
            return new Loaded(snapshot, Map.copyOf(state.builtInputs()));
        } catch (RuntimeException error) {
            throw new IOException("Dialog编译快照损坏，请执行 /dialogmenu reload all", error);
        }
    }

    private static Gson codec(Images images) {
        return new GsonBuilder().serializeNulls().enableComplexMapKeySerialization()
            .registerTypeHierarchyAdapter(Path.class, new PathCodec())
            .registerTypeAdapter(FileTime.class, new TimeCodec())
            .registerTypeHierarchyAdapter(BufferedImage.class, images)
            .registerTypeHierarchyAdapter(ConfigurationSection.class, new SectionCodec())
            .create();
    }

    private static final class PathCodec implements JsonSerializer<Path>, JsonDeserializer<Path> {
        public JsonElement serialize(Path path, java.lang.reflect.Type type, JsonSerializationContext context) {
            return new JsonPrimitive(path.toString());
        }
        public Path deserialize(JsonElement value, java.lang.reflect.Type type, JsonDeserializationContext context) {
            return Path.of(value.getAsString());
        }
    }
    private static final class TimeCodec implements JsonSerializer<FileTime>, JsonDeserializer<FileTime> {
        public JsonElement serialize(FileTime time, java.lang.reflect.Type type, JsonSerializationContext context) {
            return new JsonPrimitive(time.toString());
        }
        public FileTime deserialize(JsonElement value, java.lang.reflect.Type type, JsonDeserializationContext context) {
            return FileTime.from(Instant.parse(value.getAsString()));
        }
    }
    private static final class SectionCodec implements JsonSerializer<ConfigurationSection>,
                                                        JsonDeserializer<ConfigurationSection> {
        public JsonElement serialize(ConfigurationSection section, java.lang.reflect.Type type,
                                     JsonSerializationContext context) {
            var yaml = new YamlConfiguration();
            MenuTemplate.copy(section, yaml);
            return new JsonPrimitive(yaml.saveToString());
        }
        public ConfigurationSection deserialize(JsonElement value, java.lang.reflect.Type type,
                                                JsonDeserializationContext context) {
            var yaml = new YamlConfiguration();
            try { yaml.loadFromString(value.getAsString()); }
            catch (Exception error) { throw new JsonParseException(error); }
            return yaml;
        }
    }
    private static final class Images implements JsonSerializer<BufferedImage>, JsonDeserializer<BufferedImage> {
        final Map<String, String> encoded;
        final Map<BufferedImage, String> keys = new IdentityHashMap<>();
        final Map<String, BufferedImage> decoded = new HashMap<>();
        Images(Map<String, String> encoded) { this.encoded = Objects.requireNonNull(encoded); }
        public JsonElement serialize(BufferedImage image, java.lang.reflect.Type type,
                                     JsonSerializationContext context) {
            String key = keys.computeIfAbsent(image, FontPack::hash);
            if (!encoded.containsKey(key)) {
                try {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    if (!ImageIO.write(image, "png", bytes)) throw new IOException("没有PNG编码器");
                    encoded.put(key, Base64.getEncoder().encodeToString(bytes.toByteArray()));
                } catch (IOException error) { throw new JsonIOException(error); }
            }
            return new JsonPrimitive(key);
        }
        public BufferedImage deserialize(JsonElement value, java.lang.reflect.Type type,
                                         JsonDeserializationContext context) {
            return decoded.computeIfAbsent(value.getAsString(), key -> {
                try {
                    BufferedImage image = ImageIO.read(new ByteArrayInputStream(
                        Base64.getDecoder().decode(Objects.requireNonNull(encoded.get(key)))));
                    if (image == null || !FontPack.hash(image).equals(key))
                        throw new IOException("编译快照图像损坏");
                    return image;
                } catch (IOException error) { throw new JsonParseException(error); }
            });
        }
    }
}
