package dev.dialogmenu;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

/** Exercises production rendering failures; successful Paper dialog delivery is tested on a server. */
public final class PresentationLifecycleTests {
    private static int checks;
    private static int tick;
    private static Thread owner;
    private static Path data;
    private static ReloadEngine.Snapshot snapshot;
    private static final AtomicInteger scheduled = new AtomicInteger();

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static void set(Object object, Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        set(object, object.getClass(), name, value);
    }
    private static Object get(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    private interface Call { Object run(Method method, Object[] args) throws Throwable; }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            if (m.getName().equals("toString")) return "offline-" + type.getSimpleName();
            return call.run(m, a);
        }));
    }
    private static Player player() {
        UUID id = UUID.randomUUID();
        return proxy(Player.class, (m, a) -> switch (m.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> "SyncProbe";
            case "isOnline", "hasPermission" -> true;
            case "closeDialog", "closeInventory", "sendMessage" -> null;
            case "showDialog" -> throw new AssertionError("invalid render must not display");
            default -> throw new AssertionError("Unexpected player: " + m);
        });
    }
    private static Server platform() {
        var scheduler = proxy(BukkitScheduler.class, (m, a) -> {
            scheduled.incrementAndGet();
            throw new AssertionError("Synchronous rendering scheduled work: " + m);
        });
        var manager = proxy(PluginManager.class, (m, a) -> switch (m.getName()) {
            case "getPlugin" -> null;
            case "isPluginEnabled" -> false;
            case "callEvent" -> null;
            default -> throw new AssertionError("Unexpected plugin manager: " + m);
        });
        return proxy(Server.class, (m, a) -> switch (m.getName()) {
            case "isPrimaryThread" -> Thread.currentThread() == owner;
            case "getCurrentTick" -> tick;
            case "getScheduler" -> scheduler;
            case "getPluginManager" -> manager;
            case "getLogger" -> Logger.getLogger("offline-platform");
            case "getName", "getVersion", "getBukkitVersion" -> "offline-boundary";
            default -> throw new AssertionError("Unexpected server: " + m);
        });
    }
    private static DialogMenuPlugin plugin(ExecutorService previews) throws Exception {
        var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        var plugin = (DialogMenuPlugin) unsafe.getClass().getMethod("allocateInstance", Class.class)
            .invoke(unsafe, DialogMenuPlugin.class);
        set(plugin, JavaPlugin.class, "isEnabled", true);
        set(plugin, JavaPlugin.class, "dataFolder", data.toFile());
        set(plugin, JavaPlugin.class, "logger", Logger.getLogger("offline-plugin"));
        set(plugin, "loaded", snapshot);
        set(plugin, "width", 452);
        set(plugin, "maxPixels", 1048576);
        set(plugin, "previewWorker", previews);
        set(plugin, "presentationCaches", new WeakHashMap<>());
        set(plugin, "previewCaches", new WeakHashMap<>());
        set(plugin, "presentationRequests", new HashMap<>());
        set(plugin, "preferences", new YamlConfiguration());
        set(plugin, "tokens", new ActionTokens());
        set(plugin, "gestures", new ClickGestures(() -> 0L, (action, millis) -> () -> {}));
        set(plugin, "views", new HashMap<>());
        set(plugin, "application", new MenuSessions(plugin));
        return plugin;
    }
    private static void rejectsRender(DialogMenuPlugin plugin, Player player) throws Exception {
        boolean rejected = false;
        try { plugin.showApplication(player, snapshot.documents().get("dense"), 1, List.of()); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "render error was not delivered before return");
    }
    @SuppressWarnings("unchecked")
    private static void synchronousFailures(DialogMenuPlugin plugin) throws Exception {
        Player player = player();
        var views = (Map<UUID, Object>) get(plugin, "views");
        var viewType = Class.forName("dev.dialogmenu.DialogMenuPlugin$View");
        var constructor = viewType.getDeclaredConstructor(LayoutStore.Document.class, int.class, List.class, UUID.class);
        constructor.setAccessible(true);
        Object previous = constructor.newInstance(snapshot.documents().get("dense"), 452, List.of(), UUID.randomUUID());
        views.put(player.getUniqueId(), previous);
        ActionTokens tokens = (ActionTokens) get(plugin, "tokens");
        var page = new ActionTokens.Page();
        AtomicInteger activated = new AtomicInteger();
        ClickEvent click = page.bind(activated::incrementAndGet);
        String id = ((ClickEvent.Payload.Custom) click.payload()).key().value();
        tokens.publish(player.getUniqueId(), page);
        for (int step = 0; step < 4; step++) {
            if (step == 2) tick++;
            if (step == 3) tick += 2;
            rejectsRender(plugin, player);
            check(plugin.surfaceFromApi(player) == previous, "failed refresh replaced the previous view");
        }
        var action = tokens.takeResponse(player.getUniqueId(), id);
        check(action != null, "failed refresh invalidated the displayed action");
        action.accept(null);
        check(activated.get() == 1 && tokens.takeResponse(player.getUniqueId(), id) == null,
            "displayed action did not remain one-shot");
        plugin.closeFromApi(player);
        check(plugin.currentIdFromApi(player) == null, "close retained the view");
        rejectsRender(plugin, player);
        check(plugin.currentIdFromApi(player) == null, "failed first open left a view");
        check(scheduled.get() == 0, "failed render left deferred work");
    }
    private static void previewIsolation(DialogMenuPlugin plugin, ExecutorService previews) throws Exception {
        var doc = snapshot.documents().get("dense");
        var renderer = new MenuRenderer(snapshot.assets());
        var frame = renderer.drawRuntime(doc.menu(), DialogMenuPlugin.PREVIEW, 452, 1048576);
        var serializer = GsonComponentSerializer.gson();
        String expected = serializer.serialize(new SpriteCanvas().encode(
            doc.menu(), frame, DialogMenuPlugin.PREVIEW, snapshot.fonts(), region -> null).component());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        previews.submit(() -> {
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        check(entered.await(5, TimeUnit.SECONDS), "preview gate");
        List<CompletableFuture<SpriteCanvas.Output>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) futures.add(plugin.renderPreview(snapshot, doc, DialogMenuPlugin.PREVIEW));
            rejectsRender(plugin, player());
            check(futures.stream().noneMatch(CompletableFuture::isDone), "preview gate opened prematurely");
        } finally { release.countDown(); }
        for (var future : futures)
            check(expected.equals(serializer.serialize(future.get(30, TimeUnit.SECONDS).component())),
                "background preview differs from uncached rendering");
    }
    public static void main(String[] args) throws Exception {
        System.out.println("PASS synchronous presentation checks=" + run(Path.of(args[0])));
    }
    static int run(Path directory) throws Exception {
        checks = 0;
        scheduled.set(0);
        tick = 0;
        owner = Thread.currentThread();
        data = Files.createTempDirectory(directory, "synchronous-presentation-").resolve("data");
        Files.createDirectories(data.resolve("layouts"));
        Files.writeString(data.resolve("config.yml"), """
            default-layout: dense
            canvas-width: 452
            max-canvas-pixels: 1048576
            text-scales: [1]
            canvas-scales: [1]
            resource-build-cpu-percent: 100
            """);
        Files.writeString(data.resolve("layouts/dense.yml"), """
            schema-version: 3
            id: dense
            canvas: {width: 452, height: 205}
            frontend:
              text:
                type: text
                content: 'Synchronous'
                offset: {x: 226, y: 20}
                actions: 'tell: clicked'
            """);
        var server = Bukkit.class.getDeclaredField("server");
        server.setAccessible(true);
        Object previousServer = server.get(null);
        try (var previews = Executors.newSingleThreadExecutor();
             var engine = new ReloadEngine(data, DialogMenuPlugin.PREVIEW)) {
            snapshot = engine.prepare().get(120, TimeUnit.SECONDS).snapshot();
            server.set(null, platform());
            var plugin = plugin(previews);
            synchronousFailures(plugin);
            previewIsolation(plugin, previews);
            check(scheduled.get() == 0, "interactive rendering used a scheduler");
        } finally { server.set(null, previousServer); }
        System.out.println("PASS synchronous presentation checks=" + checks);
        return checks;
    }
}
