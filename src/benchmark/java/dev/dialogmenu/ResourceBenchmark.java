package dev.dialogmenu;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx1g", "-Djava.awt.headless=true"})
@Threads(1)
public class ResourceBenchmark {
    @State(Scope.Thread)
    public static class Cold {
        @Param({"1", "13"}) public int menus;
        @Param({"1", "4"}) public int workers;
        Path root;
        @Setup(Level.Invocation) public void setup() throws Exception {
            root = BenchmarkFixtures.directory();
            BenchmarkFixtures.resourceInput(root, menus, workers);
        }
        @TearDown(Level.Invocation) public void teardown() throws Exception { BenchmarkFixtures.remove(root); }
    }
    @State(Scope.Thread)
    public static class Warm {
        @Param({"1", "13"}) public int menus;
        Path root;
        ReloadEngine engine;
        int sequence;
        @Setup(Level.Trial) public void setup() throws Exception {
            root = BenchmarkFixtures.directory();
            BenchmarkFixtures.resourceInput(root, menus, 1);
            engine = new ReloadEngine(root, BenchmarkFixtures.context("Value0000"));
            var first = engine.prepare().get(120, TimeUnit.SECONDS);
            if (!first.resourcesChanged() || Files.size(first.snapshot().pack()) == 0) throw new AssertionError("No resource build");
        }
        @TearDown(Level.Trial) public void teardown() throws Exception {
            engine.close(); BenchmarkFixtures.remove(root);
        }
    }
    @Benchmark @BenchmarkMode(Mode.SingleShotTime)
    public long coldBuild(Cold state) throws Exception {
        // 冷的是引擎/目录/编译缓存，不清操作系统页缓存；素材随 benchmark 提供，不含 HTTP。
        try (var engine = new ReloadEngine(state.root, BenchmarkFixtures.context("Value0000"))) {
            var result = engine.prepare().get(120, TimeUnit.SECONDS);
            if (!result.resourcesChanged()) throw new AssertionError("Cold build reused a snapshot");
            return Files.size(result.snapshot().pack());
        }
    }
    @Benchmark @BenchmarkMode(Mode.SampleTime)
    @Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
    @Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
    public Object cacheHit(Warm state) throws Exception {
        var result = state.engine.prepare().get(120, TimeUnit.SECONDS);
        if (result.changed()) throw new AssertionError("Unchanged cache miss");
        return result.snapshot();
    }
    @Benchmark @BenchmarkMode(Mode.SingleShotTime)
    public Object restoreDiskCache(Warm state) throws Exception {
        try (var engine = new ReloadEngine(state.root, BenchmarkFixtures.context("Value0000"))) {
            return engine.restore().get(120, TimeUnit.SECONDS).snapshot();
        }
    }
    @Benchmark @BenchmarkMode(Mode.SingleShotTime)
    public Object reloadYaml(Warm state) throws Exception {
        // 文件改写属于这项重载操作，序号避免只测到“未变化”分支。
        Files.writeString(state.root.resolve("layouts/menu0.yml"), BenchmarkFixtures.layout("menu0", 12)
            + "\n# revision " + state.sequence++ + "\n");
        var result = state.engine.prepare(ReloadEngine.Mode.YAML).get(120, TimeUnit.SECONDS);
        if (!result.changed() || result.resourcesChanged()) throw new AssertionError("Unexpected reload mode");
        return result.snapshot();
    }
}
