package dev.dialogmenu;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

/** Real registry files, CE textures, reload snapshots and outgoing object components. */
final class ImageRegistryTests {
    private static int checks;
    private static final Context CONTEXT = new Context() {
        public String expand(String text) { return text; }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
    };
    private static final String REGISTRY = """
        {"textures":{
          "demo:a":{"source":"craftengine:demo/gui/a.png"},
          "demo:b":{"source":"craftengine:demo/gui/b.png"},
          "demo:alias":{"source":"craftengine:demo/gui/b.png"}
        }}
        """;
    private static final String TINTED_REGISTRY = REGISTRY.replace(
        "\"demo:b\":{\"source\":\"craftengine:demo/gui/b.png\"}",
        "\"demo:b\":{\"source\":\"craftengine:demo/gui/b.png\",\"tintable\":true}");

    static int run(Path root) throws Exception {
        checks = 0;
        Path data = Files.createTempDirectory(root, "image-registry-");
        Path ce = Files.createDirectories(data.resolve("ce/demo/resourcepack/assets/demo/textures/gui"));
        Files.createDirectories(data.resolve("images"));
        Files.createDirectories(data.resolve("layouts"));
        BufferedImage small = image(16, 16), large = image(256, 256);
        ImageIO.write(small, "png", ce.resolve("a.png").toFile());
        ImageIO.write(large, "png", ce.resolve("b.png").toFile());
        Path registry = data.resolve("images/registry.json"), menu = data.resolve("layouts/probe.yml");
        Files.writeString(registry, REGISTRY);
        Files.writeString(menu, menu("a", 64, 37, 0, 0, "#FFFFFF"));
        Files.writeString(data.resolve("config.yml"), """
            default-layout: probe
            canvas-width: 512
            max-canvas-pixels: 1048576
            text-scales: [1]
            canvas-scales: [1]
            text-colors: ['#55AAFF']
            resource-build-cpu-percent: 100
            craftengine-resources: ce
            """);
        Path originalPack;
        byte[] originalBytes;
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            var defaults = engine.prepare().get(90, TimeUnit.SECONDS);
            check(registrations(defaults.snapshot().pack()) == 2, "Default registry exports one original per unique source, not palette copies");
            Files.writeString(menu, menu("a", 64, 37, 0, 0, "#123456"));
            var originalColor = engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS);
            check(!originalColor.resourcesChanged(), "Unprepared image color is ignored when tint is disabled");
            assertTint(originalColor.snapshot(), 0xFFFFFF);
            Files.writeString(registry, TINTED_REGISTRY);
            var first = engine.prepare(ReloadEngine.Mode.RESOURCE).get(90, TimeUnit.SECONDS);
            check(first.resourcesChanged(), "Enabling tint is a resource change");
            originalPack = first.snapshot().pack();
            originalBytes = Files.readAllBytes(originalPack);
            var stamp = Files.getLastModifiedTime(originalPack);
            var snapshot = first.snapshot();
            check(snapshot.assets().sources().contains("registered:demo:b"), "Unused registration appears in the catalog");
            try (ZipFile zip = new ZipFile(originalPack.toFile())) {
                JsonObject atlas = JsonParser.parseString(new String(zip.getInputStream(
                    zip.getEntry("assets/minecraft/atlases/items.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                var sources = atlas.getAsJsonArray("sources");
                check(userRegistrations(sources) == 18, "One original A plus 17 colors for opted-in B; disabled alias shares only the white copy");
                String sprite = sources.asList().stream().map(JsonElement::getAsJsonObject)
                    .map(value -> value.get("sprite").getAsString())
                    .filter(value -> value.contains(FontPack.hash(large))).findFirst().orElseThrow();
                BufferedImage exported = ImageIO.read(zip.getInputStream(zip.getEntry(
                    "assets/dialogmenu/textures/" + sprite.substring("dialogmenu:".length()) + ".png")));
                for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++)
                    check(exported.getRGB(x + 2, y + 2) == large.getRGB(x, y), "Complete 256px source pixels preserved");
            }
            for (int width : List.of(255, 256, 257, 300, 512)) {
                Files.writeString(menu, menu("b", width, 257, 3, 7, "#55AAFF"));
                var result = engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS);
                snapshot = result.snapshot();
                check(!result.resourcesChanged() && snapshot.pack().equals(originalPack), "Switching to unused registered B and resizing keeps the pack");
                check(Arrays.equals(originalBytes, Files.readAllBytes(originalPack))
                    && stamp.equals(Files.getLastModifiedTime(originalPack)), "Geometry reload neither rewrites nor replaces ZIP");
                var doc = snapshot.documents().get("probe");
                var frame = snapshot.renderer().drawRuntime(doc.menu(), CONTEXT, 640, 1048576);
                check(frame.image() == null && frame.sprites().getFirst().image() == null, "Runtime creates no page or target-sized bitmap");
                check(frame.sprites().getFirst().original().getWidth() == 256, "Source does not change with display width");
                var output = new SpriteCanvas().encode(doc.menu(), frame, CONTEXT, snapshot.fonts(),
                    region -> ClickEvent.runCommand("/registered"));
                List<JsonObject> objects = new ArrayList<>();
                collect(JsonParser.parseString(GsonComponentSerializer.gson().serialize(output.component())), objects);
                check(!objects.isEmpty(), "Actual outgoing object sprite is present");
                for (JsonObject object : objects) {
                    int payload = object.get("shadow_color").getAsInt();
                    check(((payload >>> 11) & 1023) + 1 == Math.min(width, 257),
                        "Outgoing payload carries proportional target height, not independent XY dimensions");
                    check(object.get("sprite").getAsString().endsWith("_" + snapshot.fonts().imageTint(0xFF55AAFF)),
                        "The requested prepared tint is selected");
                }
                check(hasHit(output.component()),
                    "Visible image has independent native click coverage");
            }
            Files.writeString(menu, menu("b", 64, 37, 0, 0, "#123456"));
            rejects(() -> engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS),
                "Opted-in image still rejects a color absent from the prepared palette");
            Files.writeString(menu, menu("alias", 64, 37, 0, 0, "#123456"));
            var disabledAlias = engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS);
            check(!disabledAlias.resourcesChanged(), "A disabled alias does not inherit its same-source sibling's tint permission");
            assertTint(disabledAlias.snapshot(), 0xFFFFFF);
            Files.writeString(menu, menu("b", 256, 256, 0, 0, "#55AAFF"));
            var enabled = engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS);
            assertTint(enabled.snapshot(), 0x55AAFF);
            var enabledDoc = enabled.snapshot().documents().get("probe");
            var preview = enabled.snapshot().renderer().draw(enabledDoc.menu(), CONTEXT, 640, 1048576);
            int expected = large.getRGB(100, 100);
            expected = (expected & 0xFF000000) | (((expected >> 16 & 255) * 0x55 / 255) << 16)
                | (((expected >> 8 & 255) * 0xAA / 255) << 8) | (expected & 255);
            check(preview.image().getRGB(192 + 100, 192 + 100) == expected, "Opted-in preview actually multiplies source RGB");
            Files.writeString(menu, menu("alias", 143, 37, -20, -16, "#FFFFFF"));
            var alias = engine.prepare(new ReloadEngine.Request(ReloadEngine.Mode.YAML, "probe")).get(90, TimeUnit.SECONDS);
            check(!alias.resourcesChanged() && alias.snapshot().pack().equals(originalPack), "Single-menu reload can switch to a prepared alias");
            Files.writeString(menu, menu("missing", 64, 37, 0, 0, "#FFFFFF"));
            rejects(() -> engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS), "Unknown registration must not silently rebuild or rasterize");
            Files.writeString(menu, menu("alias", 143, 37, -20, -16, "#FFFFFF"));
            check(engine.prepare(ReloadEngine.Mode.YAML).get().snapshot().pack().equals(originalPack), "Failed edit retains previous pack");
            Files.writeString(registry, "{broken");
            rejects(() -> engine.prepare(ReloadEngine.Mode.RESOURCE).get(90, TimeUnit.SECONDS), "Invalid registry rejects resource preparation");
            check(engine.prepare(ReloadEngine.Mode.YAML).get().snapshot().pack().equals(originalPack), "YAML reuses compiled registry, not pending broken file");
        }
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            var restored = engine.restore().get(90, TimeUnit.SECONDS);
            check(restored.snapshot().pack().equals(originalPack), "Persisted registry restores without reading broken disk registry");
            assertTint(restored.snapshot(), 0xFFFFFF);
            Files.writeString(menu, menu("b", 64, 37, 0, 0, "#55AAFF"));
            assertTint(engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS).snapshot(), 0x55AAFF);
            Files.writeString(menu, menu("a", 255, 256, 12, 9, "#55AAFF"));
            var changed = engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS);
            check(!changed.resourcesChanged(), "Restored snapshot still supports unused geometry and original color");
            assertTint(changed.snapshot(), 0xFFFFFF);
            Files.writeString(registry, REGISTRY);
            var disabled = engine.prepare(ReloadEngine.Mode.RESOURCE).get(90, TimeUnit.SECONDS);
            check(disabled.resourcesChanged() && registrations(disabled.snapshot().pack()) == 2,
                "Disabling tint removes the extra color resources");
            Files.writeString(menu, menu("b", 64, 37, 0, 0, "#123456"));
            assertTint(engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS).snapshot(), 0xFFFFFF);
            large.setRGB(17, 23, 0xFF010203);
            ImageIO.write(large, "png", ce.resolve("b.png").toFile());
            var refreshed = engine.prepare(ReloadEngine.Mode.RESOURCE).get(90, TimeUnit.SECONDS);
            check(refreshed.resourcesChanged() && !refreshed.snapshot().pack().equals(originalPack), "Real CE texture change requires a new resource pack");
            check(refreshed.snapshot().assets().image("registered:demo:b").getRGB(17, 23) == 0xFF010203,
                "Source replacement actually reaches registered data");
        }
        Files.writeString(registry, REGISTRY.replace("demo/gui/a.png", "demo/../../escape.png"));
        rejects(() -> new VanillaAssets(data.resolve("images"), data.resolve("ce")), "CE path traversal is rejected");
        Files.writeString(registry, REGISTRY.replace("demo/gui/b.png", "demo/gui/missing.png"));
        rejects(() -> new VanillaAssets(data.resolve("images"), data.resolve("ce")), "Unused broken registry entry is rejected too");
        Files.writeString(registry, REGISTRY);
        Files.writeString(ce.resolve("a.png.mcmeta"), "{\"animation\":{}}");
        rejects(() -> new VanillaAssets(data.resolve("images"), data.resolve("ce")), "Animation is not silently flattened");
        Files.delete(ce.resolve("a.png.mcmeta"));
        for (String invalid : List.of("\"true\"", "1", "null", "{}")) {
            Files.writeString(registry, TINTED_REGISTRY.replace("\"tintable\":true", "\"tintable\":" + invalid));
            rejects(() -> new VanillaAssets(data.resolve("images"), data.resolve("ce")),
                "Tint opt-in accepts only a JSON boolean");
        }
        BufferedImage atlas = image(32, 16);
        ImageIO.write(atlas, "png", ce.resolve("atlas.png").toFile());
        Files.writeString(registry, """
            {"textures":{"demo:cell":{"source":"craftengine:demo/gui/atlas.png",
              "tintable":false,"atlas":{"columns":2,"rows":1,"column":1,"row":0}}}}
            """);
        BufferedImage cell = new VanillaAssets(data.resolve("images"), data.resolve("ce")).image("registered:demo:cell");
        check(cell.getWidth() == 16 && cell.getHeight() == 16, "Explicit atlas cell keeps its complete dimensions");
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
            check(cell.getRGB(x, y) == atlas.getRGB(x + 16, y), "Explicit source atlas cell pixels preserved");
        Path export = Files.createDirectories(data.resolve("repeat-export"));
        var images = new RegisteredImages();
        images.prepare(Map.of("registered:demo:b", new ImageRegistry.Entry(large, false)), List.of(0), 0);
        images.write(export);
        images.write(export);
        JsonArray sources = JsonParser.parseString(Files.readString(export.resolve("assets/minecraft/atlases/items.json")))
            .getAsJsonObject().getAsJsonArray("sources");
        check(sources.size() == 1, "Repeated export does not duplicate atlas registrations");
        new RegisteredImages().write(export);
        check(JsonParser.parseString(Files.readString(export.resolve("assets/minecraft/atlases/items.json")))
            .getAsJsonObject().getAsJsonArray("sources").isEmpty(), "Removed registrations do not leave stale atlas entries");
        legacyImages(data, small, large);
        localImages(Files.createTempDirectory(root, "local-registry-"));
        paletteLifecycle(Files.createTempDirectory(root, "palette-16-"));
        System.out.println("PASS registered-image checks=" + checks + " (offline, no client)");
        return checks;
    }

    private static void paletteLifecycle(Path data) throws Exception {
        int before = checks;
        List<String> defaults = List.of("000000", "0000AA", "00AA00", "00AAAA",
            "AA0000", "AA00AA", "FFAA00", "AAAAAA", "555555", "5555FF",
            "55FF55", "55FFFF", "FF5555", "FF55FF", "FFFF55", "FFFFFF");
        Path images = Files.createDirectories(data.resolve("images"));
        Files.createDirectories(data.resolve("layouts"));
        ImageIO.write(image(16, 16), "png", images.resolve("a.png").toFile());
        Files.writeString(images.resolve("registry.json"), "{\"textures\":{\"a.png\":{\"tintable\":true}}}");
        Path menu = data.resolve("layouts/probe.yml"), config = data.resolve("config.yml");
        String settings = """
            default-layout: probe
            canvas-width: 300
            text-scales: [1]
            canvas-scales: [1]
            resource-build-cpu-percent: 100
            text-colors: []
            """;
        Files.writeString(config, settings);
        Files.writeString(menu, paletteMenu("FFFFFF", 0));
        Path preparedPack;
        byte[] preparedBytes;
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            var first = engine.prepare().get(90, TimeUnit.SECONDS).snapshot();
            Path pack = first.pack();
            byte[] bytes = Files.readAllBytes(pack);
            var stamp = Files.getLastModifiedTime(pack);
            check(registrations(pack) == 16, "Default palette prepares exactly 16 tints, not 32 or a full RGB table");
            Path exported = unpack(pack, data.resolve("default-pack"));
            for (int i = 0; i < defaults.size(); i++) {
                Files.writeString(menu, paletteMenu(defaults.get(i), i));
                var changed = engine.prepare(new ReloadEngine.Request(ReloadEngine.Mode.YAML, "probe"))
                    .get(90, TimeUnit.SECONDS);
                check(!changed.resourcesChanged() && changed.snapshot().pack().equals(pack)
                    && Arrays.equals(bytes, Files.readAllBytes(pack)) && stamp.equals(Files.getLastModifiedTime(pack)),
                    "Each default color and new geometry reuses the exact prepared ZIP");
                assertTint(changed.snapshot(), Integer.parseInt(defaults.get(i), 16));
                checks += RowPackTests.verify(exported, changed.snapshot().documents().get("probe").menu(),
                    changed.snapshot().renderer(), CONTEXT, changed.snapshot().fonts(), 300);
            }
            Files.writeString(menu, paletteMenu("123456", 2));
            rejects(() -> engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS),
                "A new HEX color cannot silently change the resources during YAML reload");
            Files.writeString(menu, paletteMenu("FFFFFF", 0));
            check(engine.prepare(ReloadEngine.Mode.YAML).get().snapshot().pack().equals(pack),
                "An unprepared color failure keeps the usable default palette");
            Files.writeString(config, settings.replace("text-colors: []",
                "text-colors: ['#123456', '#123456', '#FFFFFF']"));
            rejects(() -> engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS),
                "Declaring an extra color requires resource preparation, not just YAML reload");
            var added = engine.prepare(ReloadEngine.Mode.ALL).get(90, TimeUnit.SECONDS);
            preparedPack = added.snapshot().pack();
            preparedBytes = Files.readAllBytes(preparedPack);
            check(added.resourcesChanged() && !preparedPack.equals(pack) && registrations(preparedPack) == 17,
                "One extra HEX adds one tint; duplicate declarations and built-in colors do not duplicate resources");
            var addedStamp = Files.getLastModifiedTime(preparedPack);
            Path addedExport = unpack(preparedPack, data.resolve("extra-pack"));
            for (String color : List.of("123456", "000000", "55FFFF", "123456")) {
                Files.writeString(menu, paletteMenu(color, 3));
                var changed = engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS);
                check(!changed.resourcesChanged() && changed.snapshot().pack().equals(preparedPack)
                    && Arrays.equals(preparedBytes, Files.readAllBytes(preparedPack))
                    && addedStamp.equals(Files.getLastModifiedTime(preparedPack)),
                    "Prepared HEX and default colors can be selected repeatedly without rebuilding");
                checks += RowPackTests.verify(addedExport, changed.snapshot().documents().get("probe").menu(),
                    changed.snapshot().renderer(), CONTEXT, changed.snapshot().fonts(), 300);
            }
        }
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            var restored = engine.restore().get(90, TimeUnit.SECONDS);
            check(restored.snapshot().pack().equals(preparedPack), "Restore retains the extended palette resource pack");
            Files.writeString(menu, paletteMenu("123456", 7));
            var changed = engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS);
            check(!changed.resourcesChanged() && changed.snapshot().pack().equals(preparedPack)
                && Arrays.equals(preparedBytes, Files.readAllBytes(preparedPack)),
                "Custom color and new geometry remain reusable after restart");
            checks += RowPackTests.verify(data.resolve("extra-pack"), changed.snapshot().documents().get("probe").menu(),
                changed.snapshot().renderer(), CONTEXT, changed.snapshot().fonts(), 300);
        }
        System.out.println("PASS palette lifecycle checks=" + (checks - before) + " (16 defaults, extra HEX, frozen ZIP, restore)");
    }

    private static String paletteMenu(String color, int variant) {
        return localMenu("a.png", 31 + variant % 4 * 64, 17 + variant % 3 * 9, "#" + color)
            .replace("width: 640, height: 640", "width: 300, height: 180")
            + """
                  label:
                    type: text
                    content: A
                    color: '#%s'
                    size: %s
                    offset: {x: %s, y: 50}
                    actions: close
                """.formatted(color, 8 + variant % 3 * 2, variant % 5 * 3);
    }

    private static Path unpack(Path pack, Path output) throws Exception {
        Files.createDirectories(output);
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            for (var entry : zip.stream().filter(value -> !value.isDirectory()).toList()) {
                Path target = output.resolve(entry.getName());
                Files.createDirectories(target.getParent());
                Files.copy(zip.getInputStream(entry), target);
            }
        }
        return output;
    }

    private static void localImages(Path data) throws Exception {
        Path images = Files.createDirectories(data.resolve("images/sub"));
        Files.createDirectories(data.resolve("layouts"));
        Path menu = data.resolve("layouts/probe.yml");
        ImageIO.write(image(16, 16), "png", images.resolve("a.png").toFile());
        ImageIO.write(image(256, 256), "png", images.resolve("b.png").toFile());
        Files.writeString(menu, localMenu("sub/a.png", 64, 37, "#123456"));
        Files.writeString(data.resolve("config.yml"), """
            default-layout: probe
            canvas-width: 512
            max-canvas-pixels: 1048576
            text-scales: [1]
            canvas-scales: [1]
            text-colors: ['#55AAFF']
            resource-build-cpu-percent: 100
            """);
        Path pack;
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            var first = engine.prepare().get(90, TimeUnit.SECONDS);
            pack = first.snapshot().pack();
            check(registrations(pack) == 2, "Recursive scanning prepares unused PNGs without a registry file");
            check(first.snapshot().assets().sources().contains("sub/b.png"), "Scanned images retain their relative-path catalog entry");
            byte[] bytes = Files.readAllBytes(pack);
            var stamp = Files.getLastModifiedTime(pack);
            for (int width : List.of(255, 256, 257, 300, 512)) {
                Files.writeString(menu, localMenu("sub/b.png", width, 257, "#123456"));
                var result = engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS);
                check(!result.resourcesChanged() && result.snapshot().pack().equals(pack)
                    && Arrays.equals(bytes, Files.readAllBytes(pack)) && stamp.equals(Files.getLastModifiedTime(pack)),
                    "Previously unused local image and new geometry use the same ZIP");
                var frame = result.snapshot().renderer().drawRuntime(result.snapshot().documents().get("probe").menu(),
                    CONTEXT, 640, 1048576);
                check(frame.image() == null && frame.sprites().getFirst().image() == null
                    && frame.sprites().getFirst().original().getWidth() == 256,
                    "Local runtime retains the complete source, not a scaled bitmap");
                check(frame.sprites().getFirst().width() == Math.min(width, 257)
                    && frame.sprites().getFirst().height() == Math.min(width, 257),
                    "Square source is proportionally fitted into the requested image box");
                assertTint(result.snapshot(), 0xFFFFFF);
            }
            Files.writeString(data.resolve("images/registry.json"), """
                {"textures":{"sub/b.png":{"tintable":true},
                  "demo:local":{"source":"sub/b.png"}}}
                """);
            Files.writeString(menu, localMenu("sub/b.png", 300, 257, "#55AAFF"));
            engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS);
            var tinted = engine.prepare(ReloadEngine.Mode.RESOURCE).get(90, TimeUnit.SECONDS);
            Files.writeString(menu, localMenu("sub/b.png", 300, 257, "#55AAFF"));
            assertTint(engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS).snapshot(), 0x55AAFF);
            Files.writeString(menu, localMenu("registered:demo:local", 300, 257, "#123456"));
            assertTint(engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS).snapshot(), 0xFFFFFF);
            check(registrations(tinted.snapshot().pack()) == 18, "Tint override and independent local alias share original pixels");
            ImageIO.write(image(32, 16), "png", images.resolve("new.png").toFile());
            Files.writeString(menu, localMenu("sub/new.png", 64, 37, "#FFFFFF"));
            rejects(() -> engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS),
                "YAML-only cannot read newly added disk images");
            var added = engine.prepare().get(90, TimeUnit.SECONDS);
            check(added.resourcesChanged() && registrations(added.snapshot().pack()) == 19,
                "New unused or used PNG changes the resource snapshot");
            Files.writeString(menu, localMenu("sub/a.png", 64, 37, "#FFFFFF"));
            engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS);
            Files.delete(images.resolve("new.png"));
            var removed = engine.prepare(ReloadEngine.Mode.RESOURCE).get(90, TimeUnit.SECONDS);
            check(removed.resourcesChanged() && registrations(removed.snapshot().pack()) == 18,
                "Deleting an unused PNG removes its prepared resource");
            pack = removed.snapshot().pack();
            Files.writeString(images.resolve("a.png"), "broken pending image");
        }
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            var restored = engine.restore().get(90, TimeUnit.SECONDS);
            check(restored.snapshot().pack().equals(pack), "Restore uses frozen local images, not pending disk bytes");
            Files.writeString(menu, localMenu("sub/a.png", 257, 37, "#FFFFFF"));
            assertTint(engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS).snapshot(), 0xFFFFFF);
            rejects(() -> engine.prepare(ReloadEngine.Mode.RESOURCE).get(90, TimeUnit.SECONDS),
                "A broken scanned PNG rejects resource rebuilding while preserving the old snapshot");
        }
        ImageIO.write(image(16, 16), "png", images.resolve("a.png").toFile());
        var assets = new VanillaAssets(data.resolve("images"));
        ImageIO.write(image(16, 32), "png", images.resolve("late.png").toFile());
        rejects(assets::verifyInputs, "A PNG added during compilation cannot escape the input check");
        Files.delete(images.resolve("late.png"));
        Files.writeString(images.resolve("a.png.mcmeta"), "{\"animation\":{}}");
        rejects(() -> new VanillaAssets(data.resolve("images")), "Local animations cannot silently become static sprites");
        Files.delete(images.resolve("a.png.mcmeta"));
        Path outside = data.resolve("outside.png");
        ImageIO.write(image(16, 16), "png", outside.toFile());
        Files.createSymbolicLink(images.resolve("escape.png"), outside);
        rejects(() -> new VanillaAssets(data.resolve("images")), "Scanned file symlinks cannot escape images/");
        Files.delete(images.resolve("escape.png"));
        Files.writeString(data.resolve("images/registry.json"),
            "{\"textures\":{\"demo:bad\":{\"source\":\"../outside.png\"}}}");
        rejects(() -> new VanillaAssets(data.resolve("images")), "Explicit local aliases cannot traverse outside images/");
    }

    private static String localMenu(String source, int width, int height, String color) {
        return menu("local", width, height, 0, 0, color).replace("registered:demo:local", source);
    }

    private static void legacyImages(Path data, BufferedImage small, BufferedImage large) throws Exception {
        ImageIO.write(small, "png", data.resolve("images/local.png").toFile());
        ImageIO.write(large, "png", data.resolve("images/large.png").toFile());
        var renderer = new MenuRenderer(new VanillaAssets(data.resolve("images"), data.resolve("ce")));
        for (String fields : List.of(
            "source: local.png", "source: large.png",
            "source: local.png\n    opacity: 128\n    rotation: {z: 15}",
            "source: minecraft:item/book",
            "source: craftengine:demo/gui/a.png",
            "source: craftengine:demo/gui/a.png\n    reuse-texture: true")) {
            String source = """
                schema-version: 1
                id: legacy
                canvas: {width: 300, height: 180}
                frontend:
                  image:
                    type: image
                    width: 32
                    height: 32
                    color: '#123456'
                    actions: 'tell: IMAGE'
                    %s
                """.formatted(fields);
            var colored = LayoutStore.loadText("legacy", source).get("legacy");
            var original = LayoutStore.loadText("legacy", source.replace("#123456", "#FFFFFF")).get("legacy");
            if (fields.contains("rotation")) {
                rejects(() -> renderer.drawRuntime(colored.menu(), CONTEXT, 300, 131072),
                    "Unsupported runtime transforms cannot silently rasterize a new bitmap");
                continue;
            }
            var fonts = FontPack.create(List.of(original), renderer, CONTEXT, List.of(300), 131072);
            var preview = renderer.draw(colored.menu(), CONTEXT, 300, 131072);
            var baseline = renderer.draw(original.menu(), CONTEXT, 300, 131072);
            check(Arrays.equals(preview.image().getRGB(0, 0, 300, 180, null, 0, 300),
                baseline.image().getRGB(0, 0, 300, 180, null, 0, 300)),
                "Legacy image preview preserves original pixels and opacity: " + fields);
            var frame = renderer.drawRuntime(colored.menu(), CONTEXT, 300, 131072);
            var output = new SpriteCanvas().encode(colored.menu(), frame, CONTEXT, fonts,
                region -> ClickEvent.runCommand("/image"));
            var expected = new SpriteCanvas().encode(original.menu(), baseline, CONTEXT, fonts,
                region -> ClickEvent.runCommand("/image"));
            check(output.equals(expected), "Legacy image color-only edit keeps original resources, actions and output: " + fields);
        }
    }

    private static int registrations(Path pack) throws Exception {
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            return userRegistrations(JsonParser.parseString(new String(zip.getInputStream(
                zip.getEntry("assets/minecraft/atlases/items.json")).readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("sources"));
        }
    }
    private static int userRegistrations(JsonArray sources) throws Exception {
        Set<String> defaults = new HashSet<>();
        for (var image : InventoryAssets.images().values()) defaults.add(FontPack.hash(image.image()));
        Set<String> nativeSprites = new HashSet<>();
        InventoryAssets.nativeSprites().values().forEach(value -> nativeSprites.add(RegisteredImages.nativeSpriteId(value)));
        return (int) sources.asList().stream().filter(value -> {
            String sprite = value.getAsJsonObject().get("sprite").getAsString();
            return !sprite.startsWith("dialogmenu:registered/tile_") && !sprite.startsWith("dialogmenu:model/") && !nativeSprites.contains(sprite)
                && defaults.stream().noneMatch(hash -> sprite.startsWith("dialogmenu:registered/" + hash + "_"));
        }).count();
    }

    private static void assertTint(ReloadEngine.Snapshot snapshot, int expectedRgb) throws Exception {
        var doc = snapshot.documents().get("probe");
        var frame = snapshot.renderer().drawRuntime(doc.menu(), CONTEXT, 640, 1048576);
        var output = new SpriteCanvas().encode(doc.menu(), frame, CONTEXT, snapshot.fonts(),
            region -> ClickEvent.runCommand("/registered"));
        List<JsonObject> objects = new ArrayList<>();
        collect(JsonParser.parseString(GsonComponentSerializer.gson().serialize(output.component())), objects);
        check(!objects.isEmpty(), "Tint policy preserves actual outgoing object components");
        int tint = snapshot.fonts().imageTint(expectedRgb);
        try (ZipFile zip = new ZipFile(snapshot.pack().toFile())) {
            for (JsonObject object : objects) {
                String sprite = object.get("sprite").getAsString();
                BufferedImage wrapper = ImageIO.read(zip.getInputStream(zip.getEntry(
                    "assets/dialogmenu/textures/" + sprite.substring("dialogmenu:".length()) + ".png")));
                check((wrapper.getRGB(1, 0) >>> 24) == tint, "Selected resource carries the permitted color");
            }
        }
        check(hasHit(output.component()), "Changing tint policy preserves image actions");
    }

    static BufferedImage image(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            image.setRGB(x, y, ((x + y) % 7 == 0 ? 0x80 : 0xFF) << 24 | x % 256 << 16 | y % 256 << 8 | 200);
        return image;
    }

    private static String menu(String id, int width, int height, int x, int y, String color) {
        return """
            schema-version: 1
            id: probe
            canvas: {width: 640, height: 640}
            frontend:
              image:
                type: image
                source: 'registered:demo:%s'
                width: %d
                height: %d
                offset: {x: %d, y: %d}
                color: '%s'
                actions: 'tell: REGISTERED'
                tooltip: Registered
            """.formatted(id, width, height, x, y, color);
    }

    private static void collect(JsonElement element, List<JsonObject> objects) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("sprite")) objects.add(object);
            for (var entry : object.entrySet()) collect(entry.getValue(), objects);
        } else if (element.isJsonArray()) for (JsonElement child : element.getAsJsonArray()) collect(child, objects);
    }
    private static boolean hasHit(net.kyori.adventure.text.Component component) {
        return RgbaHits.FONT.equals(component.font()) && component.clickEvent() != null
            || component.children().stream().anyMatch(ImageRegistryTests::hasHit);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private static void rejects(Work work, String message) throws Exception {
        try { work.run(); }
        catch (java.io.IOException | IllegalArgumentException | ExecutionException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(args[0]));
        if (args.length > 1 && args[1].equals("fixtures")) {
            for (int size : List.of(16, 256)) {
                BufferedImage source = image(size, size);
                ImageIO.write(source, "png", root.resolve("source-" + size + ".png").toFile());
                ImageIO.write(RegisteredImages.wrap(source, 0), "png", root.resolve("wrapped-" + size + ".png").toFile());
            }
            BufferedImage wide = image(256, 16);
            ImageIO.write(wide, "png", root.resolve("source-wide.png").toFile());
            ImageIO.write(RegisteredImages.wrap(wide, 0), "png", root.resolve("wrapped-wide.png").toFile());
        } else run(root);
    }
}
