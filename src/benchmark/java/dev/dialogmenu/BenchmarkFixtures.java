package dev.dialogmenu;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import static dev.dialogmenu.MenuModel.*;

/** 固定的合成输入，不读取运行服务器、玩家数据或第三方插件目录。 */
final class BenchmarkFixtures {
    static final int WIDTH = 480, MAX_PIXELS = 655360;
    static final String CHARACTERS = "0123456789Value物品金币";
    static final GsonComponentSerializer JSON = GsonComponentSerializer.gson();
    static final Plugin OWNER = (Plugin) Proxy.newProxyInstance(BenchmarkFixtures.class.getClassLoader(),
        new Class<?>[]{Plugin.class}, (proxy, method, args) -> method.getName().equals("isEnabled") ? true : null);

    static Path directory() throws IOException {
        // 缺少离线素材立即失败，禁止 benchmark 静默测量网络下载。
        for (String name : List.of("glyphs.bin.gz", "vanilla-icons.zip", "vanilla-inventory.zip"))
            if (BenchmarkFixtures.class.getResource("/" + name) == null)
                throw new IOException("Missing offline benchmark resource: " + name);
        return Files.createTempDirectory("dialogmenu-benchmark-");
    }
    static void remove(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        // 只处理由本工具创建的临时目录，不跟随符号链接。
        if (!root.getFileName().toString().startsWith("dialogmenu-benchmark-"))
            throw new IOException("Refusing to remove a non-benchmark directory: " + root);
        try (var files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }
    static void image(Path root) throws IOException {
        Path images = Files.createDirectories(root.resolve("images"));
        var icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
            icon.setRGB(x, y, 0xff000000 | x * 0x10100 | y * 0x10);
        ImageIO.write(icon, "png", images.resolve("icon.png").toFile());
    }
    static String layout(String id, int count) {
        StringBuilder yaml = new StringBuilder("schema-version: 1\nid: " + id
            + "\nscale-text-with-canvas: true\ncanvas: {width: 480, height: 300}\nfrontend:\n");
        for (int i = 0; i < count; i++) {
            int x = -204 + i % 6 * 72, y = 120 - i / 6 * 32;
            yaml.append("  icon").append(i).append(":\n    type: image\n    source: icon.png\n")
                .append("    width: 16\n    height: 16\n    offset: {x: ").append(x)
                .append(", y: ").append(y).append("}\n    actions: ['tell: item").append(i)
                .append("']\n    tooltip: 'Item ").append(i).append("'\n")
                .append("  text").append(i).append(":\n    type: text\n    content: '物品 ")
                .append(i).append(" {value}'\n    size: 8\n    offset: {x: ")
                .append(x + 24).append(", y: ").append(y).append("}\n");
        }
        return yaml.toString();
    }
    static String sessionLayout() {
        return """
            schema-version: 1
            id: session
            variables:
              amount: {type: external, source: amount}
            collections:
              items: {external: true}
            forms:
              input:
                fields:
                  value: {label: Value, max-length: 64}
            pages:
              session:
                canvas: {width: 480, height: 72}
                frontend:
                  heading:
                    type: text
                    content: '金币 {amount}'
                    size: 8
                flow-lists:
                  items: {collection: items, row: '{entry.name}'}
            """;
    }
    static Context context(String value) {
        return new Context() {
            public String expand(String text) { return text.replace("{value}", value).replace("{amount}", value); }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return true; }
        };
    }
    static Player player(int index) {
        UUID id = new UUID(0, index + 1);
        return (Player) Proxy.newProxyInstance(BenchmarkFixtures.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "Player" + index;
                case "isOnline", "hasPermission" -> true;
                default -> null;
            });
    }
    static List<DialogMenuEntry> entries(int count, boolean alternate) {
        List<DialogMenuEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) entries.add(new DialogMenuEntry("item" + i,
            "物品 " + i + (alternate ? " 1" : " 0"), "", Map.of()));
        return List.copyOf(entries);
    }

    static final class Pipeline implements AutoCloseable {
        final Path root;
        final String yaml;
        final LayoutStore.Document document;
        final VanillaAssets assets;
        final MenuRenderer renderer;
        final SpriteCanvas canvas = new SpriteCanvas();
        final FontPack fonts;
        final BenchmarkRendering presentation;
        final Context context = context("Value0000");
        Pipeline(int count) throws Exception {
            root = directory();
            image(root);
            yaml = layout("pipeline", count);
            document = LayoutStore.loadText("pipeline", yaml).get("pipeline");
            assets = new VanillaAssets(root.resolve("images"));
            renderer = new MenuRenderer(assets, 16_777_216);
            fonts = FontPack.create(List.of(document), renderer, context, List.of(WIDTH), MAX_PIXELS,
                CHARACTERS, List.of(1.0), List.of(1.0));
            presentation = new BenchmarkRendering(fonts);
        }
        Rendered render(Context active) throws Exception {
            return renderer.drawRuntime(document.menu(), active, WIDTH, MAX_PIXELS);
        }
        Component encode(Rendered frame, Context active, ActionTokens.Page page) {
            return canvas.encode(document.menu(), frame, active, fonts, region -> page.bind(() -> {})).component();
        }
        String present(Context active, SpriteCanvas canvas) throws Exception {
            var frame = presentation.frame(renderer, document.menu(), active, WIDTH, MAX_PIXELS);
            return JSON.serialize(presentation.encode(canvas, document.menu(), frame, active, fonts, new ActionTokens.Page()));
        }
        public void close() throws IOException { remove(root); }
    }

    /** 运行真实会话/排版/令牌/JSON；边界停在网络发送和 Paper Registry 之前。 */
    static final class SessionHost implements MenuSessions.Host, AutoCloseable {
        final Path root = directory();
        final MenuSessions sessions = new MenuSessions(this);
        final LayoutStore.Document document;
        final VanillaAssets assets;
        final MenuRenderer renderer;
        final SpriteCanvas canvas = new SpriteCanvas();
        final FontPack fonts;
        final BenchmarkRendering presentation;
        final ActionTokens tokens = new ActionTokens();
        String output;
        Consumer<Map<String, String>> submit;
        int presentations, closed, visibleRows;
        SessionHost() throws Exception {
            document = LayoutStore.loadText("session", sessionLayout()).get("session");
            assets = new VanillaAssets(root.resolve("images"));
            renderer = new MenuRenderer(assets);
            fonts = FontPack.create(List.of(document), renderer, context("0000"), List.of(WIDTH), MAX_PIXELS,
                CHARACTERS, List.of(1.0), List.of(1.0));
            presentation = new BenchmarkRendering(fonts);
        }
        DialogMenuBinding bind(Player player, List<DialogMenuEntry> rows) throws Exception {
            // 分开绑定和填充列表，用于与一次带入初始列表的 API 对照。
            var binding = sessions.bind(OWNER, player, document, WIDTH, Map.of("amount", "0000"),
                key -> {}, reason -> closed++);
            sessions.updateCollection(binding, "items", rows);
            return binding;
        }
        DialogMenuBinding bindInitial(Player player, List<DialogMenuEntry> rows) throws Exception {
            return sessions.bind(OWNER, player, document, WIDTH, Map.of("amount", "0000"),
                "items", rows, key -> {}, reason -> closed++);
        }
        public DialogMenuApi getMenuApi() { return null; }
        public File getDataFolder() { return root.toFile(); }
        public java.util.logging.Logger getLogger() { return java.util.logging.Logger.getAnonymousLogger(); }
        public void closeApplication(Player player) { tokens.remove(player.getUniqueId()); sessions.remove(player); }
        public void replaceApplication(Player player) { closeApplication(player); }
        public void closeFromApi(Player player) { closeApplication(player); }
        public void suspendApplication(Player player) { tokens.remove(player.getUniqueId()); }
        public void showApplication(Player player, LayoutStore.Document doc, int width, List<String> history) throws Exception {
            visibleRows = doc.flowRows().size();
            Context context = context("0000");
            var frame = presentation.frame(renderer, doc.menu(), context, width, MAX_PIXELS);
            var page = new ActionTokens.Page();
            Component body = presentation.encode(canvas, doc.menu(), frame, context, fonts, page);
            for (var row : doc.flowRows()) {
                Component text = Component.text(row.text());
                if (!row.id().isBlank()) text = text.clickEvent(page.bind(() -> {}));
                body = body.append(Component.newline()).append(text);
            }
            output = JSON.serialize(body);
            tokens.publish(player.getUniqueId(), page);
            presentations++;
        }
        public void showForm(Player player, MenuTemplate.FormSpec form,
                             Consumer<Map<String, String>> submitted, Runnable cancelled) { submit = submitted; }
        public void executeActions(Player player, List<String> actions) {}
        public void later(Runnable action) { throw new AssertionError("Unexpected asynchronous provider request"); }
        public void close() throws IOException { sessions.clear(); remove(root); }
    }

    static void resourceInput(Path root, int menus, int workers) throws IOException {
        image(root);
        Files.createDirectories(root.resolve("layouts"));
        Files.writeString(root.resolve("config.yml"), "default-layout: menu0\ncanvas-width: 480\n"
            + "max-canvas-pixels: 655360\ntext-scales: [1]\ncanvas-scales: [1]\n"
            + "resource-build-cpu-percent: 100\nresource-build-threads: " + workers + "\n"
            + "craftengine-auto-reload: false\npreload-inventory-models: false\n");
        for (int i = 0; i < menus; i++) Files.writeString(root.resolve("layouts/menu" + i + ".yml"), layout("menu" + i, 12));
    }
}
