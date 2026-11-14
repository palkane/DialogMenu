package dev.dialogmenu;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx1g", "-Djava.awt.headless=true"})
@Threads(1)
@State(Scope.Thread)
public class TextBenchmark {
    @Param({"32", "512"}) public int characters;
    String text;
    @Setup public void setup() {
        String unit = "&a物品 &lValue123 &r金币 ";
        text = unit.repeat((characters + unit.length() - 1) / unit.length()).substring(0, characters);
    }
    @Benchmark public Object parseAndGlyphs() { return MenuText.glyphs(MenuText.parse(text)); }
}
