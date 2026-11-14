package dev.dialogmenu;

import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx1g", "-Djava.awt.headless=true"})
@Threads(1)
@State(Scope.Thread)
public class PipelineBenchmark {
    @Param({"12", "48"}) public int items;
    BenchmarkFixtures.Pipeline fixture;
    MenuModel.Rendered frame;
    Component encoded;
    int sequence;
    @Setup(Level.Trial) public void setup() throws Exception {
        fixture = new BenchmarkFixtures.Pipeline(items);
        frame = fixture.render(fixture.context);
        encoded = fixture.encode(frame, fixture.context, new ActionTokens.Page());
        if (BenchmarkFixtures.JSON.serialize(encoded).length() < 100) throw new AssertionError("Empty fixture");
    }
    @TearDown(Level.Trial) public void teardown() throws Exception { fixture.close(); }
    @Benchmark public Object parseLayout() throws Exception { return LayoutStore.loadText("pipeline", fixture.yaml); }
    @Benchmark public Object renderWarm() throws Exception { return fixture.render(fixture.context); }
    @Benchmark public Object encodeWarm() { return fixture.encode(frame, fixture.context, new ActionTokens.Page()); }
    @Benchmark public String serializeWarm() { return BenchmarkFixtures.JSON.serialize(encoded); }
    @Benchmark public String openPipeline() throws Exception {
        // 每次使用新画布缓存，模拟首次打开；资源和渲染器已经准备好。
        return fixture.present(fixture.context, new SpriteCanvas());
    }
    @Benchmark public String refreshDynamic() throws Exception {
        var context = BenchmarkFixtures.context("Value" + (sequence++ & 4095));
        return fixture.present(context, fixture.canvas);
    }
}
