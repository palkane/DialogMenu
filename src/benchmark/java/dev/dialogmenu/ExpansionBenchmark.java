package dev.dialogmenu;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.openjdk.jmh.annotations.*;

/** 一帧内展开32段文本；声明计划复用，变量结果仅在该帧内缓存。 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx1g", "-Djava.awt.headless=true"})
@Threads(1)
@State(Scope.Thread)
public class ExpansionBenchmark {
    @Param({"12", "64"}) public int variables;
    MenuExpansion.Plan plan;
    Map<String, String> external;
    String[] sparse, dense, nested;
    String[] expectedSparse, expectedDense, expectedNested;

    @Setup(Level.Trial) public void setup() {
        var specs = new YamlConfiguration();
        var values = new LinkedHashMap<String, String>();
        var expected = new ArrayList<String>();
        for (int i = 0; i < variables; i++) {
            var spec = specs.createSection("v" + i);
            // 每四个变量形成一条依赖链，覆盖声明顺序与按需求值。
            if (i % 4 == 0) {
                spec.set("type", "external");
                spec.set("source", "value" + i);
                values.put("value" + i, "Value" + i);
                expected.add("Value" + i);
            } else {
                spec.set("type", "literal");
                spec.set("source", "{v" + (i - 1) + "}/" + i);
                expected.add(expected.get(i - 1) + "/" + i);
            }
        }
        var player = specs.createSection("player");
        player.set("type", "papi"); player.set("source", "%benchmark_player%");
        var script = specs.createSection("script");
        script.set("type", "skript"); script.set("source", "benchmark_value");
        plan = new MenuExpansion.Plan(specs);
        external = Map.copyOf(values);
        sparse = new String[32]; dense = new String[32]; nested = new String[32];
        expectedSparse = new String[32]; expectedDense = new String[32]; expectedNested = new String[32];
        String allTokens = String.join(" ", java.util.stream.IntStream.range(0, variables)
            .mapToObj(i -> "{v" + i + "}").toList());
        String allValues = String.join(" ", expected);
        for (int i = 0; i < sparse.length; i++) {
            sparse[i] = "Row " + i + " {v0} {flag} {unknown}";
            expectedSparse[i] = "Row " + i + " Value0 true {unknown}";
            dense[i] = "Row " + i + " " + allTokens + " {player} {script}";
            expectedDense[i] = "Row " + i + " " + allValues + " Player Value";
            nested[i] = "Row " + i + " {v" + (variables - 1) + "} {player} {player} {script}";
            expectedNested[i] = "Row " + i + " " + expected.getLast() + " Player Player Value";
        }
    }

    private String[] expand(String[] texts) {
        var frame = new MenuExpansion(Map.of("flag", "true"), plan, external,
            (source, fallback) -> source.equals("%benchmark_player%") ? "Player" : fallback,
            source -> source.equals("benchmark_value") ? "Value" : null);
        var result = new String[texts.length];
        for (int i = 0; i < texts.length; i++) result[i] = frame.apply(texts[i]);
        return result;
    }

    @Benchmark public String[] sparseFrame() { return expand(sparse); }
    @Benchmark public String[] denseFrame() { return expand(dense); }
    @Benchmark public String[] nestedFrame() { return expand(nested); }

    void verify() {
        if (!Arrays.equals(sparseFrame(), expectedSparse) || !Arrays.equals(denseFrame(), expectedDense)
                || !Arrays.equals(nestedFrame(), expectedNested))
            throw new AssertionError("Expansion fixture changed declaration order or output");
        int[] calls = new int[2];
        var frame = new MenuExpansion(Map.of("flag", "false"), plan, external,
            (source, fallback) -> { calls[0]++; return "Player"; },
            source -> { calls[1]++; return "Value"; });
        for (String text : nested) frame.apply(text);
        if (!frame.apply("{flag}").equals("false") || calls[0] != 1 || calls[1] != 1)
            throw new AssertionError("Variable providers must be evaluated once per frame");
    }
}
