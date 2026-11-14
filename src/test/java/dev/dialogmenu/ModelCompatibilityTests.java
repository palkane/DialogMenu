package dev.dialogmenu;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

/** 核对实际资源快照、模型组件和 26.2 着色器之间的约定。 */
final class ModelCompatibilityTests {
    static int run(Path root) throws Exception {
        Path data = Files.createTempDirectory(root, "model-compatibility-");
        Files.createDirectories(data.resolve("layouts"));
        Files.writeString(data.resolve("config.yml"),
            "default-layout: probe\ntext-scales: [1]\ncanvas-scales: [1]\nresource-build-cpu-percent: 100\n");
        Files.writeString(data.resolve("layouts/probe.yml"), """
            schema-version: 1
            id: probe
            canvas: {width: 300, height: 180}
            frontend:
              log: {type: model, source: minecraft:oak_log, size: 20}
            """);
        Context context = new Context() {
            public String expand(String text) { return text; }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return true; }
        };
        int checks = 0;
        try (var engine = new ReloadEngine(data, context)) {
            var snapshot = engine.prepare().get(120, TimeUnit.SECONDS).snapshot();
            try (var zip = new ZipFile(snapshot.pack().toFile())) {
                for (String name : List.of("assets/dialogmenu_vanilla/shaders/include/models.glsl",
                        "assets/dialogmenu_ce/shaders/include/models.glsl")) {
                    if (zip.getEntry(name) == null) throw new AssertionError("Incomplete model snapshot: " + name);
                    checks++;
                }
                String vertex = text(zip, "dialogmenu_26_2/assets/minecraft/shaders/core/text.vsh");
                if (!vertex.contains("dialogmenu_model(position, imageUv)"))
                    throw new AssertionError("26.2 model geometry was not dispatched");
                String include = text(zip, "assets/dialogmenu/shaders/include/model_vertex_26_2.glsl");
                if (include.contains("gl_VertexIndex") || include.contains("gl_BaseVertex")
                        || include.contains("layout(location = 7)"))
                    throw new AssertionError("Unsupported 26.2 shader interface");
                checks += 2;
                if (InventoryAssets.usesItemsAtlas()) {
                    JsonArray sources = JsonParser.parseString(text(zip, "assets/minecraft/atlases/items.json"))
                        .getAsJsonObject().getAsJsonArray("sources");
                    Map<String, String> aliases = new HashMap<>();
                    for (var source : sources) {
                        var entry = source.getAsJsonObject();
                        if (entry.has("sprite") && entry.get("sprite").getAsString().startsWith("dialogmenu:model/"))
                            aliases.put(entry.get("sprite").getAsString(), entry.get("resource").getAsString());
                    }
                    for (var model : InventoryAssets.json("models.json").getAsJsonObject("models").entrySet())
                        for (var face : model.getValue().getAsJsonArray()) {
                            String sprite = face.getAsJsonObject().get("sprite").getAsString();
                            String alias = aliases.get(ModelPack.itemSprite(sprite));
                            if (alias == null) throw new AssertionError("Missing item sprite: " + sprite);
                            String input = "assets/" + sprite.replace(":", "/textures/") + ".png";
                            String output = "assets/" + alias.replace(":", "/textures/") + ".png";
                            if (alias.startsWith("dialogmenu:model_texture/")) {
                                if (!Arrays.equals(InventoryAssets.files().get(input), zip.getInputStream(zip.getEntry(output)).readAllBytes()))
                                    throw new AssertionError("Fallback model texture changed: " + sprite);
                            } else if (!alias.equals(sprite)) throw new AssertionError("Existing texture remapped: " + sprite);
                            checks++;
                        }
                    for (String name : List.of("blocks", "banner_patterns", "chests", "shulker_boxes", "decorated_pot"))
                        if (zip.getEntry("assets/minecraft/atlases/" + name + ".json") != null)
                            throw new AssertionError("Model export modifies world atlas: " + name);
                    if (!snapshot.fonts().modelPacks(data).isEmpty())
                        throw new AssertionError("26.2 exported a second unadapted model pack");
                    String component = GsonComponentSerializer.gson().serialize(
                        snapshot.fonts().model("minecraft:oak_log", 20, 0, 0));
                    if (!component.contains("dialogmenu:model/minecraft/block/oak_log"))
                        throw new AssertionError("Model component does not use isolated item sprites");
                    checks += 7;
                }
            }
            Path export = Files.createDirectories(data.resolve("export"));
            Path atlas = export.resolve("assets/minecraft/atlases/items.json");
            Files.createDirectories(atlas.getParent());
            String existing = "{\"type\":\"single\",\"resource\":\"example:icon\",\"sprite\":\"example:icon\"}";
            Files.writeString(atlas, "{\"sources\":[" + existing + "]}");
            snapshot.fonts().modelPack().write(export);
            JsonArray sources = JsonParser.parseString(Files.readString(atlas)).getAsJsonObject().getAsJsonArray("sources");
            if (!sources.contains(JsonParser.parseString(existing))) throw new AssertionError("Existing atlas entry lost");
            snapshot.fonts().modelPack().write(export);
            if (!sources.equals(JsonParser.parseString(Files.readString(atlas)).getAsJsonObject().getAsJsonArray("sources")))
                throw new AssertionError("Repeated export duplicates model entries");
            checks += 2;
        }
        System.out.println("PASS model compatibility checks=" + checks + " (offline)");
        return checks;
    }

    private static String text(ZipFile zip, String name) throws Exception {
        var entry = zip.getEntry(name);
        if (entry == null) throw new AssertionError("Missing model resource: " + name);
        try (var input = zip.getInputStream(entry)) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
    }
}
