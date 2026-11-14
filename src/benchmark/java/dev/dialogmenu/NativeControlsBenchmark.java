package dev.dialogmenu;

import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;
import io.papermc.paper.dialog.DialogResponseView;
import org.bukkit.configuration.file.YamlConfiguration;
import org.openjdk.jmh.annotations.*;

/** 原生控件独立测量，不与缺少这项功能的版本相除。 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx1g", "-Djava.awt.headless=true"})
@Threads(1)
@State(Scope.Thread)
public class NativeControlsBenchmark {
    @Param({"2", "12"}) public int fields;
    YamlConfiguration config;
    NativeControls.Spec spec;
    DialogResponseView response;
    @Setup public void setup() throws Exception {
        StringBuilder yaml = new StringBuilder("native-controls:\n  inputs:\n");
        for (int i = 0; i < fields; i++) yaml.append("    field").append(i)
            .append(i % 2 == 0 ? ": {type: text, label: Name, initial: Value, max-length: 64}\n"
                : ": {type: slider, label: Amount, min: 0, max: 100, initial: 50, step: 1}\n");
        yaml.append("  buttons:\n    submit: {label: Submit, actions: ['tell: done']}\n");
        config = new YamlConfiguration(); config.loadFromString(yaml.toString());
        spec = NativeControls.parse(config);
        response = (DialogResponseView) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{DialogResponseView.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getText" -> "Value";
                case "getFloat" -> 50f;
                default -> null;
            });
        if (NativeControls.values(spec, response).size() != fields) throw new AssertionError("Missing fields");
    }
    @Benchmark public Object parse() { return NativeControls.parse(config); }
    @Benchmark public Object validateSubmission() { return NativeControls.values(spec, response); }
}
