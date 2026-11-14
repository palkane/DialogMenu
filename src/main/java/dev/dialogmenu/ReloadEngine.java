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
    enum Mode { YAML, RESOURCE, ALL, FULL }
    record Progress(int percent, String stage) {}
    private record Validation(LayoutStore.Document doc, Context context, int width) {}
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
            throw new IllegalArgumentException("/dialogmenu reload [yaml|resource|all|full|菜单名]");
        }
    }
    record Settings(int width, int maxPixels, String defaultLayout, boolean escape,
                    List<Double> textScales, double defaultTextScale, boolean autoCraftEngine,
                    Path resourceRoot, int resourceBuildCpuPercent, int resourceBuildThreads, List<String> craftEngineDefinitions,
                    List<Integer> textColors, boolean reloadProgressBossBar, boolean reuseCompiledCache,
                    List<Double> canvasScales, double defaultCanvasScale, List<MenuOpeners.Rule> openers,
                    boolean hideDialogWarning, boolean hideScrollbar, ResourceVersion resourceVersion) {}
    record Stamp(long size, FileTime modified, String digest) {}
    record Snapshot(Map<String, LayoutStore.Document> documents, VanillaAssets assets, MenuRenderer renderer,
                    FontPack fonts, Settings settings, Path pack, Map<Path, Stamp> inputs,
                    Map<Path, Stamp> resourceInputs, long buildMillis,
                    String buildThread, long generation, PreparedLayouts presentations) {
        Snapshot(Map<String, LayoutStore.Document> documents, VanillaAssets assets, MenuRenderer renderer,
                 FontPack fonts, Settings settings, Path pack, Map<Path, Stamp> inputs,
                 Map<Path, Stamp> resourceInputs, long buildMillis, String buildThread, long generation) {
            this(documents, assets, renderer, fonts, settings, pack, inputs, resourceInputs,
                buildMillis, buildThread, generation, new PreparedLayouts(fonts));
        }
    }
    record Result(Snapshot snapshot, boolean changed, boolean resourcesChanged, Set<String> menus) {
        Result(Snapshot snapshot, boolean changed, boolean resourcesChanged) {
            this(snapshot, changed, resourcesChanged, Set.of());
        }
        Result(Snapshot snapshot, boolean changed) { this(snapshot, changed, changed); }
    }
    record Startup(Result result, Exception yamlError) {}
    private final Path data;
    private final Context preview;
    private final PackCache cache;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "DialogMenu-reload");
        thread.setDaemon(true);
        return thread;
    });
    private Snapshot latest;
    private CompiledSnapshot persisted;
    // YAML 已应用不等于字库已按该版本重建，二者分别记录。
    private Map<Path, Stamp> builtInputs = Map.of();
    private long generation;
    private boolean reuseCompiledCache;
    private volatile String libraryStatus = "等待检查";

    ReloadEngine(Path data, Context preview) {
        this(data, preview, false);
    }
    ReloadEngine(Path data, Context preview, boolean reuseCompiledCache) {
        this.data = realPath(data);
        this.preview = preview;
        this.cache = new PackCache(this.data.resolve("pack-cache"));
        this.reuseCompiledCache = reuseCompiledCache;
    }
    private static Path realPath(Path data) {
        try { return data.toRealPath(); }
        catch (IOException error) { throw new UncheckedIOException("无法解析Dialog数据目录：" + data, error); }
    }
    void retainPack(Object consumer, Snapshot snapshot) { cache.retain(consumer, snapshot.pack()); }

    private CompiledSnapshot persisted() throws IOException {
        if (persisted == null) persisted = new CompiledSnapshot(data);
        return persisted;
    }
    CompletableFuture<Result> restore() {
        return CompletableFuture.supplyAsync(() -> {
            try { return restored(); }
            catch (Exception error) { throw new CompletionException(error); }
        }, worker);
    }
    String libraryStatus() { return libraryStatus; }
    private void prepareLibrary(ResourceVersion version, Consumer<Progress> progress) throws IOException {
        libraryStatus = "准备中";
        try {
            new AssetLibrary(data, version).prepare(stage -> progress.accept(new Progress(5, stage)));
            libraryStatus = "就绪";
        } catch (IOException error) {
            libraryStatus = "失败";
            throw error;
        }
    }
    CompletableFuture<Startup> startup() { return startup(ignored -> {}); }
    CompletableFuture<Startup> startup(Consumer<Progress> progress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                progress.accept(new Progress(5, "恢复快照及对应版本资源"));
                Result restored = restored();
                if (reuseCompiledCache) return new Startup(restored, null);
                try {
                    return new Startup(layouts(new ProgressReporter(ignored -> {})), null);
                } catch (Exception error) {
                    return new Startup(restored, error);
                }
            } catch (Exception error) { throw new CompletionException(error); }
        }, worker);
    }
    private Result restored() throws Exception {
        if (latest == null) {
            libraryStatus = "准备中";
            CompiledSnapshot.Loaded saved;
            try { saved = persisted().load(); }
            catch (Exception error) { libraryStatus = "失败"; throw error; }
            libraryStatus = "就绪";
            saved.snapshot().presentations().prepare(saved.snapshot(), preview);
            latest = saved.snapshot();
            builtInputs = saved.builtInputs();
            generation = latest.generation();
            cache.retain(latest, latest.pack());
        }
        return new Result(latest, false, false);
    }
    CompletableFuture<Result> prepareForCraftEngine(boolean reloading) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Result restored = restored();
                return reuseCompiledCache || !reloading ? restored
                    : build(Mode.RESOURCE, new ProgressReporter(ignored -> {}));
            } catch (Exception error) { throw new CompletionException(error); }
        }, worker);
    }
    private void install(Snapshot next, Map<Path, Stamp> built) throws Exception {
        next.presentations().prepare(next, preview);
        persisted().save(next, built);
        cache.retain(next, next.pack());
        builtInputs = built;
        latest = next;
        generation = next.generation();
    }

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
        MenuOpeners.validate(latest.settings().openers(), documents.keySet());
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
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), Thread.currentThread().getName(), generation + 1);
        install(next, builtInputs);
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
        if (settings.hideDialogWarning() != latest.settings().hideDialogWarning()
            || settings.hideScrollbar() != latest.settings().hideScrollbar())
            throw new IllegalArgumentException("原生警告或滚动条隐藏设置已变化，请执行 /dialogmenu reload all 并接收新资源包；旧布局保留");
        if (!settings.resourceVersion().equals(latest.settings().resourceVersion())
            || !settings.resourceRoot().equals(latest.settings().resourceRoot())
            || !settings.craftEngineDefinitions().equals(latest.settings().craftEngineDefinitions())
            || !latest.settings().textScales().containsAll(settings.textScales())
            || !latest.settings().canvasScales().containsAll(settings.canvasScales())
            || !latest.settings().textColors().containsAll(settings.textColors()))
            throw new IllegalArgumentException("资源版本、目录、CE定义文件、字体倍率或颜色范围已变化，请执行 /dialogmenu reload all；旧布局保留");
        progress.update(35, "解析全部菜单");
        var documents = new LayoutStore(data.resolve("layouts")).load();
        if (!documents.containsKey(settings.defaultLayout()))
            throw new IllegalArgumentException("default-layout不存在");
        MenuOpeners.validate(settings.openers(), documents.keySet());
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
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), Thread.currentThread().getName(), generation + 1);
        install(next, builtInputs);
        reuseCompiledCache = settings.reuseCompiledCache();
        progress.update(98, "菜单已准备，等待发布");
        return new Result(next, true, false);
    }
    private Settings settings() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(data.resolve("config.yml")));
        int width = LayoutStore.integer(yaml, "canvas-width", 300, 64, 512);
        int maxPixels = LayoutStore.integer(yaml, "max-canvas-pixels", 131072, 4096, 1048576);
        List<Double> scales = CanvasScale.options(yaml, "text-scales", List.of(1.0));
        // 旧配置缺少此字段时保留原有画布倍率，不回写配置或玩家偏好。
        List<Double> canvasScales = CanvasScale.options(yaml, "canvas-scales", CanvasScale.OPTIONS);
        double defaultCanvas = LayoutStore.number(yaml, "default-canvas-scale", 1);
        if (!canvasScales.contains(defaultCanvas))
            throw new IllegalArgumentException("default-canvas-scale须在canvas-scales中");
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
            yaml.getBoolean("close-with-escape", true), List.copyOf(scales), defaultScale,
            yaml.getBoolean("craftengine-auto-reload", true),
            data.resolve(yaml.getString("craftengine-resources", "../CraftEngine/resources")).normalize(),
            LayoutStore.integer(yaml, "resource-build-cpu-percent", 80, 1, 100),
            LayoutStore.integer(yaml, "resource-build-threads", BuildTasks.defaultThreads(), 1, 8),
            List.copyOf(LayoutStore.strings(yaml.get("craftengine-definitions"))), List.copyOf(colors),
            yaml.getBoolean("reload-progress-bossbar", true), yaml.getBoolean("reuse-compiled-cache", false),
            canvasScales, defaultCanvas, MenuOpeners.parse(yaml),
            yaml.getBoolean("hide-dialog-warning", false), yaml.getBoolean("hide-scrollbar", false),
            new ResourceVersion(yaml.getString("resource-version", ResourceVersion.DEFAULT.id())));
    }
    private Result build(Mode mode, ProgressReporter progress) throws Exception {
        long start = System.nanoTime();
        progress.update(5, "检查资源缓存");
        if (latest == null) persisted().prune(cache);
        else cache.prune(latest.pack());
        if (mode == Mode.RESOURCE && latest == null)
            throw new IllegalStateException("尚无已编译资源，请执行 /dialogmenu reload all");
        // 仅资源重载不读取磁盘上的待应用布局或配置。
        boolean all = mode == Mode.ALL || mode == Mode.FULL;
        progress.update(10, "读取重载配置");
        Map<Path, Stamp> yamlInputs = all ? yamlInputs() : latest.inputs();
        Settings settings = all ? settings() : latest.settings();
        prepareLibrary(settings.resourceVersion(), step -> progress.update(step.percent(), step.stage()));
        progress.update(15, "准备菜单和资源清单");
        try (var budget = BuildBudget.open(settings.resourceBuildCpuPercent());
             var tasks = new BuildTasks(settings.resourceBuildThreads(), settings.resourceBuildCpuPercent())) {
            return build(mode, yamlInputs, settings, start, progress, tasks);
        }
    }
    private Result build(Mode mode, Map<Path, Stamp> yamlInputs, Settings settings, long start,
                         ProgressReporter progress, BuildTasks tasks) throws Exception {
        boolean all = mode == Mode.ALL || mode == Mode.FULL;
        boolean full = mode == Mode.FULL;
        var documents = all ? new LayoutStore(data.resolve("layouts")).load() : latest.documents();
        List<LayoutStore.Document> catalog = MenuTemplate.catalog(documents.values());
        boolean versionChanged = latest == null || !settings.resourceVersion().equals(latest.settings().resourceVersion());
        ModelPack models = full || versionChanged || !sameModelInputs(latest.resourceInputs())
            || !ModelPack.matches(latest.fonts().modelPack(), catalog)
            ? ModelPack.prepare(data, catalog, settings.resourceVersion()) : latest.fonts().modelPack();
        if (all) {
            progress.update(17, "后台缓存编辑器纹理与方块素材");
            new DialogMenuEditorFiles(data, settings.resourceRoot()).preload(full,
                (done, total) -> progress.update(17 + done * 2 / Math.max(1, total),
                    "缓存编辑器素材 " + done + "/" + total));
        }
        if (!full && !versionChanged && builtInputs.equals(yamlInputs)
            && latest.resourceInputs().equals(resourceInputs(latest.assets()))) {
            progress.update(95, "资源未变化，复用已准备快照");
            return new Result(latest, false, false);
        }
        if (!documents.containsKey(settings.defaultLayout())) throw new IllegalArgumentException("default-layout不存在");
        MenuOpeners.validate(settings.openers(), documents.keySet());
        progress.update(20, "扫描图像与CraftEngine资源");
        var assets = new VanillaAssets(data.resolve("images"), settings.resourceRoot(), settings.craftEngineDefinitions(),
            settings.resourceVersion());
        Map<Path, Stamp> initialResources = resourceInputs(assets);
        // 编译缓存仅活到本轮校验结束，最多保留64MiB像素。
        var renderer = new MenuRenderer(assets, 16_777_216);
        var widths = new TreeSet<>(List.of(300, 360, 420, settings.width()));
        progress.update(24, "生成字体与图片资源");
        var fonts = FontPack.create(catalog, renderer, preview, widths, settings.maxPixels(),
            MenuTemplate.characters(documents.values()), settings.textScales(), settings.canvasScales(),
            settings.textColors(), step -> progress.update(24 + step * 48 / 85,
                step < 55 ? "准备菜单图文" : "生成字形图集"), tasks,
            !full && !versionChanged ? latest.fonts() : null, models);
        progress.update(72, fonts.reusedGlyphs() ? "字体依赖未变化，复用字形图集" : "字形图集生成完成");
        validate(catalog, renderer, fonts, settings, progress, 73, 80, tasks);
        assets.verifyInputs();
        if (all && !yamlInputs.equals(yamlInputs())) throw new IOException("构建期间布局或配置发生变化，请重试");
        var afterResources = resourceInputs(assets);
        for (var entry : initialResources.entrySet())
            if (!entry.getValue().equals(afterResources.get(entry.getKey())))
                throw new IOException("构建期间图片发生变化，请重试");
        Path packDirectory = data.resolve("pack-cache");
        Files.createDirectories(packDirectory);
        Path exported = Files.createTempDirectory(packDirectory, "staging-");
        Path zip = packDirectory.resolve("dialog-" + UUID.randomUUID() + ".zip");
        try {
            progress.update(81, "写入资源包文件");
            fonts.writeBase(exported, step -> progress.update(81 + step * 8 / 100, "写入字体与图片"), tasks);
            NativeUiTextures.write(exported, settings.hideDialogWarning(), settings.hideScrollbar());
            fonts.modelPacks(data);
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
        Map<Path, Stamp> dependencies = resourceInputs(assets);
        try {
            assets.verifyInputs();
            if (!afterResources.equals(dependencies) || all && !yamlInputs.equals(yamlInputs()))
                throw new IOException("构建期间输入发生变化，请重试");
            BuildBudget.settle();
        } catch (Exception error) {
            Files.deleteIfExists(zip);
            throw error;
        }
        Snapshot next = new Snapshot(documents, assets, renderer, fonts, settings, zip, yamlInputs,
            dependencies, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
            Thread.currentThread().getName(), generation + 1);
        try {
            progress.update(99, "保存编译快照");
            install(next, yamlInputs);
            if (all) reuseCompiledCache = settings.reuseCompiledCache();
        } catch (Exception error) {
            Files.deleteIfExists(zip);
            throw error;
        }
        progress.update(99, "资源快照构建完成");
        return new Result(next, true);
    }
    private boolean sameModelInputs(Map<Path, Stamp> previousResources) throws IOException {
        Set<Path> paths = new HashSet<>();
        ModelPack.inputs(data, paths);
        Map<Path, Stamp> current = stamps(paths);
        for (Path path : paths)
            if (!Objects.equals(current.get(path), previousResources.get(path))) return false;
        return true;
    }
    private void validate(List<LayoutStore.Document> catalog, MenuRenderer renderer, FontPack fonts,
                          Settings settings, ProgressReporter progress, int start, int end) throws Exception {
        validate(catalog, renderer, fonts, settings, progress, start, end, null);
    }
    private void validate(List<LayoutStore.Document> catalog, MenuRenderer renderer, FontPack fonts,
                          Settings settings, ProgressReporter progress, int start, int end,
                          BuildTasks tasks) throws Exception {
        var widths = new TreeSet<>(List.of(300, 360, 420, settings.width()));
        int[] completed = {0};
        String characters = MenuTemplate.characters(catalog);
        var dynamic = new FontPack.Validation();
        List<Validation> jobs = new ArrayList<>();
        // 可选倍率在玩家选择时校验，不要求每个布局加载时适配所有倍率。
        for (var doc : catalog) for (int size : MenuRenderer.preparedWidths(doc.menu(), widths,
            settings.defaultCanvasScale(), settings.maxPixels())) {
            BuildBudget.checkpoint();
            var base = MenuTemplate.preview(doc, preview);
            Context active = new Context() {
                public String expand(String text) { return base.expand(text); }
                public Icon item(int slot) { return base.item(slot); }
                public boolean permitted(String permission) { return base.permitted(permission); }
                public double textScale() { return settings.defaultTextScale(); }
                public double canvasScale() { return settings.defaultCanvasScale(); }
            };
            jobs.add(new Validation(doc, active, size));
        }
        int total = Math.max(1, jobs.size());
        BuildTasks.ordered(tasks, jobs, job -> {
            var menu = job.doc().menu();
            var frame = renderer.drawRuntime(menu, job.context(), job.width(), settings.maxPixels());
            if (job.width() == MenuRenderer.MAX_WIDTH && !MenuRenderer.modelProtocolFits(menu, frame)) return null;
            var canvas = new SpriteCanvas();
            if (job.doc().controls().overlay() && !job.doc().controls().empty())
                canvas.encodeNative(job.doc(), frame, job.context(), fonts, region -> null);
            else canvas.encode(menu, frame, job.context(), fonts, region -> null);
            fonts.validateDynamic(job.doc(), job.context(), job.width(), characters, dynamic);
            return null;
        }, ignored -> progress.update(start + ++completed[0] * (end - start) / total, "校验菜单与资源"));
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
    private Map<Path, Stamp> resourceInputs(VanillaAssets assets) throws IOException {
        Set<Path> files = new HashSet<>();
        scan(data.resolve("images"), files);
        files.addAll(assets.libraryPaths());
        files.addAll(assets.craftEngine.dependencies());
        ModelPack.inputs(data, files);
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
            try {
                Path target = data.resolve("resourcepack");
                snapshot.fonts().write(target);
                NativeUiTextures.write(target, snapshot.settings().hideDialogWarning(), snapshot.settings().hideScrollbar());
            }
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
