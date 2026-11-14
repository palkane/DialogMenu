package dev.dialogmenu;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

/** Default inventory resources must work without settings, registration or prior references. */
final class InventoryAssetsTests {
    private static int checks;
    private static final Context CONTEXT = new Context() {
        public String expand(String text) { return text; }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
    };
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static String layout(String node) {
        return "schema-version: 1\nid: probe\ncanvas: {width: 300, height: 180}\nfrontend:\n  icon: " + node + "\n";
    }
    private static void objects(JsonElement node, List<JsonObject> result) {
        if (node.isJsonArray()) for (var child : node.getAsJsonArray()) objects(child, result);
        if (!node.isJsonObject()) return;
        var object = node.getAsJsonObject();
        if (object.has("sprite")) result.add(object);
        for (var entry : object.entrySet()) objects(entry.getValue(), result);
    }
    static int run(Path root) throws Exception {
        var catalog = InventoryAssets.json("catalog.json");
        try (var zip = new ZipInputStream(new ByteArrayInputStream(InventoryAssets.files().get("resourcepack.zip")))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;)
                check(!entry.getName().endsWith(".png"), "Native 3D texture copies are forbidden in the download");
        }
        Map<String, Integer> spaces = new HashMap<>();
        ModelPack.faceSpaces(spaces);
        RgbaOffsets.nativeSpaces(spaces);
        check(spaces.get(Character.toString(0xF1020)) == -65536
            && spaces.get(Character.toString(0xF1021)) == 65536,
            "Native image scope uses balanced positioning outside ordinary GUI coordinates");
        for (int face = 0; face < 8192; face++) {
            int shift = ModelPack.faceMove(face, false).codePoints().map(cp -> spaces.get(Character.toString(cp))).sum();
            int reset = ModelPack.faceMove(face, true).codePoints().map(cp -> spaces.get(Character.toString(cp))).sum();
            check(shift == -(face + 33) * 2000 && shift + reset == 0,
                "Every geometry bank must preserve native cursor advance");
        }
        check(catalog.size() > 1600 && catalog.entrySet().stream().noneMatch(
            entry -> entry.getValue().getAsJsonObject().get("kind").getAsString().equals("unsupported")),
            "Every inventory entry is accounted for, with no silently unsupported IDs");
        for (String id : List.of("stone", "chest", "player_head", "copper_golem_statue", "decorated_pot", "conduit"))
            check(catalog.getAsJsonObject("minecraft:" + id).get("kind").getAsString().equals("model"),
                id + " must retain true face geometry");
        for (String id : List.of("diamond", "trident", "clock", "compass", "potion", "leather_chestplate"))
            check(catalog.getAsJsonObject("minecraft:" + id).get("kind").getAsString().equals("image"),
                id + " must use its inventory 2D appearance");
        Path data = Files.createTempDirectory(root, "default-inventory-");
        Files.createDirectories(data.resolve("layouts"));
        Files.writeString(data.resolve("config.yml"),
            "default-layout: probe\ntext-scales: [1]\ncanvas-scales: [1]\nresource-build-cpu-percent: 100\n");
        Path file = data.resolve("layouts/probe.yml");
        Files.writeString(file, layout("{type: text, content: A}"));
        List<Path> packs;
        Map<Path, byte[]> bytes = new HashMap<>();
        Map<Path, java.nio.file.attribute.FileTime> stamps = new HashMap<>();
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            var snapshot = engine.prepare().get().snapshot();
            packs = new ArrayList<>(snapshot.fonts().modelPacks(data));
            packs.add(snapshot.pack());
            check(packs.size() == (InventoryAssets.usesItemsAtlas() ? 1 : 3) && !Files.exists(data.resolve("models/settings.json")),
                "Default library is prepared even without references or client/compiler settings");
            long defaultBytes = 0;
            for (Path pack : packs) defaultBytes += Files.size(pack);
            check(defaultBytes <= 192 * 1024,
                "Complete inventory-only A snapshot must fit in 192 KiB, not per-face texture copies: " + defaultBytes);
            for (Path pack : packs) {
                bytes.put(pack, Files.readAllBytes(pack));
                stamps.put(pack, Files.getLastModifiedTime(pack));
            }
            for (String id : List.of("minecraft:item/diamond", "minecraft:inventory/trident",
                "model:minecraft:dirt", "model:minecraft:player_head", "model:minecraft:decorated_pot")) {
                boolean model = id.startsWith("model:");
                String node = model
                    ? "{type: image, source: '%s', size: 32, scale: 2, offset: {x: 7, y: -13}, actions: close, tooltip: icon}"
                    : "{type: image, source: '%s', width: 16, height: 16, scale: 2, offset: {x: 7, y: -13}, actions: close, tooltip: icon}";
                Files.writeString(file, layout(node.formatted(id)));
                var changed = engine.prepare(ReloadEngine.Mode.YAML).get();
                check(!changed.resourcesChanged(), "Unused default ID, proportional image scaling and offsets reuse the prepared pack");
                snapshot = changed.snapshot();
                var menu = snapshot.documents().get("probe").menu();
                var frame = snapshot.renderer().drawRuntime(menu, CONTEXT, 300, 131072);
                String json = GsonComponentSerializer.gson().serialize(new SpriteCanvas().encode(
                    menu, frame, CONTEXT, snapshot.fonts(), region -> ClickEvent.runCommand("/inventory")).component());
                check(json.contains("/inventory") && json.contains("row_hit"), "Visible inventory icon retains native clicks");
                List<JsonObject> parts = new ArrayList<>();
                objects(JsonParser.parseString(json), parts);
                check(!parts.isEmpty(), "Inventory source reaches actual object components");
                for (var part : parts) {
                    int payload = part.get("shadow_color").getAsInt();
                    check(model
                        ? ((payload >>> 11) & 1023) + 1 == 64
                        : ((payload >>> 11) & 1023) + 1 == 32,
                        "Models use the same proportional target size on both axes; images encode target height");
                }
                if (model) {
                    check(frame.sprites().isEmpty(), "3D remains real faces, never a baked model PNG");
                    var area = snapshot.fonts().modelArea(menu, menu.nodes().getFirst(), 300, 180);
                    check(area.width > 0 && area.height > 0 && area.contains(157, 102),
                        "Proportionally scaled model has positioned native hit bounds");
                } else if (id.equals("minecraft:item/diamond")) {
                    var glyphs = MenuText.glyphs(ResourceRegressionTests.textOnly(
                        GsonComponentSerializer.gson().deserialize(json)));
                    long shifts = glyphs.stream().filter(g -> g.code() == 0xF1020).count();
                    check(shifts > 0 && shifts == glyphs.stream().filter(g -> g.code() == 0xF1021).count(),
                        "Native inventory objects carry balanced scope markers in actual output");
                    check(glyphs.stream().filter(g -> g.code() == 0xF1020 || g.code() == 0xF1021)
                        .allMatch(g -> g.style().clickEvent() == null && g.style().hoverEvent() == null),
                        "Scope positioning never expands click or hover bounds");
                }
                for (Path pack : packs)
                    check(Arrays.equals(bytes.get(pack), Files.readAllBytes(pack))
                        && stamps.get(pack).equals(Files.getLastModifiedTime(pack)),
                        "Default source switch and geometry do not rewrite ZIP bytes, path or mtime");
            }
            var large = LayoutStore.loadText("probe",
                layout("{type: model, source: 'minecraft:stone', size: 32, scale: 18.75, actions: close, tooltip: large}")
                    .replace("{width: 300, height: 180}", "{width: 1014, height: 1024}"))
                .get("probe").menu();
            var largeFrame = snapshot.renderer().drawRuntime(large, CONTEXT, 1014, 1_048_576);
            check(MenuRenderer.modelProtocolFits(large, largeFrame), "600px model is not constrained by the removed 512 encoding");
            String largeJson = GsonComponentSerializer.gson().serialize(new SpriteCanvas().encode(
                large, largeFrame, CONTEXT, snapshot.fonts(), region -> ClickEvent.runCommand("/large")).component());
            List<JsonObject> largeParts = new ArrayList<>();
            objects(JsonParser.parseString(largeJson), largeParts);
            check(!largeParts.isEmpty() && largeParts.stream().allMatch(part ->
                ((part.get("shadow_color").getAsInt() >>> 11) & 1023) + 1 == 600),
                "Every large model face uses the same scalar size");
            check(largeJson.contains("/large") && largeJson.contains("row_hit") && largeJson.contains("show_text"),
                "Large model keeps actual click and hover components");
            for (Path pack : packs)
                check(Arrays.equals(bytes.get(pack), Files.readAllBytes(pack)),
                    "Scaling a model beyond 512 does not add resources");
            Files.createDirectories(data.resolve("images"));
            Files.writeString(data.resolve("images/registry.json"),
                "{\"models\":{\"minecraft:stone\":{\"mode\":\"auto\"}}}");
            try {
                ModelPack.prepare(data, snapshot.documents().values());
                throw new AssertionError("Default models bypassed registration validation");
            } catch (java.io.IOException expected) { checks++; }
        }
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            engine.restore().get();
            Files.writeString(file, layout("{type: image, source: 'model:minecraft:oak_stairs', size: 32, scale: 2}"));
            check(!engine.prepare(ReloadEngine.Mode.YAML).get().resourcesChanged(),
                "Persisted default library permits unused models without reading a pending invalid registry");
        }
        System.out.println("PASS default inventory checks=" + checks);
        return checks;
    }
}
