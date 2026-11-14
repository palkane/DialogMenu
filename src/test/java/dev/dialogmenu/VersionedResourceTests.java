package dev.dialogmenu;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

/** Real version libraries and complete reload/export/restore, with no client or network required. */
public final class VersionedResourceTests {
    private static int checks;
    private static final Context CONTEXT = new Context() {
        public String expand(String text) { return text; }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
    };

    public static void main(String[] args) throws Exception {
        Path libraries = Path.of(args[0]), output = Path.of(args[1]);
        Files.createDirectories(output);
        Path data = Files.createTempDirectory(output, "data-");
        Files.createDirectories(data.resolve("layouts"));
        Files.createDirectories(data.resolve("images"));
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++)
            image.setRGB(x, y, 0xFF000000 | x << 16 | y << 8 | (x ^ y));
        ImageIO.write(image, "png", data.resolve("images/full.png").toFile());
        Files.writeString(data.resolve("layouts/probe.yml"), """
            schema-version: 1
            id: probe
            canvas: {width: 300, height: 180}
            frontend:
              text: {type: text, content: A}
              model: {type: model, source: minecraft:stone, size: 32, offset: {x: -70}}
              image: {type: image, source: full.png, width: 48, height: 48, offset: {x: 70}}
            """);
        ReloadEngine.Snapshot previous = null;
        List<String> versions = Arrays.asList(args).subList(2, args.length);
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            for (String id : versions) {
                ResourceVersion version = new ResourceVersion(id);
                var library = new AssetLibrary(data, version);
                if (!version.equals(ResourceVersion.DEFAULT)) {
                    for (Path path : library.paths()) {
                        Files.createDirectories(path.getParent());
                        Files.copy(libraries.resolve(id).resolve(path.getFileName()), path, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                Files.writeString(data.resolve("config.yml"), """
                    default-layout: probe
                    text-scales: [1]
                    canvas-scales: [1]
                    resource-build-cpu-percent: 100
                    resource-version: '%s'
                    """.formatted(id));
                if (previous != null) {
                    try {
                        engine.prepare(ReloadEngine.Mode.YAML).get(120, TimeUnit.SECONDS);
                        throw new AssertionError("YAML reload accepted a different resource target");
                    } catch (java.util.concurrent.ExecutionException expected) {
                        check(expected.getCause().getMessage().contains("reload all"), "Version changes require resource rebuild");
                    }
                }
                var snapshot = engine.prepare().get(120, TimeUnit.SECONDS).snapshot();
                check(snapshot.assets().version().equals(version), "Assets use configured target");
                if (previous != null) check(!snapshot.pack().equals(previous.pack()), "Version switch creates a fresh pack");
                JsonObject components = new JsonObject();
                var serializer = GsonComponentSerializer.gson();
                for (String model : List.of("stone", "oak_stairs", "red_bed", "red_banner", "shield",
                        "dragon_head", "prismarine", "magma_block", "sculk_sensor")) {
                    String json = serializer.serialize(snapshot.fonts().model("minecraft:" + model, 64, 0, 0));
                    check(version.bitmap() ? json.contains("dialogmenu:model_bitmap") && !json.contains("\"sprite\"")
                        : json.contains("\"sprite\""), "Model uses target transport: " + model);
                    components.add(model, JsonParser.parseString(json));
                }
                String registered = serializer.serialize(snapshot.fonts().registeredImages().component(
                    "registered:full.png", snapshot.fonts().imageTint(0xFFFFFFFF), 173, 0, 0));
                check(version.bitmap() ? registered.contains("registered_bitmap") && !registered.contains("\"sprite\"")
                    : registered.contains("\"sprite\""), "Complete 256px image uses target transport");
                components.add("full_image", JsonParser.parseString(registered));
                Path exported = output.resolve(id);
                Files.createDirectories(exported);
                try (var zip = new ZipInputStream(Files.newInputStream(snapshot.pack()))) {
                    for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                        if (entry.isDirectory()) continue;
                        Path path = exported.resolve(entry.getName()).normalize();
                        if (!path.startsWith(exported)) throw new AssertionError("Unsafe pack entry");
                        Files.createDirectories(path.getParent());
                        Files.write(path, zip.readAllBytes());
                    }
                }
                Files.writeString(exported.resolve("components.json"), components.toString());
                ImageIO.write(image, "png", exported.resolve("full-source.png").toFile());
                if (version.bitmap()) {
                    check(!Files.exists(exported.resolve("assets/minecraft/atlases/items.json")),
                        "Bitmap snapshot does not create an object atlas");
                    check(Files.exists(exported.resolve("assets/dialogmenu/font/model_bitmap.json")),
                        "Actual bitmap model fonts are exported");
                }
                check(engine.prepare(ReloadEngine.Mode.RESOURCE).get(120, TimeUnit.SECONDS).snapshot() == snapshot,
                    "Warm unchanged resource reload reuses the snapshot");
                try (var restored = new ReloadEngine(data, CONTEXT)) {
                    var saved = restored.restore().get(120, TimeUnit.SECONDS).snapshot();
                    check(saved.assets().version().equals(version), "Restore retains version ownership");
                    check(serializer.serialize(saved.fonts().model("minecraft:prismarine", 64, 0, 0))
                        .equals(components.get("prismarine").toString()), "Restore retains actual model encoding");
                }
                previous = snapshot;
                System.out.println("PASS version " + id + " pack=" + Files.size(snapshot.pack()));
            }
            byte[] before = Files.readAllBytes(data.resolve("pack-cache/compiled.json.gz"));
            Files.writeString(data.resolve("config.yml"), "resource-version: 'unsupported'\n");
            try {
                engine.prepare().get(120, TimeUnit.SECONDS);
                throw new AssertionError("Unsupported target was accepted");
            } catch (java.util.concurrent.ExecutionException expected) {
                check(expected.getCause().getMessage().contains("resource-version"), "Unsupported target is diagnosed");
            }
            check(Arrays.equals(before, Files.readAllBytes(data.resolve("pack-cache/compiled.json.gz"))),
                "Failed version change preserves the persisted snapshot");
            check(engine.prepare(ReloadEngine.Mode.RESOURCE).get(120, TimeUnit.SECONDS).snapshot() == previous,
                "Failed version change preserves the live snapshot");
        }
        System.out.println("PASS versioned resource checks=" + checks);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
