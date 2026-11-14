package dev.dialogmenu;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bukkit.entity.Player;
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx1g", "-Djava.awt.headless=true"})
@Threads(1)
@State(Scope.Thread)
public class SessionBenchmark {
    @Param({"12", "60"}) public int rows;
    @Param({"1", "50"}) public int activePlayers;
    BenchmarkFixtures.SessionHost host;
    Player[] players;
    DialogMenuBinding[] bindings;
    boolean[] alternate;
    List<DialogMenuEntry> first, second;
    int sequence;
    @Setup(Level.Trial) public void setup() throws Exception {
        host = new BenchmarkFixtures.SessionHost();
        first = BenchmarkFixtures.entries(rows, false);
        second = BenchmarkFixtures.entries(rows, true);
        players = new Player[activePlayers]; bindings = new DialogMenuBinding[activePlayers];
        alternate = new boolean[activePlayers];
        for (int i = 0; i < activePlayers; i++) {
            players[i] = BenchmarkFixtures.player(i);
            bindings[i] = host.bind(players[i], first);
        }
        if (host.output == null || host.output.length() < 100 || host.visibleRows != rows)
            throw new AssertionError("List was not encoded");
    }
    @TearDown(Level.Trial) public void teardown() throws Exception { host.close(); }
    private int next() { return Math.floorMod(sequence++, activePlayers); }
    @Benchmark public String bindAndPopulate() throws Exception {
        int i = next(); bindings[i] = host.bind(players[i], first); return host.output;
    }
    @Benchmark public String bindInitialCollection() throws Exception {
        int i = next(); bindings[i] = host.bindInitial(players[i], first); return host.output;
    }
    @Benchmark public String refreshValues() throws Exception {
        int i = next();
        if (!host.sessions.update(bindings[i], Map.of("amount", Integer.toString(sequence & 4095))))
            throw new AssertionError("Inactive binding");
        return host.output;
    }
    @Benchmark public String replaceCollection() throws Exception {
        int i = next();
        alternate[i] = !alternate[i];
        if (!host.sessions.updateCollection(bindings[i], "items", alternate[i] ? second : first))
            throw new AssertionError("Inactive binding");
        return host.output;
    }
    @Benchmark public String formSubmit() {
        int i = next();
        if (!host.sessions.openForm(bindings[i], "input", values -> {})) throw new AssertionError("Form did not open");
        host.submit.accept(Map.of("value", "123"));
        return host.output;
    }
}
