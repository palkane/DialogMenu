package dev.dialogmenu;

import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** 离线验证缓存、重载、动态格式及资源发布合同，不启动 Bukkit。 */
public final class ResourceRegressionTests {
    static net.kyori.adventure.text.Component textOnly(net.kyori.adventure.text.Component component) {
        var text = component instanceof net.kyori.adventure.text.TextComponent value
            ? value : net.kyori.adventure.text.Component.empty().style(component.style());
        return text.children(component.children().stream().map(ResourceRegressionTests::textOnly).toList());
    }
    private static int checks;
    private static final Context PREVIEW = context("Player");
    private static Context context(String value) {
        return new Context() {
            public String expand(String text) { return text.replace("{player}", value).replace("{value}", value); }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return true; }
        };
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    @FunctionalInterface interface Operation { void run() throws Exception; }
    private static void rejects(Operation operation, String message) throws Exception {
        try { operation.run(); }
        catch (IllegalArgumentException | IllegalStateException | java.io.IOException error) { checks++; return; }
        catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException) { checks++; return; }
            throw error;
        }
        throw new AssertionError(message);
    }
    private static String yaml(String text) {
        return "schema-version: 1\nid: probe\ncanvas: {width: 300, height: 180}\nfrontend:\n  label:\n    type: text\n    content: '" + text + "'\n";
    }
    private static LayoutStore.Document doc(String text) throws Exception {
        var yaml = new YamlConfiguration(); yaml.loadFromString(yaml(text));
        return LayoutStore.parse("probe", yaml);
    }
    private static Path data(Path root, String name, String text) throws Exception {
        Path data = root.resolve(name);
        Files.createDirectories(data.resolve("layouts"));
        Files.writeString(data.resolve("config.yml"), "default-layout: probe\nresource-build-cpu-percent: 100\n");
        Files.writeString(data.resolve("layouts/probe.yml"), yaml(text));
        return data;
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("dialogmenu.test.directory"));
        Files.createDirectories(root);
        root = Files.createTempDirectory(root, "run-");
        checks += AssetLibraryTests.run(root);
        checks += DefaultGlyphTests.run(root);
        checks += InventoryAssetsTests.run(root);
        checks += ModelCompatibilityTests.run(root);
        checks += InventoryCompatibilityTests.run(root);
        checks += CoordinateTests.run(root);
        checks += ScaleCalibrationTests.run(root);
        checks += NativeControlsTests.run(root);
        checks += PresentationContextTests.run(root);
        checks += PreparedLayoutTests.run(root);
        checks += NativeBackgroundTests.run(root);
        checks += NativeUiTextureTests.run(root);
        SessionReplacementCheck.main(new String[0]);
        InitialCollectionCheck.run();
        AvatarPlacementCheck.main(new String[0]);
        if (NativePlayerHeads.supported()) AvatarInteractionCheck.main(new String[0]);
        CatalogTooltipCheck.main(new String[0]);
        NativePlayerHeadCheck.run();
        AvatarSnapshotCheck.run(root);
        FitWidthCheck.run(root);
        checks += ProportionalImageTests.run(root);
        checks += GraphicLimitTests.run(root);
        checks += CanvasScaleTests.run(root);
        cacheAndReload(root);
        formats(root);
        configuration(root);
        examples(root);
        publication();
        preview(root);
        tintAndTextStretch(root);
        UnifiedGeometryTests.run(root);
        GraphicReuseTests.run(root);
        DecorationOpenerTests.run(root);
        MaxWidthTests.run(root);
        HitFontTests.run(root);
        ShaderTests.run(root);
        ImageRegistryTests.run(root);
        System.out.println("PASS row pixel/z/hit checks=" + RowPackTests.run(root));
        ParallelBuildTests.main(new String[]{root.toString()});
        IndependentBuildTests.main(new String[]{root.toString()});
        BuildTasksTests.main(new String[]{root.toString()});
        BuildBudgetTests.main(new String[0]);
        ReloadTests.run(root);
        MenuReloadTests.main(new String[]{root.toString()});
        PersistenceTests.main(new String[]{root.toString()});
        GlyphCacheTests.main(new String[]{root.toString()});
        Path input = root.resolve("input.bin"); Files.writeString(input, "A");
        var consumed = new SourceFiles(); consumed.read(input); consumed.verify();
        Files.writeString(input, "B");
        rejects(consumed::verify, "changed consumed input accepted");
        checks += PresentationLifecycleTests.run(root);
        checks += ExpansionTests.run();
        System.out.println("PASS " + checks + " resource regression checks (offline, no client)");
    }
    private static void cacheAndReload(Path root) throws Exception {
        Path data = data(root, "stamp", "A"), file = data.resolve("layouts/probe.yml");
        try (var engine = new ReloadEngine(data, PREVIEW)) {
            var first = engine.prepare().get(); var stamp = Files.getLastModifiedTime(file);
            Files.writeString(file, yaml("B")); Files.setLastModifiedTime(file, stamp);
            var second = engine.prepare().get();
            check(second.changed() && !first.snapshot().pack().equals(second.snapshot().pack()), "same-stamp change missed");
            check(second.snapshot().documents().get("probe").menu().nodes().getFirst().text().equals("B"), "old document retained");
            check(!engine.prepare().get().changed(), "unchanged build not cached");
            Files.writeString(file, yaml("C").replace("type: text", "type: unsupported"));
            rejects(() -> engine.prepare().get(), "invalid candidate accepted");
            Files.writeString(file, yaml("B")); Files.setLastModifiedTime(file, stamp);
            check(engine.prepare().get().snapshot() == second.snapshot(), "failed build replaced last snapshot");
            Files.writeString(file, yaml("C"));
            check(!engine.prepare(ReloadEngine.Mode.RESOURCE).get().changed(), "resource reload read pending YAML");
        }
        data = data(root, "dynamic", "Player"); file = data.resolve("layouts/probe.yml");
        try (var engine = new ReloadEngine(data, PREVIEW)) {
            engine.prepare().get(); Files.writeString(file, yaml("{player}"));
            rejects(() -> engine.prepare(ReloadEngine.Mode.YAML).get(), "YAML accepted missing dynamic coverage");
            var next = engine.prepare().get();
            check(next.resourcesChanged(), "all skipped dynamic rebuild");
            next.snapshot().fonts().textFont(8, MenuText.parse("New汉")); checks++;
            Files.writeString(file, yaml("{player}") + "# harmless YAML update\n");
            var layout = engine.prepare(new ReloadEngine.Request(ReloadEngine.Mode.YAML, "probe")).get();
            check(layout.changed() && !layout.resourcesChanged(), "single menu reload rebuilt ZIP");
            var rebuilt = engine.prepare().get();
            check(rebuilt.resourcesChanged(), "all reused pre-YAML fingerprint");
            Files.writeString(file, yaml("{player}") + "# another YAML update\n");
            engine.prepare(ReloadEngine.Mode.YAML).get();
            var resource = engine.prepare(ReloadEngine.Mode.RESOURCE).get();
            check(resource.resourcesChanged(), "resource reused pre-YAML fingerprint");
        }
    }
    private static void configuration(Path root) throws Exception {
        Path escapeData = data(root, "escape-config", "A");
        try (var engine = new ReloadEngine(escapeData, PREVIEW)) {
            var initial = engine.prepare().get().snapshot();
            check(initial.settings().escape(), "Missing close-with-escape must allow Esc by default");
            check(initial.documents().get("probe").menu().closeWithEscape() == null,
                "Menus without an override inherit the global Esc setting");
            for (boolean escape : List.of(false, true)) {
                Files.writeString(escapeData.resolve("config.yml"),
                    "default-layout: probe\nclose-with-escape: " + escape + "\n");
                var updated = engine.prepare(ReloadEngine.Mode.YAML).get().snapshot();
                check(updated.settings().escape() == escape, "Global Esc setting applies with YAML reload");
                check(updated.pack().equals(initial.pack()), "Esc configuration does not rebuild menu resources");
                Files.writeString(escapeData.resolve("layouts/probe.yml"),
                    yaml("A") + "close-with-escape: " + !escape + "\n");
                var overridden = engine.prepare(ReloadEngine.Mode.YAML).get().snapshot();
                check(overridden.settings().escape() == escape
                    && overridden.documents().get("probe").menu().closeWithEscape() == !escape,
                    "Explicit page Esc setting survives reload independently of the global setting");
            }
        }
        try (var input = ResourceRegressionTests.class.getResourceAsStream("/config.yml")) {
            var defaults = YamlConfiguration.loadConfiguration(
                new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
            check(defaults.getBoolean("close-with-escape"), "Fresh installations must allow Esc by default");
        }
        Path data = data(root, "invalid-config", "A");
        for (String threads : List.of("0", "9", "1.5", "bad")) {
            Files.writeString(data.resolve("config.yml"),
                "default-layout: probe\nresource-build-threads: " + threads + "\n");
            try (var engine = new ReloadEngine(data, PREVIEW)) {
                rejects(() -> engine.prepare().get(), "invalid parallelism accepted");
            }
        }
        for (String value : List.of("'#123456'", "[123]", "['bad']")) {
            Files.writeString(data.resolve("config.yml"), "default-layout: probe\ntext-colors: " + value + "\n");
            try (var engine = new ReloadEngine(data, PREVIEW)) {
                rejects(() -> engine.prepare().get(), "invalid color configuration accepted");
            }
        }
    }
    private static void formats(Path root) throws Exception {
        var renderer = new MenuRenderer(new VanillaAssets(root.resolve("format-images")));
        var document = doc("{value}");
        var fonts = FontPack.create(List.of(document), renderer, context("A"), List.of(300), 131072);
        for (String value : List.of("A", "Z", "汉", "A\nA", "&cA", "&0A", "&f汉", "&lA")) {
            var context = context(value);
            new SpriteCanvas().encode(document.menu(), renderer.draw(document.menu(), context, 300, 131072), context, fonts, r -> null);
            checks++;
        }
        for (String decoration : List.of("&o", "&n", "&m", "&k")) {
            var styled = doc(decoration + "A");
            var prepared = FontPack.create(List.of(styled), renderer, PREVIEW, List.of(300), 131072);
            new SpriteCanvas().encode(styled.menu(), renderer.draw(styled.menu(), PREVIEW, 300, 131072),
                PREVIEW, prepared, region -> null);
            fonts.rgbaText(MenuText.parse(decoration + "A"), -1, 9, new TextStyle(8, 1), 9);
            checks += 2;
        }
        rejects(() -> fonts.rgbaText(MenuText.parse("&#123456A"), -1, 9, new TextStyle(8, 1), 9), "undeclared HEX accepted");
        var declared = FontPack.create(List.of(document), renderer, context("A"), List.of(300), 131072,
            "", List.of(1.0), List.of(1.0), List.of(0x123456));
        declared.rgbaText(MenuText.parse("&#123456A"), -1, 9, new TextStyle(8, 1), 9); checks++;
        var palette = new RgbaOffsets();
        for (int color = 0; color < 240; color++) palette.add(0x100000 + color);
        rejects(() -> palette.add(0x123456), "palette overflow accepted");
    }
    private static void examples(Path root) throws Exception {
        Path data = root.resolve("examples");
        Files.createDirectories(data.resolve("layouts"));
        Files.createDirectories(data.resolve("images"));
        for (String resource : List.of("config.yml", "layouts/layout-demo.yml", "layouts/layout-detail.yml",
            "images/demo-diamond.png", "images/shapes/panel-light.png"))
            try (var input = ResourceRegressionTests.class.getResourceAsStream("/" + resource)) {
                Files.createDirectories(data.resolve(resource).getParent());
                Files.copy(Objects.requireNonNull(input), data.resolve(resource));
            }
        try (var engine = new ReloadEngine(data, PREVIEW)) {
            var snapshot = engine.prepare().get().snapshot();
            for (var document : snapshot.documents().values()) for (int width : List.of(300, 360, 420))
                for (double scale : snapshot.settings().textScales())
                    for (double canvasScale : snapshot.settings().canvasScales()) {
                    Context base = new Context() {
                        public String expand(String text) { return PREVIEW.expand(text); }
                        public Icon item(int slot) { return null; }
                        public boolean permitted(String permission) { return true; }
                        public double textScale() { return scale; }
                    };
                    Context active = CanvasScale.context(base, canvasScale);
                    var frame = snapshot.renderer().draw(document.menu(), active, width, snapshot.settings().maxPixels());
                    new SpriteCanvas().encode(document.menu(), frame, active, snapshot.fonts(), r -> null);
                    checks++;
                }
        }
    }
    private static void preview(Path root) throws Exception {
        Path zip = root.resolve("preview.zip");
        var entries = List.of("assets/dialogmenu/font/text.json", "assets/minecraft/shaders/core/rendertype_text.vsh",
            "assets/dialogmenu/shaders/include/test.glsl", "assets/minecraft/shaders/core/rendertype_text.fsh");
        try (var output = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            for (String name : entries) {
                output.putNextEntry(new java.util.zip.ZipEntry(name));
                output.write("dialogmenu:rgba/palette.glsl".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        byte[] bytes = EditorBridge.previewPack(zip);
        try (var input = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            int count = 0;
            for (java.util.zip.ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                check(!entry.getName().contains("assets/dialogmenu/"), "preview retained original path");
                check(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .equals("dialogmenu_preview:rgba/palette.glsl"), "preview shader retained production namespace");
                count++;
            }
            check(count == entries.size(), "preview lost resources");
        }
    }
    private static void tintAndTextStretch(Path root) throws Exception {
        Path data = root.resolve("tint-stretch");
        Files.createDirectories(data.resolve("images"));
        BufferedImage source = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) source.setRGB(x, y, 0xFFFFFFFF);
        ImageIO.write(source, "png", data.resolve("images/sample.png").toFile());
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
            schema-version: 1
            id: probe
            canvas: {width: 300, height: 180}
            frontend:
              icon:
                type: image
                source: sample.png
                width: 32
                height: 24
                color: '#55AAFF'
                offset: {y: 25}
                actions: ['tell: IMAGE']
              label:
                type: text
                content: 'A中'
                color: '#55AAFF'
                size: 8
                width: 100
                scale: 1.5
                offset: {y: -20}
                actions: ['tell: TEXT']
            """);
        var document = LayoutStore.parse("probe", yaml);
        var renderer = new MenuRenderer(new VanillaAssets(data.resolve("images")));
        Sprite fitted = renderer.draw(document.menu(), PREVIEW, 300, 131072).sprites().stream()
            .filter(sprite -> sprite.id().equals("frontend.icon")).findFirst().orElseThrow();
        check(fitted.width() == 24 && fitted.height() == 24 && fitted.x() == 138,
            "Image preserves its source aspect ratio inside the configured 32x24 box");
        var invalidImage = new YamlConfiguration();
        invalidImage.loadFromString("""
            schema-version: 1
            id: invalid-image
            canvas: {width: 300, height: 180}
            frontend:
              icon:
                type: image
                source: sample.png
                width: 32
                height: 24
                scale: 2
            """);
        var scaledImage = LayoutStore.parse("invalid-image", invalidImage);
        check(scaledImage.menu().nodes().stream().filter(node -> node.type().equals("image"))
            .findFirst().orElseThrow().scale() == 2, "Single scalar scale applies uniformly to images");
        for (String kind : List.of("text", "rectangle", "frame", "line", "model", "image", "group"))
            for (String scale : List.of("{x: 1.5, y: 2}", "{x: 2, y: 2}", "{x: 2}")) {
            String node = kind.equals("model")
                ? "type: model\n    source: minecraft:stone\n    size: 16"
                : kind.equals("image") ? "type: image\n    source: sample.png\n    width: 16\n    height: 16"
                : kind.equals("text") ? "type: text\n    content: A"
                : "type: " + kind + "\n    width: 16\n    height: 16";
            var anisotropic = new YamlConfiguration();
            anisotropic.loadFromString("""
                schema-version: 1
                id: anisotropic
                canvas: {width: 300, height: 180}
                frontend:
                  item:
                    %s
                    scale: %s
                """.formatted(node, scale));
            boolean rejected = false;
            try { LayoutStore.parse("anisotropic", anisotropic); }
            catch (IllegalArgumentException error) {
                rejected = error.getMessage().contains("已移除scale.x/scale.y");
            }
            check(rejected, "Independent XY scaling must be rejected for " + kind);
        }
        var dynamicScale = new YamlConfiguration();
        dynamicScale.loadFromString("""
            schema-version: 1
            id: dynamic-scale
            variables:
              factor: {type: external, preview: '1'}
            pages:
              dynamic-scale:
                canvas: {width: 300, height: 180}
                frontend:
                  group:
                    type: group
                    scale: '{factor}'
                    children:
                      label: {type: text, content: A, scale: 3, offset: {x: 5, y: 7}}
            """);
        var scaleTemplate = MenuTemplate.load("dynamic-scale", dynamicScale).get("dynamic-scale").template();
        for (int factor : List.of(1, 2, 3)) {
            var node = scaleTemplate.document(Map.of(), text -> text.replace("{factor}", Integer.toString(factor)))
                .menu().nodes().getFirst();
            check(node.scale() == factor * 3 && node.x() == factor * 5 && node.y() == factor * 7,
                "Dynamic group scale uniformly transforms both offsets and descendant glyph size");
        }
        var fonts = FontPack.create(List.of(document), renderer, PREVIEW, List.of(300), 131072);
        TextStyle stretched = new TextStyle(8, 1.5);
        check(fonts.textFont(stretched, MenuText.parse("A中")).equals(fonts.textFont(8)),
            "Text scale shares the fixed source font");
        check(fonts.textWidth(MenuText.parse("A中"), stretched) > fonts.textWidth(MenuText.parse("A中"), 8),
            "Horizontal stretch changes measured text advance");
        Path pack = data.resolve("pack");
        fonts.write(pack);
        String json = Files.readString(pack.resolve("assets/dialogmenu/font/" + RgbaText.FONT + ".json"));
        check(JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("providers").get(0)
            .getAsJsonObject().get("height").getAsInt() == 1,
            "Vertical stretch does not change native provider height");
        Path imageAtlas;
        try (var files = Files.list(pack.resolve("assets/dialogmenu/textures/registered"))) {
            imageAtlas = files.filter(p -> p.toString().endsWith(".png")).findFirst().orElseThrow();
        }
        BufferedImage atlas = ImageIO.read(imageAtlas.toFile());
        check((atlas.getRGB(1, 0) >>> 24) == fonts.imageTint(0xFFFFFF),
            "Automatically registered image keeps original color instead of preparing a tint");
        List<String> callbacks = new ArrayList<>();
        var output = new SpriteCanvas().encode(document.menu(),
            renderer.draw(document.menu(), PREVIEW, 300, 131072), PREVIEW, fonts, region -> {
                callbacks.add(region.id());
                return ClickEvent.runCommand("/" + region.id());
            });
        var textGlyphs = MenuText.glyphs(textOnly(output.component())).stream()
            .filter(g -> g.code() == 'A' || g.code() == '中').toList();
        check(!textGlyphs.isEmpty() && textGlyphs.stream().allMatch(g -> g.style().shadowColor() != null
                && (g.style().shadowColor().value() & 255) == fonts.imageTint(0x55AAFF)),
            "Text retains its configured tint while the image remains untinted");
        check(callbacks.equals(List.of("frontend.icon", "frontend.label")),
            "Tint and stretched elements preserve their bound interactions");
        check(MenuText.glyphs(textOnly(output.component())).stream().anyMatch(g -> g.style().font() != null
            && g.style().font().value().equals(RgbaText.FONT)),
            "Rendered text references the fixed source font");
        check(MenuText.glyphs(textOnly(output.component())).stream().anyMatch(g -> g.style().font() != null
            && g.style().font().equals(RgbaHits.FONT) && g.style().clickEvent() != null),
            "Text hit proxy follows the stretched font and keeps its action");
    }
    private static void publication() throws Exception {
        var publication = new ResourcePublication<String>();
        publication.prepare(1, "old"); check(publication.commit(1).equals("old"), "initial commit failed");
        publication.prepare(2, "candidate");
        check(publication.pending().value().equals("candidate"), "candidate lost");
        rejects(() -> publication.commit(1), "stale receipt accepted");
        publication.prepare(3, "newer");
        rejects(() -> publication.commit(2), "superseded receipt accepted");
        check(publication.commit(3).equals("newer"), "latest commit failed");
        publication.prepare(2, "stale"); check(publication.pending() == null, "late prepare overwrote committed version");
    }
}
