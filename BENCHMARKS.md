# Offline performance benchmarks

The opt-in `benchmark` source set uses JMH 1.37. It is separate from the plugin JAR and from `check`: variable host load must not make functional CI fail. Use `benchmarkSmoke` to validate fixtures first. All layouts, images, values, players and responses are synthetic. No running server, database, player connection or resource-pack publisher is used.

## Run

Use the same JDK and compile dependencies as the regular project build (Gradle 9, Java 21 or newer). For a newer runtime/toolchain, pass the existing `-PjavaVersion` property. A Java 25 comparison, for example:

```sh
gradle -PjavaVersion=25 benchmarkSmoke
gradle -PjavaVersion=25 benchmark
python3 tools/benchmark_report.py build/reports/benchmarks/results.json
```

`DIALOGMENU_LOCAL_CLASSPATH` has the same meaning as in the normal build. For offline environments, `DIALOGMENU_BENCHMARK_CLASSPATH` can contain the pinned JMH core/annotation processor 1.37, jopt-simple 5.0.4 and commons-math3 3.6.1 JARs, separated by the platform path separator. Otherwise Gradle resolves these benchmark-only dependencies normally. No benchmark dependency is added to the plugin runtime.

Default steady-state measurements use three 500 ms warm-up iterations, five 500 ms measurement iterations, two independent JVM forks and one thread. Forks have a 512 MiB initial heap and 1 GiB maximum heap. `-prof gc` records allocation/GC alongside sampled operation latencies. Results include raw iteration data, runtime information, and P50/P95/P99. Check allocation-profiler availability on the selected JVM; unsupported data must not be interpreted as zero allocation.

Choose another output or pass standard JMH options:

```sh
gradle benchmark -PbenchmarkOutput=build/reports/benchmarks/candidate.json \
  '-PbenchmarkArgs=dev.dialogmenu.PipelineBenchmark -f 3 -wi 5 -i 10 -w 1s -r 1s'
```

For a short harness smoke run, override forks/iterations/durations; do not publish its scores as a performance baseline:

```sh
gradle benchmark '-PbenchmarkArgs=dev.dialogmenu.*Benchmark -f 1 -wi 1 -i 1 -w 100ms -r 100ms'
```

`benchmarkClasspath` writes the resolved classpath to `build/benchmark/classpath.txt` for external harnesses. This is a local build artifact, not a distributable file.

## Scope of each measurement

| Group | Operations and parameters | Timed boundary |
| --- | --- | --- |
| Pipeline | 12 / 48 image-and-text items; YAML parse, warm render, warm encode, JSON serialize, opening pipeline, changing text | The first four isolate stages. Opening and refresh use the snapshot presentation cache and include click-token creation and JSON serialization. Opening uses a new canvas cache with resources already prepared. |
| Sessions | 12 / 60 rows; 1 / 50 active synthetic players | One player's atomic initial-list bind, separate bind-and-populate, value update, collection replacement or form completion, including the real session/template/render/encode/JSON path. Players are serviced serially, like the server main thread; this is not 50 concurrent Bukkit threads. |
| Text | 32 / 512 input characters | Styled mixed CJK/Latin text parsing and glyph extraction. |
| Resources | 1 / 13 layouts; 1 / 4 resource workers | Cold engine/directory compilation, unchanged cache lookup, persisted snapshot restore, and changed YAML reload. |
| Native controls | 2 / 12 text/slider fields | Parsing existing configuration and validating a synthetic submission. |

The full default matrix has 48 parameterized cases. Session `bindInitialCollection` supplies the initial rows atomically and measures one complete presentation. `bindAndPopulate` uses a separate bind and update, measuring both presentations. `formSubmit` measures session validation and the return to the menu. Constructing a live Paper dialog/input screen requires the server's registry provider and is outside this offline suite; no fake provider substitutes for its cost.

Resource `coldBuild`, `restoreDiskCache` and `reloadYaml` use single-shot timing, two warm-up and five measured invocations per fork. Cold input files are created outside the timed method; each invocation uses a new directory and engine. It measures real resource preparation, validation, ZIP output and snapshot persistence. It does **not** flush the operating-system file cache, restart the JVM for each invocation, or download files. Required vanilla assets are placed on the benchmark classpath and validated before execution. Thus this is a compilation-cold benchmark with warm JVM/OS caches, not first-install HTTP latency. Restore includes creation of a new engine and reading/decoding its persisted snapshot. Changed YAML reload includes writing the changed source file. Unchanged lookup uses sampled steady-state timing.

Use the standard JMH JSON raw data for confidence analysis. In particular, ten measured single-shot observations do not support a reliable tail-latency SLO: their P95/P99 are descriptive. Run more forks/iterations for a release decision. Allocation from `-prof gc` is JVM-level profiler data, including worker activity; do not label it per-player retained heap. For cold builds, allocation profiling can also include invocation-level fixture creation/cleanup, although those steps are outside the timed method. Disk cache and allocation figures are not network traffic or client GPU measurements.

The renderer/session boundary intentionally stops before Paper registry construction, scheduler handoff and `Player.showDialog`. JSON payload serialization here is a repeatable workload, not a claim about exact network bytes. Real first-visible-pixel latency, client FPS, resource download/apply time, server tick impact and real-player concurrency require a separate server/client experiment. Native-only cases have no valid ratio against a version without native controls.

## Compare versions

Keep hardware, operating system, JDK, dependency classpath, fixture parameters, fork options and measurement settings fixed. Run versions sequentially, reverse their order in a second pass when changes are small, and retain host CPU/load observations. A busy shared machine can distort latency even when the benchmark itself is isolated in a JVM.

```sh
python3 tools/benchmark_report.py candidate.json --baseline baseline.json --output comparison.md
python3 tools/test_benchmark_report.py
```

The report rejects mismatched JVM/measurement settings and units, matches by method/mode/parameters, and explicitly lists unmatched cases. Positive percentages mean more time/allocation per operation. Ratios are descriptive, not significance tests or automatic performance gates. Keep raw files and inspect per-fork spread; avoid presenting a single build-time observation as a speedup.

When adapting the harness to an older implementation, keep identical fixtures, operations and result consumption. Change `BenchmarkRendering` to match that version's actual presentation path if `PreparedLayouts` is absent; omit unsupported native-only cases from that build. Do not remove work merely to make a baseline compile. Record adapter differences and distinguish low-level common-path comparisons from whole-pipeline behavior.
