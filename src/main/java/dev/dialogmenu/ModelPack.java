package dev.dialogmenu;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

/** Frozen inventory faces and independent resource packs, persisted with the font snapshot. */
final class ModelPack {
    record Face(String atlas, String sprite, int face) {}
    private final Map<String, List<Face>> models;
    private final Map<String, String> packs;
    private final Map<String, double[]> bounds;
    private final Set<String> registered;
    private static ModelPack defaultPack;
    private static final List<String> COMPILER = List.of("prepare.py", "model_catalog.py",
        "item_catalog.py", "model_cache.py", "corner_texture.py", "inventory/special-research/native-geometry.json");

    private ModelPack(Map<String, List<Face>> models, Map<String, String> packs, Map<String, double[]> bounds,
                      Set<String> registered) {
        this.models = Map.copyOf(models);
        this.packs = Map.copyOf(packs);
        this.bounds = Map.copyOf(bounds);
        this.registered = Set.copyOf(registered);
    }

    private static Set<String> ids(Collection<LayoutStore.Document> documents) {
        Set<String> ids = new TreeSet<>();
        for (var doc : documents) for (Node node : doc.menu().nodes())
            if (node.type().equals("model")) ids.add(node.source());
        return ids;
    }
    private static synchronized ModelPack defaults(Path root) throws IOException {
        if (defaultPack != null) return defaultPack;
        var library = new AssetLibrary(root);
        library.prepare(ignored -> {});
        InventoryAssets.prepare(library);
        JsonObject data = InventoryAssets.json("models.json");
        var models = new TreeMap<String, List<Face>>();
        var bounds = new TreeMap<String, double[]>();
        Gson gson = new Gson();
        for (var entry : data.getAsJsonObject("models").entrySet())
            models.put(entry.getKey(), List.of(gson.fromJson(entry.getValue(), Face[].class)));
        for (var entry : data.getAsJsonObject("bounds").entrySet())
            bounds.put(entry.getKey(), gson.fromJson(entry.getValue(), double[].class));
        byte[] empty;
        try (var bytes = new ByteArrayOutputStream(); var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("assets/dialogmenu_ce/shaders/include/models.glsl"));
            zip.write(("#define MODEL_CE_FACES 0\n"
                + "vec4 modelCeData(int index) { return vec4(0.0); }\n")
                .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            empty = bytes.toByteArray();
        }
        defaultPack = new ModelPack(models, Map.of(
            "vanilla", Base64.getEncoder().encodeToString(InventoryAssets.files().get("resourcepack.zip")),
            "ce", Base64.getEncoder().encodeToString(empty)), bounds, models.keySet());
        return defaultPack;
    }
    static boolean matches(ModelPack pack, Collection<LayoutStore.Document> documents) {
        Set<String> required = ids(documents);
        if (pack != null) required.addAll(pack.registered);
        return required.equals(pack == null ? Set.of() : pack.models.keySet());
    }
    static ModelPack prepare(Path data, Collection<LayoutStore.Document> documents) throws Exception {
        ModelPack defaults = defaults(data);
        Set<String> ids = ids(documents);
        SourceFiles inputs = new SourceFiles();
        Path unified = data.resolve("images/registry.json");
        JsonObject declared = new JsonObject();
        if (Files.isRegularFile(unified)) {
            if (!unified.toRealPath().startsWith(data.resolve("images").toRealPath()))
                throw new IOException("images/registry.json cannot leave images/");
            JsonObject source = JsonParser.parseString(new String(inputs.read(unified), StandardCharsets.UTF_8)).getAsJsonObject();
            if (source.has("models")) declared = source.getAsJsonObject("models");
        }
        for (var entry : declared.entrySet()) {
            if (!entry.getKey().matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || entry.getKey().contains("..")
                || !entry.getValue().isJsonObject()) throw new IOException("Invalid model registration: " + entry.getKey());
            if (entry.getKey().startsWith("minecraft:") && !entry.getValue().getAsJsonObject().isEmpty())
                throw new IOException("原版模型注册使用空对象，不支持CE覆盖字段：" + entry.getKey());
        }
        Set<String> registered = new TreeSet<>(declared.keySet());
        ids.addAll(registered);
        ids.removeAll(defaults.models.keySet());
        registered.addAll(defaults.registered);
        if (ids.isEmpty()) {
            inputs.verify();
            return defaults;
        }
        Path root = data.resolve("models"), compiler = root.resolve("compiler");
        Path settings = root.resolve("settings.json"), registry = root.resolve("registry.json");
        if (!Files.isRegularFile(settings))
            throw new IOException("model需要 models/settings.json");
        JsonObject config = JsonParser.parseString(new String(inputs.read(settings), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject combined = Files.isRegularFile(registry)
            ? JsonParser.parseString(new String(inputs.read(registry), StandardCharsets.UTF_8)).getAsJsonObject()
            : new JsonObject();
        if (!combined.has("version")) combined.addProperty("version", 1);
        if (!combined.has("items")) combined.add("items", new JsonObject());
        JsonObject items = combined.getAsJsonObject("items");
        for (var entry : declared.entrySet()) {
            // Vanilla inventory IDs are resolved from the client jar; only CE IDs
            // belong in the CE item registry, which rejects minecraft:* entries.
            if (entry.getKey().startsWith("minecraft:")) {
                continue;
            }
            if (items.has(entry.getKey()) && !items.get(entry.getKey()).equals(entry.getValue()))
                throw new IOException("模型同时存在两个不同定义，请只保留统一注册表项：" + entry.getKey());
            items.add(entry.getKey(), entry.getValue());
        }
        Path effective = root.resolve("compiled-input.json");
        writeChanged(effective, new Gson().toJson(combined).getBytes(StandardCharsets.UTF_8));
        Path client = resolve(root, config.get("client-jar").getAsString());
        if (!Files.isRegularFile(client)) throw new IOException("model的client-jar不存在：" + client);
        for (String name : COMPILER) {
            try (var input = ModelPack.class.getResourceAsStream("/models/compiler/" + name)) {
                if (input == null) throw new IOException("Missing model compiler: " + name);
                writeChanged(compiler.resolve(name), input.readAllBytes());
            }
        }
        List<String> command = new ArrayList<>(List.of(
            config.has("python") ? config.get("python").getAsString() : "python3",
            compiler.resolve("prepare.py").toString(), "--registry", effective.toString(),
            "--output", root.resolve("prepared").toString()));
        if (config.has("ce-pack")) command.addAll(List.of("--ce-pack",
            resolve(root, config.get("ce-pack").getAsString()).toString()));
        command.add("--ids");
        command.addAll(ids);
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile())
            .redirectErrorStream(true).redirectOutput(root.resolve("build.log").toFile());
        builder.environment().put("DIALOGMENU_CLIENT_JAR", client.toString());
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        Process process = builder.start();
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS))
                throw new IOException("模型准备超时，见 " + root.resolve("build.log"));
            if (process.exitValue() != 0) {
                List<String> log = Files.readAllLines(root.resolve("build.log"));
                throw new IOException("模型准备失败：" + String.join(" ", log.subList(Math.max(0, log.size() - 3), log.size()))
                    + "；见 " + root.resolve("build.log"));
            }
        } finally {
            if (process.isAlive()) process.destroy();
        }
        JsonObject prepared = JsonParser.parseString(
            Files.readString(root.resolve("prepared/registry.json"))).getAsJsonObject();
        JsonObject registryData = prepared.getAsJsonObject("models");
        Map<String, List<Face>> faces = new LinkedHashMap<>(defaults.models);
        Map<String, double[]> bounds = new LinkedHashMap<>(defaults.bounds);
        Gson gson = new Gson();
        for (String id : ids) {
            if (!registryData.has(id))
                throw new IOException(id + " 不是可显示的立体物品；2D物品请用image，见 models/prepared/catalog.json");
            faces.put(id, List.of(gson.fromJson(registryData.get(id), Face[].class)));
            bounds.put(id, gson.fromJson(prepared.getAsJsonObject("bounds").get(id), double[].class));
        }
        Map<String, String> packs = new LinkedHashMap<>();
        for (String kind : List.of("vanilla", "ce"))
            packs.put(kind, Base64.getEncoder().encodeToString(
                Files.readAllBytes(root.resolve("prepared/" + kind + "/resourcepack.zip"))));
        packs.put("vanilla", defaults.packs.get("vanilla"));
        inputs.verify();
        return new ModelPack(faces, packs, bounds, registered);
    }

    java.awt.Rectangle area(Menu menu, Node node, int width, int height) {
        double[] box = bounds.get(node.source());
        if (box == null) throw new IllegalArgumentException(node.source() + " 未准备模型命中边界");
        double zoom = width / (double) menu.width();
        int size = (int) Math.round(node.size() * node.scale() * zoom);
        int x = (int) Math.round((menu.width() / 2.0 + node.x()) * zoom);
        int y = (int) Math.round((menu.height() / 2.0 - node.y()) * zoom) - 1;
        int left = Math.max(0, (int) Math.floor(x + box[0] * size));
        int top = Math.max(0, (int) Math.floor(y + box[1] * size));
        int right = Math.min(width, (int) Math.ceil(x + box[2] * size));
        int bottom = Math.min(height, (int) Math.ceil(y + box[3] * size));
        return new java.awt.Rectangle(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }

    private static Path resolve(Path root, String value) {
        return root.resolve(value).toAbsolutePath().normalize();
    }
    static void inputs(Path data, Set<Path> files) {
        files.add(data.resolve("images/registry.json"));
        for (String name : List.of("settings.json", "registry.json", "prepared/registry.json",
            "prepared/vanilla/resourcepack.zip", "prepared/ce/resourcepack.zip"))
            files.add(data.resolve("models").resolve(name));
    }
    static void writeChanged(Path path, byte[] bytes) throws IOException {
        if (Files.isRegularFile(path) && Arrays.equals(Files.readAllBytes(path), bytes)) return;
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "model-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    List<Path> paths(Path data) throws IOException {
        // 26.2 的完整字体快照已经包含模型资源，不再向合包方暴露原始世界图集。
        if (InventoryAssets.usesItemsAtlas()) return List.of();
        List<Path> result = new ArrayList<>();
        for (String kind : List.of("vanilla", "ce")) {
            byte[] bytes = Base64.getDecoder().decode(packs.get(kind));
            Path path = data.resolve("models/packs/" + kind + "-" + SourceFiles.digest(bytes) + ".zip");
            writeChanged(path, bytes);
            result.add(path);
        }
        return List.copyOf(result);
    }
    static String itemSprite(String sprite) { return "dialogmenu:model/" + sprite.replace(':', '/'); }
    // 已校验源库比 26.2 客户端新增的模型纹理；仅为这些纹理导出快照。
    private static final Set<String> MISSING_26_2 = Set.of(
        "minecraft:block/orange_poplar_leaves", "minecraft:block/poplar_log",
        "minecraft:block/poplar_log_top", "minecraft:block/poplar_planks", "minecraft:block/poplar_shelf",
        "minecraft:block/poplar_trapdoor", "minecraft:block/red_poplar_leaves", "minecraft:block/shelf_mushroom_stage0",
        "minecraft:block/straw_bed", "minecraft:block/stripped_poplar_log",
        "minecraft:block/stripped_poplar_log_top", "minecraft:block/yellow_poplar_leaves");
    private static String itemTexture(String sprite) { return "dialogmenu:model_texture/" + sprite.replace(':', '/'); }
    private static String texturePath(String id) {
        return "assets/" + id.replace(":", "/textures/") + ".png";
    }
    void write(Path target) throws IOException {
        for (String value : packs.values())
            try (var input = new ZipInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(value)))) {
                for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                    if (entry.getName().equals("pack.mcmeta") || entry.isDirectory()) continue;
                    // items-only 协议不导出原始世界图集，避免覆盖客户端或其他资源包的规则。
                    if (InventoryAssets.usesItemsAtlas() && entry.getName().contains("/atlases/")
                            && !entry.getName().equals("assets/minecraft/atlases/items.json")) continue;
                    Path path = target.resolve(entry.getName()).normalize();
                    if (!path.startsWith(target.normalize())) throw new IOException("Invalid model pack entry");
                    Files.createDirectories(path.getParent());
                    byte[] bytes = input.readAllBytes();
                    if (entry.getName().contains("/atlases/") && entry.getName().endsWith(".json")
                            && Files.isRegularFile(path)) {
                        JsonObject existing = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                        JsonObject incoming = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                        for (JsonElement source : incoming.getAsJsonArray("sources"))
                            if (!existing.getAsJsonArray("sources").contains(source)) existing.getAsJsonArray("sources").add(source);
                        bytes = existing.toString().getBytes(StandardCharsets.UTF_8);
                    }
                    Files.write(path, bytes);
                }
            }
        if (InventoryAssets.usesItemsAtlas()) writeItemSprites(target);
    }
    private void writeItemSprites(Path target) throws IOException {
        Path path = target.resolve("assets/minecraft/atlases/items.json");
        JsonObject atlas = Files.isRegularFile(path)
            ? JsonParser.parseString(Files.readString(path)).getAsJsonObject() : new JsonObject();
        JsonArray sources = atlas.has("sources") ? atlas.getAsJsonArray("sources") : new JsonArray();
        Set<String> sprites = new TreeSet<>();
        for (var faces : models.values()) for (Face face : faces) sprites.add(face.sprite());
        for (String sprite : sprites) {
            String resource = sprite;
            if (MISSING_26_2.contains(sprite)) {
                String input = texturePath(sprite);
                resource = itemTexture(sprite);
                Path destination = target.resolve(texturePath(resource));
                byte[] bytes = InventoryAssets.files().get(input);
                if (bytes == null) throw new IOException("Missing prepared model texture: " + sprite);
                Files.createDirectories(destination.getParent());
                Files.write(destination, bytes);
                byte[] metadata = InventoryAssets.files().get(input + ".mcmeta");
                if (metadata != null) Files.write(destination.resolveSibling(destination.getFileName() + ".mcmeta"), metadata);
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "minecraft:single");
            entry.addProperty("resource", resource);
            entry.addProperty("sprite", itemSprite(sprite));
            if (!sources.contains(entry)) sources.add(entry);
        }
        atlas.add("sources", sources);
        Files.createDirectories(path.getParent());
        Files.writeString(path, atlas.toString() + "\n");
    }
    Component component(String id, int size, int offset, int band) {
        List<Face> faces = models.get(id);
        if (faces == null) throw new IllegalArgumentException(id + " 未准备，请执行 /dialogmenu reload all");
        int payload = (RgbaImages.imagePayload(size, -offset, band) & 0x1FFFFFFF) | 0x40000000;
        JsonArray parts = new JsonArray();
        for (Face face : faces) {
            JsonObject shift = new JsonObject();
            shift.addProperty("text", faceMove(face.face(), false));
            shift.addProperty("font", "dialogmenu:space");
            parts.add(shift);
            JsonObject part = new JsonObject();
            part.addProperty("type", "object");
            part.addProperty("atlas", face.atlas());
            part.addProperty("sprite", InventoryAssets.usesItemsAtlas() ? itemSprite(face.sprite()) : face.sprite());
            part.addProperty("color", "#beef01");
            part.addProperty("shadow_color", payload);
            parts.add(part);
            JsonObject reset = new JsonObject();
            reset.addProperty("text", faceMove(face.face(), true) + SpriteCanvas.move(-8));
            reset.addProperty("font", "dialogmenu:space");
            parts.add(reset);
        }
        JsonObject json = new JsonObject();
        json.addProperty("text", "");
        json.add("extra", parts);
        // The old Paper API remains the compile baseline; the installed Adventure must support objects.
        Component result = GsonComponentSerializer.gson().deserialize(json.toString());
        String encoded = GsonComponentSerializer.gson().serialize(result);
        if (!encoded.contains("\"sprite\""))
            throw new IllegalStateException("model需要支持sprite组件的Minecraft/Adventure（26.2及以上）");
        return result;
    }
    static String faceMove(int face, boolean restore) {
        if (face < 0 || face >= 8192) throw new IllegalArgumentException("Model face out of range");
        // Keep every face outside normal GUI coordinates without exceeding float integer precision.
        int value = face + 33;
        StringBuilder text = new StringBuilder();
        for (int bit = 0; bit < 14; bit++)
            if ((value & (1 << bit)) != 0) text.appendCodePoint(0xF1000 + bit + (restore ? 14 : 0));
        return text.toString();
    }
    static void faceSpaces(Map<String, Integer> spaces) {
        for (int bit = 0; bit < 14; bit++) {
            spaces.put(Character.toString(0xF1000 + bit), -(2000 << bit));
            spaces.put(Character.toString(0xF1000 + bit + 14), 2000 << bit);
        }
    }
    static void shaders(Path target) throws IOException {
        Path vertexPath = target.resolve("dialogmenu_26_3/assets/minecraft/shaders/core/text.vsh");
        String vertex = Files.readString(vertexPath)
            .replace("void main() {", "#ifdef IS_GUI\n#include <dialogmenu_vanilla:models.glsl>\n"
                + "#include <dialogmenu_ce:models.glsl>\n#endif\n#include <dialogmenu:model_vertex.glsl>\n\nvoid main() {")
            .replace("    dialogmenu_finish_native(position);",
                "    dialogmenu_model(position, imageUv);\n    dialogmenu_finish_native(position);");
        Files.writeString(vertexPath, vertex);
        Path fragmentPath = target.resolve("dialogmenu_26_3/assets/minecraft/shaders/core/text.fsh");
        String fragment = Files.readString(fragmentPath)
            .replace("void main() {", "layout(location = 7) flat in float dialogmenuModelVisible;\n\nvoid main() {")
            .replace("    vec2 uv = texCoord0;", "    if (dialogmenuModelVisible < 0.5) discard;\n    vec2 uv = texCoord0;");
        Files.writeString(fragmentPath, fragment);
        try (var input = ModelPack.class.getResourceAsStream("/rgba/model_vertex.glsl")) {
            if (input == null) throw new IOException("Missing model shader");
            Path path = target.resolve("assets/dialogmenu/shaders/include/model_vertex.glsl");
            Files.createDirectories(path.getParent());
            String shader = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            Files.writeString(path, shader);
            // 26.2 使用 GLSL 330，按名称链接 varying，四顶点使用 gl_VertexID。
            Files.writeString(path.resolveSibling("model_vertex_26_2.glsl"), shader
                .replace("layout(location = 7) flat out", "flat out")
                .replace("(gl_VertexIndex - gl_BaseVertex)", "gl_VertexID"));
        }
        Path vertex26Path = target.resolve("dialogmenu_26_2/assets/minecraft/shaders/core/text.vsh");
        String vertex26 = Files.readString(vertex26Path)
            .replace("void main() {", "#ifdef IS_GUI\n#moj_import <dialogmenu_vanilla:models.glsl>\n"
                + "#moj_import <dialogmenu_ce:models.glsl>\n#moj_import <dialogmenu:model_vertex_26_2.glsl>\n#endif\n\nvoid main() {")
            .replace("    dialogmenu_finish_native(position);",
                "#ifdef IS_GUI\n    dialogmenu_model(position, imageUv);\n#endif\n    dialogmenu_finish_native(position);");
        Files.writeString(vertex26Path, vertex26);
        Path fragment26Path = target.resolve("dialogmenu_26_2/assets/minecraft/shaders/core/text.fsh");
        String fragment26 = Files.readString(fragment26Path)
            .replace("void main() {", "#ifdef IS_GUI\nflat in float dialogmenuModelVisible;\n#endif\n\nvoid main() {")
            .replace("    vec2 uv = texCoord0;", "#ifdef IS_GUI\n    if (dialogmenuModelVisible < 0.5) discard;\n#endif\n    vec2 uv = texCoord0;");
        Files.writeString(fragment26Path, fragment26);
    }
}
