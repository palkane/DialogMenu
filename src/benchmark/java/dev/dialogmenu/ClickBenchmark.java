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
public class ClickBenchmark {
    @Param({"12", "48"}) public int items;
    @Param({"1", "50"}) public int activePlayers;
    ClickFixtures host;
    ClickFixtures.Client[] clients;
    int sequence;

    @Setup(Level.Trial) public void setup() throws Exception {
        host = new ClickFixtures(items);
        clients = new ClickFixtures.Client[activePlayers];
        for (int i = 0; i < activePlayers; i++) {
            var client = host.bind(BenchmarkFixtures.player(i));
            clients[i] = client;
            // 两页都实际构建一次，使 cached 场景在资源就绪、页面缓存已热的边界上计时。
            host.click(client, "frontend.icon0");
            host.click(client, "frontend.back");
            if (client.visibleRows != items || client.json.length() < 100)
                throw new AssertionError("Click fixture did not render");
        }
    }
    @TearDown(Level.Trial) public void teardown() throws Exception { if (host != null) host.close(); }
    private ClickFixtures.Client next() { return clients[Math.floorMod(sequence++, activePlayers)]; }

    @Benchmark public String clickOpenPage() {
        var client = next();
        host.click(client, "frontend.icon0");
        return client.json;
    }
    @Benchmark public String clickOpenUncachedPage() {
        var client = next();
        host.clearPresentationCaches();
        host.click(client, "frontend.icon0");
        return client.json;
    }
    // 每 op 包含两次真实点击、两次页面构建；保留原始 round-trip 耗时，不隐式除以二。
    @Benchmark public String clickOpenAndBack() {
        var client = next();
        host.click(client, "frontend.icon0");
        host.click(client, "frontend.back");
        return client.json;
    }
    @Benchmark public String clickRefreshState() {
        var client = next();
        host.click(client, "frontend.refresh");
        return client.json;
    }
    @Benchmark public String clickNextListPage() {
        var client = next();
        host.click(client, "frontend.next");
        return client.json;
    }
    @Benchmark public String clickEntryAndBack() {
        var client = next();
        host.click(client, host.firstEntry(client));
        host.click(client, "frontend.back");
        return client.json;
    }
}
