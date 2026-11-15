package dev.dialogmenu;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;
import static dev.dialogmenu.MenuModel.*;

/** 离线点击适配器：真实令牌/会话/页面流水线，平台发送止于组件 JSON。 */
final class ClickFixtures implements MenuSessions.Host, AutoCloseable {
    static final class Client {
        final Player player;
        UUID session;
        final Map<String, String> buttons = new HashMap<>();
        DialogMenuBinding binding;
        LayoutStore.Document document;
        List<String> history = List.of();
        String json;
        int page, presentations, visibleRows;
        Client(Player player) { this.player = player; }
    }

    final Path root;
    final MenuSessions sessions = new MenuSessions(this);
    final ActionTokens tokens = new ActionTokens();
    final Map<UUID, Client> clients = new HashMap<>();
    final Map<String, LayoutStore.Document> documents;
    final List<DialogMenuEntry> first, second;
    final MenuRenderer renderer;
    final FontPack fonts;
    BenchmarkRendering presentation;
    SpriteCanvas canvas = new SpriteCanvas();

    ClickFixtures(int items) throws Exception {
        root = BenchmarkFixtures.directory();
        BenchmarkFixtures.image(root);
        documents = LayoutStore.loadText("clicks", layout(items));
        renderer = new MenuRenderer(new VanillaAssets(root.resolve("images")));
        fonts = FontPack.create(documents.values(), renderer, BenchmarkFixtures.context("0"),
            List.of(BenchmarkFixtures.WIDTH), BenchmarkFixtures.MAX_PIXELS,
            BenchmarkFixtures.CHARACTERS + "DashboardDetailRefreshNextBacktruefalseselected",
            List.of(1.0), List.of(1.0));
        presentation = new BenchmarkRendering(fonts);
        first = BenchmarkFixtures.entries(items, false);
        // 两个不重叠的页，翻页结果不能只依靠计数器判断。
        second = first.stream().map(entry -> new DialogMenuEntry("next" + entry.id(),
            entry.name() + " 1", "", Map.of())).toList();
    }

    private static String layout(int items) {
        var yaml = new StringBuilder("""
            schema-version: 1
            id: clicks
            state: {flag: 'false', selected: '0'}
            collections:
              items: {external: true}
            pages:
            """);
        for (String page : List.of("dashboard", "detail")) {
            String other = page.equals("dashboard") ? "detail" : "dashboard";
            String body = BenchmarkFixtures.layout(page, items);
            body = body.substring(body.indexOf("scale-text-with-canvas:"))
                .replace("height: 300", "height: 340")
                .replace("['tell: item0']", "['open: " + other + "']")
                .replace("{value}", "{flag}");
            yaml.append("  ").append(page).append(":\n");
            body.lines().forEach(line -> yaml.append("    ").append(line).append('\n'));
            yaml.append("""
                refresh:
                  type: text
                  content: 'Refresh {flag} {selected}'
                  offset: {x: -150, y: -145}
                  actions: ['toggle: flag']
                next:
                  type: text
                  content: Next
                  offset: {x: 0, y: -145}
                  actions: ['callback: next']
                back:
                  type: text
                  content: Back
                  offset: {x: 150, y: -145}
                  actions: ['back']
                """.indent(6));
            if (page.equals("dashboard")) yaml.append("""
                flow-lists:
                  items: {collection: items, row: '{entry.name}'}
                """.indent(4));
        }
        return yaml.toString();
    }

    Client bind(Player player) throws Exception {
        var client = new Client(player);
        clients.put(player.getUniqueId(), client);
        client.binding = sessions.bind(BenchmarkFixtures.OWNER, player, documents.get("dashboard"),
            BenchmarkFixtures.WIDTH, Map.of(), "items", first, key -> callback(client, key), reason -> {});
        return client;
    }

