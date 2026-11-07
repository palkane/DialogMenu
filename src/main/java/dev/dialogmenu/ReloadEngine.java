package dev.dialogmenu;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.zip.*;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** 文件解析和资源包准备不接触运行中的 Bukkit 或玩家状态。 */
final class ReloadEngine implements AutoCloseable {
    enum Mode { YAML, RESOURCE, ALL }
    record Progress(int percent, String stage) {}
    private static final class ProgressReporter {
        private final Consumer<Progress> sink;
        private int percent;
        ProgressReporter(Consumer<Progress> sink) { this.sink = sink; }
        void update(int next, String stage) {
            percent = Math.max(percent, Math.max(0, Math.min(99, next)));
            sink.accept(new Progress(percent, stage));
        }
    }
    record Request(Mode mode, String menu) {
        static Request command(String[] args) {
            if (args.length == 1) return new Request(Mode.YAML, null);
            if (args.length == 2) for (Mode mode : Mode.values())
                if (mode.name().equalsIgnoreCase(args[1])) return new Request(mode, null);
            if (args.length == 2) return new Request(Mode.YAML, LayoutStore.id(args[1]));
            throw new IllegalArgumentException("/dialogmenu reload [yaml|resource|all|菜单名]");
        }
    }
    record Settings(int width, int maxPixels, String defaultLayout, boolean escape,
                    List<Double> textScales, double defaultTextScale, boolean autoCraftEngine,
                    Path resourceRoot, int resourceBuildCpuPercent, List<String> craftEngineDefinitions,
                    List<Integer> textColors, boolean reloadProgressBossBar) {}
    record Stamp(long size, FileTime modified, String digest) {}
    record Snapshot(Map<String, LayoutStore.Document> documents, VanillaAssets assets, MenuRenderer renderer,
                    FontPack fonts, Settings settings, Path pack, Map<Path, Stamp> inputs,
                    Map<Path, Stamp> resourceInputs, long buildMillis,
                    String buildThread, long generation) {}
    record Result(Snapshot snapshot, boolean changed, boolean resourcesChanged, Set<String> menus) {
        Result(Snapshot snapshot, boolean changed, boolean resourcesChanged) {
            this(snapshot, changed, resourcesChanged, Set.of());
        }
        Result(Snapshot snapshot, boolean changed) { this(snapshot, changed, changed); }
    }
    private final Path data;
    private final Context preview;
    private final PackCache cache;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "DialogMenu-reload");
        thread.setDaemon(true);
        return thread;
    });
    private Snapshot latest;
    // YAML 已应用不等于字库已按该版本重建，二者分别记录。
    private Map<Path, Stamp> builtInputs = Map.of();
    private long generation;

    ReloadEngine(Path data, Context preview) {
        this.data = data;
        this.preview = preview;
        this.cache = new PackCache(data.resolve("pack-cache"));
    }
    void retainPack(Object consumer, Snapshot snapshot) { cache.retain(consumer, snapshot.pack()); }

    CompletableFuture<Result> prepare() { return prepare(Mode.ALL); }
    CompletableFuture<Result> prepare(Mode mode) {
        return prepare(new Request(mode, null));
    }
    CompletableFuture<Result> prepare(Request request) {
        return prepare(request, ignored -> {});
    }
    CompletableFuture<Result> prepare(Request request, Consumer<Progress> progress) {
        return CompletableFuture.supplyAsync(() -> {
            ProgressReporter reporter = new ProgressReporter(progress);
            try {
                reporter.update(1, "开始重载");
                return request.menu() != null ? layout(request.menu(), reporter)
                    : request.mode() == Mode.YAML ? layouts(reporter) : build(request.mode(), reporter);
            }
            catch (Exception error) { throw new CompletionException(error); }
        }, worker);
    }
    private Result layout(String id, ProgressReporter progress) throws Exception {
        long start = System.nanoTime();
        progress.update(5, "检查菜单");
        if (latest == null) throw new IllegalStateException("尚无资源，请执行 /dialogmenu reload all");
        LayoutStore store = new LayoutStore(data.resolve("layouts"));
        var existing = latest.documents().get(id);
        String name = existing == null ? LayoutStore.id(id) : LayoutStore.source(existing);
        Path file = store.file(name);
        Map<Path, Stamp> input = stamps(Set.of(file));
        if (!input.containsKey(file)) throw new IllegalArgumentException("布局文件不存在：" + name);
        if (input.get(file).equals(latest.inputs().get(file))) {
            progress.update(95, "菜单未变化，复用已加载快照");
            return new Result(latest, false, false);
        }
        progress.update(30, "解析菜单");
        var replacements = store.load(name);
        Map<String, LayoutStore.Document> documents = new LinkedHashMap<>(latest.documents());
        Set<String> affected = new HashSet<>();
        documents.entrySet().removeIf(entry -> {
            if (!LayoutStore.source(entry.getValue()).equals(name)) return false;
            affected.add(entry.getKey());
            return true;
        });
        for (var entry : replacements.entrySet())
            if (documents.putIfAbsent(entry.getKey(), entry.getValue()) != null)
                throw new IllegalArgumentException("重复布局ID：" + entry.getKey());
        affected.addAll(replacements.keySet());
        if (!documents.containsKey(latest.settings().defaultLayout()))
            throw new IllegalArgumentException("default-layout不存在；旧布局保留");
        LayoutStore.validateLinks(documents);
        progress.update(65, "校验菜单与资源引用");
        try {
            validate(MenuTemplate.catalog(replacements.values()), latest.renderer(), latest.fonts(),
                latest.settings(), progress, 65, 90);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("布局不能复用现有资源：" + error.getMessage()
                + "；旧布局保留，需要新字形时执行 /dialogmenu reload all", error);
        }
        Map<Path, Stamp> inputs = new HashMap<>(latest.inputs());
        inputs.putAll(input);
        Snapshot next = new Snapshot(Collections.unmodifiableMap(documents), latest.assets(), latest.renderer(),
            latest.fonts(), latest.settings(), latest.pack(), Map.copyOf(inputs), latest.resourceInputs(),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), Thread.currentThread().getName(), ++generation);
        cache.retain(next, next.pack());
        latest = next;
        progress.update(98, "菜单已准备，等待发布");
        return new Result(next, true, false, Set.copyOf(affected));
    }
    private Result layouts(ProgressReporter progress) throws Exception {
        long start = System.nanoTime();
        progress.update(5, "扫描配置与菜单文件");
        if (latest == null) throw new IllegalStateException("尚无资源，请执行 /dialogmenu reload all");
        Map<Path, Stamp> current = yamlInputs();
        if (latest.inputs().equals(current)) {
            progress.update(95, "菜单未变化，复用已加载快照");
            return new Result(latest, false, false);
        }
        progress.update(20, "读取重载设置");
        Settings settings = settings();
        if (!settings.resourceRoot().equals(latest.settings().resourceRoot())
            || !settings.craftEngineDefinitions().equals(latest.settings().craftEngineDefinitions())
            || !latest.settings().textScales().containsAll(settings.textScales())
            || !latest.settings().textColors().containsAll(settings.textColors()))
            throw new IllegalArgumentException("资源目录、CE定义文件、字体倍率或颜色范围已变化，请执行 /dialogmenu reload all；旧布局保留");
        progress.update(35, "解析全部菜单");
        var documents = new LayoutStore(data.resolve("layouts")).load();
        if (!documents.containsKey(settings.defaultLayout()))
            throw new IllegalArgumentException("default-layout不存在");
        progress.update(60, "校验菜单与已准备资源");
        try {
            validate(MenuTemplate.catalog(documents.values()), latest.renderer(), latest.fonts(),
                settings, progress, 60, 90);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("布局不能复用现有资源：" + error.getMessage()
                + "；旧布局保留，需要新字形时执行 /dialogmenu reload all", error);
        }
        Snapshot next = new Snapshot(documents, latest.assets(), latest.renderer(), latest.fonts(), settings,
            latest.pack(), current, latest.resourceInputs(),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), Thread.currentThread().getName(), ++generation);
        cache.retain(next, next.pack());
        latest = next;
        progress.update(98, "菜单已准备，等待发布");
        return new Result(next, true, false);
    }
    private Settings settings() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(data.resolve("config.yml")));
        int width = LayoutStore.integer(yaml, "canvas-width", 300, 64, 512);
        int maxPixels = LayoutStore.integer(yaml, "max-canvas-pixels", 131072, 4096, 524288);
        List<Double> scales = new ArrayList<>();
        for (Object value : yaml.getList("text-scales", List.of(1.0))) {
            if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() < .5 || n.doubleValue() > 4)
                throw new IllegalArgumentException("text-scales须为0.5..4的倍率列表");
            if (!scales.contains(n.doubleValue())) scales.add(n.doubleValue());
        }
        double defaultScale = LayoutStore.number(yaml, "default-text-scale", 1);
        if (!scales.contains(defaultScale)) throw new IllegalArgumentException("default-text-scale须在text-scales中");
        List<Integer> colors = new ArrayList<>();
        if (yaml.contains("text-colors") && !yaml.isList("text-colors"))
            throw new IllegalArgumentException("text-colors须为#RRGGBB列表");
        for (String color : LayoutStore.strings(yaml.get("text-colors"))) {
            if (!color.matches("#[0-9a-fA-F]{6}"))
                throw new IllegalArgumentException("text-colors须为#RRGGBB列表");
            colors.add(Integer.parseInt(color.substring(1), 16));
        }
        return new Settings(width, maxPixels, yaml.getString("default-layout", "layout-demo"),
            yaml.getBoolean("close-with-escape", false), List.copyOf(scales), defaultScale,
            yaml.getBoolean("craftengine-auto-reload", true),
            data.resolve(yaml.getString("craftengine-resources", "../CraftEngine/resources")).normalize(),
            LayoutStore.integer(yaml, "resource-build-cpu-percent", 80, 1, 100),
            List.copyOf(LayoutStore.strings(yaml.get("craftengine-definitions"))), List.copyOf(colors),
            yaml.getBoolean("reload-progress-bossbar", true));
    }
    private Result build(Mode mode, ProgressReporter progress) throws Exception {
        long start = System.nanoTime();
        progress.update(5, "检查资源缓存");
        cache.prune();
        // 仅资源重载不读取磁盘上的待应用布局或配置。
        boolean all = mode == Mode.ALL || latest == null;
        progress.update(10, "读取重载配置");
        Map<Path, Stamp> yamlInputs = all ? yamlInputs() : latest.inputs();
        Settings settings = all ? settings() : latest.settings();
        progress.update(15, "准备菜单和资源清单");
        try (var budget = BuildBudget.open(settings.resourceBuildCpuPercent())) {
            return build(all, yamlInputs, settings, start, progress);
        }
    }
    private Result build(boolean all, Map<Path, Stamp> yamlInputs, Settings settings, long start,
                         ProgressReporter progress) throws Exception {
        if (latest != null && builtInputs.equals(yamlInputs)
            && latest.resourceInputs().equals(resourceInputs(latest.assets(), settings))) {
            progress.update(95, "资源未变化，复用已准备快照");
            return new Result(latest, false, false);
        }
        var documents = all ? new LayoutStore(data.resolve("layouts")).load() : latest.documents();
        if (!documents.containsKey(settings.defaultLayout())) throw new IllegalArgumentException("default-layout不存在");
        progress.update(20, "扫描图像与CraftEngine资源");
        var assets = new VanillaAssets(data.resolve("images"), settings.resourceRoot(), settings.craftEngineDefinitions());
        Map<Path, Stamp> initialResources = resourceInputs(assets, settings);
        var renderer = new MenuRenderer(assets);
        List<LayoutStore.Document> catalog = MenuTemplate.catalog(documents.values());
        var widths = new TreeSet<>(List.of(300, 360, 420, settings.width()));
        progress.update(24, "生成字体与图片资源");
        var fonts = FontPack.create(catalog, renderer, preview, widths, settings.maxPixels(),
            MenuTemplate.characters(documents.values()), settings.textScales(), CanvasScale.OPTIONS,
            settings.textColors(), step -> progress.update(24 + step * 48 / 85,
                step < 55 ? "准备菜单图文" : "生成字形图集"));
        validate(catalog, renderer, fonts, settings, progress, 73, 80);
        assets.verifyInputs();
        if (all && !yamlInputs.equals(yamlInputs())) throw new IOException("构建期间布局或配置发生变化，请重试");
        var afterResources = resourceInputs(assets, settings);
        for (var entry : initialResources.entrySet())
            if (!entry.getValue().equals(afterResources.get(entry.getKey())))
                throw new IOException("构建期间图片发生变化，请重试");
        Path packDirectory = data.resolve("pack-cache");
        Files.createDirectories(packDirectory);
        Path exported = Files.createTempDirectory(packDirectory, "staging-");
        Path zip = packDirectory.resolve("dialog-" + UUID.randomUUID() + ".zip");
        try {
            progress.update(81, "写入资源包文件");
            fonts.write(exported, step -> progress.update(81 + step * 8 / 100, "写入字体与图片"));
            List<Path> files;
            try (var paths = Files.walk(exported)) {
                files = paths.filter(Files::isRegularFile).sorted().toList();
            }
            progress.update(90, "压缩资源包");
            try (var output = new ZipOutputStream(Files.newOutputStream(zip))) {
                int totalFiles = Math.max(1, files.size()), completedFiles = 0;
                for (Path path : files) {
                    BuildBudget.checkpoint();
                    output.putNextEntry(new ZipEntry(exported.relativize(path).toString().replace('\\', '/')));
                    Files.copy(path, output);
                    output.closeEntry();
                    completedFiles++;
                    progress.update(90 + completedFiles * 8 / totalFiles, "压缩资源包");
                }
            }
        } catch (Exception error) {
            Files.deleteIfExists(zip);
            throw error;
        } finally {
            EditorFiles.remove(exported);
        }
        Map<Path, Stamp> dependencies = resourceInputs(assets, settings);
        try {
            assets.verifyInputs();
            if (!afterResources.equals(dependencies) || all && !yamlInputs.equals(yamlInputs()))
                throw new IOException("构建期间输入发生变化，请重试");
        } catch (Exception error) {
            Files.deleteIfExists(zip);
            throw error;
        }
        BuildBudget.settle();
        Snapshot next = new Snapshot(documents, assets, renderer, fonts, settings, zip, yamlInputs,
            dependencies, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
            Thread.currentThread().getName(), ++generation);
        cache.retain(next, zip);
        builtInputs = yamlInputs;
        latest = next;
        progress.update(99, "资源快照构建完成");
        return new Result(next, true);
    }
    private void validate(List<LayoutStore.Document> catalog, MenuRenderer renderer, FontPack fonts,
                          Settings settings, ProgressReporter progress, int start, int end) throws Exception {
        var widths = new TreeSet<>(List.of(300, 360, 420, settings.width()));
        int total = Math.max(1, catalog.size() * widths.size()), completed = 0;
        // 可选倍率在玩家选择时校验，不要求每个布局加载时适配所有倍率。
        for (var doc : catalog) for (int size : widths) {
            BuildBudget.checkpoint();
            var base = MenuTemplate.preview(doc, preview);
            Context active = new Context() {
                public String expand(String text) { return base.expand(text); }
                public Icon item(int slot) { return base.item(slot); }
                public boolean permitted(String permission) { return base.permitted(permission); }
                public double textScale() { return settings.defaultTextScale(); }
            };
            var frame = renderer.draw(doc.menu(), active, size, settings.maxPixels());
            new SpriteCanvas().encode(doc.menu(), frame, active, fonts, region -> null);
            fonts.validateDynamic(doc, active, size, MenuTemplate.characters(catalog));
            completed++;
            progress.update(start + completed * (end - start) / total, "校验菜单与资源");
        }
    }
    private Map<Path, Stamp> yamlInputs() throws IOException {
        Set<Path> files = new HashSet<>();
        files.add(data.resolve("config.yml"));
        scan(data.resolve("layouts"), files);
        return stamps(files);
    }
    private static void scan(Path root, Set<Path> files) throws IOException {
        if (Files.exists(root)) try (var paths = Files.walk(root.toRealPath())) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                BuildBudget.checkpoint();
                files.add(path);
            });
        }
    }
    private Map<Path, Stamp> resourceInputs(VanillaAssets assets, Settings settings) throws IOException {
        Set<Path> files = new HashSet<>();
        scan(data.resolve("images"), files);
        files.addAll(assets.craftEngine.dependencies());
        return stamps(files);
    }
    private static Map<Path, Stamp> stamps(Set<Path> files) throws IOException {
        Map<Path, Stamp> result = new HashMap<>();
        for (Path file : files) if (Files.exists(file)) {
            BuildBudget.checkpoint();
            result.put(file, new Stamp(Files.size(file), Files.getLastModifiedTime(file),
                Files.isRegularFile(file) ? SourceFiles.digest(Files.readAllBytes(file)) : "directory"));
        }
        return Map.copyOf(result);
    }
    CompletableFuture<Void> export(Snapshot snapshot) {
        return CompletableFuture.runAsync(() -> {
            try { snapshot.fonts().write(data.resolve("resourcepack")); }
            catch (IOException error) { throw new CompletionException(error); }
        }, worker);
    }
    CompletableFuture<Void> savePreferences(String yaml) {
        return CompletableFuture.runAsync(() -> {
            try { Files.writeString(data.resolve("player-text-scales.yml"), yaml); }
            catch (IOException error) { throw new CompletionException(error); }
        }, worker);
    }
    @Override public void close() { worker.shutdownNow(); }
}