    private void callback(Client client, String key) {
        try {
            if (key.equals("next")) {
                client.page ^= 1;
                if (!sessions.updateCollection(client.binding, "items", client.page == 0 ? first : second))
                    throw new AssertionError("Inactive paging binding");
            } else {
                sessions.state(client.player, "set", "selected " + key);
                open(client, "detail", true);
            }
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    void click(Client client, String button) {
        String identifier = client.buttons.get(button);
        if (identifier == null) throw new AssertionError("Missing rendered button: " + button + " in " + client.buttons.keySet());
        var action = tokens.takeResponse(client.player.getUniqueId(), identifier);
        if (action == null) throw new AssertionError("Rendered click token rejected: " + button);
        action.accept(null);
    }

    String firstEntry(Client client) { return "entry:" + (client.page == 0 ? first : second).getFirst().id(); }

    private void activate(Client client, ClickGestures.Target target) {
        tokens.remove(client.player.getUniqueId());
        if (!client.player.isOnline() || !client.player.hasPermission("dialogmenu.use")
                || sessions.suspended(client.player)) throw new AssertionError("Inactive click");
        var previous = client.document;
        try (var ignored = sessions.frame(client.player)) {
            if (!target.present(client.session, previous.menu(), context(client.player)))
                throw new AssertionError("Click target no longer present");
        }
        executeActions(client.player, target.single());
        if (client.document == previous) {
            try { sessions.refresh(client.player); }
            catch (Exception error) { throw new IllegalStateException(error); }
        }
    }

    private Context context(Player player) {
        return new Context() {
            public String expand(String text) { return sessions.expand(player, text); }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return player.hasPermission(permission); }
        };
    }

    private void open(Client client, String page, boolean remember) throws Exception {
        var history = new ArrayList<>(client.history);
        if (remember) {
            if (history.size() >= 64) history.removeFirst();
            history.add(client.document.menu().id());
        } else if (!history.removeLast().equals(page)) throw new AssertionError("Wrong back target");
        sessions.open(client.player, Objects.requireNonNull(documents.get(page)), BenchmarkFixtures.WIDTH, history);
    }

    public void executeActions(Player player, List<String> actions) {
        var client = clients.get(player.getUniqueId());
        var previous = client.document;
        for (String action : actions) {
            if (client.document != previous || sessions.suspended(player)) break;
            String[] parts = action.split(":", 2);
            String kind = parts[0].strip(), value = parts.length == 2 ? parts[1].strip() : "";
            try {
                switch (kind) {
                    case "open" -> open(client, value, true);
                    case "back" -> open(client, client.history.getLast(), false);
                    case "toggle", "set" -> sessions.state(player, kind, value);
                    case "callback" -> sessions.callback(player, value);
                    default -> throw new AssertionError("Unsupported benchmark action: " + action);
                }
            } catch (Exception error) { throw new IllegalStateException(error); }
        }
    }

    private static ClickEvent remember(Client client, String control, ClickEvent event) {
        String identifier = ((ClickEvent.Payload.Custom) event.payload()).key().value();
        String previous = client.buttons.putIfAbsent(control, identifier);
        if (previous != null && !previous.equals(identifier))
            throw new AssertionError("Control received multiple tokens: " + control);
        return event;
    }

    public void showApplication(Player player, LayoutStore.Document document, int width, List<String> history)
            throws Exception {
        var client = clients.get(player.getUniqueId());
        if (client.document == null || !client.document.menu().id().equals(document.menu().id()))
            client.session = UUID.randomUUID();
        client.document = document;
        client.history = List.copyOf(history);
        client.visibleRows = document.flowRows().size();
        client.buttons.clear();
        var frozen = DialogMenuPlugin.freezeContext(document, context(player));
        var frame = presentation.frame(renderer, document.menu(), frozen, width, BenchmarkFixtures.MAX_PIXELS);
        var page = new ActionTokens.Page();
        Component body = presentation.encode(canvas, document.menu(), frame, frozen, fonts, region -> {
            var target = ClickGestures.Target.of(client.session, document.menu(), region, frozen);
            return remember(client, region.id(), page.bind(() -> activate(client, target)));
        });
        for (var row : document.flowRows()) {
            var text = Component.text(row.text());
            if (!row.id().isBlank()) text = text.clickEvent(remember(client, "entry:" + row.id(), page.bind(() -> {
                try { sessions.entry(player, row.collection() + " " + row.id()); }
                catch (Exception error) { throw new IllegalStateException(error); }
            })));
            body = body.append(Component.newline()).append(text);
        }
        client.json = BenchmarkFixtures.JSON.serialize(body);
        tokens.publish(player.getUniqueId(), page);
        client.presentations++;
    }

    /** 仅清空页面/组件缓存，资源、字形与已解析 YAML 保持就绪。 */
    void clearPresentationCaches() {
        presentation = new BenchmarkRendering(fonts);
        canvas = new SpriteCanvas();
    }

    public DialogMenuApi getMenuApi() { return null; }
    public File getDataFolder() { return root.toFile(); }
    public java.util.logging.Logger getLogger() { return java.util.logging.Logger.getAnonymousLogger(); }
    public void closeApplication(Player player) { tokens.remove(player.getUniqueId()); sessions.remove(player); }
    public void replaceApplication(Player player) { closeApplication(player); }
    public void closeFromApi(Player player) { closeApplication(player); }
    public void suspendApplication(Player player) { tokens.remove(player.getUniqueId()); }
    public void showForm(Player player, MenuTemplate.FormSpec form,
                         Consumer<Map<String, String>> submitted, Runnable cancelled) {
        throw new AssertionError("Unexpected form");
    }
    public void later(Runnable action) { throw new AssertionError("Unexpected asynchronous provider request"); }
    public void close() throws IOException { tokens.clear(); sessions.clear(); BenchmarkFixtures.remove(root); }
}
