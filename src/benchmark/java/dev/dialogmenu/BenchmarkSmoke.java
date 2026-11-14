package dev.dialogmenu;

/** fixture 的功能断言；不以机器速度作为构建是否通过的条件。 */
public final class BenchmarkSmoke {
    public static void main(String[] args) throws Exception {
        int cases = 0;
        for (int items : new int[]{12, 48}) {
            var benchmark = new PipelineBenchmark(); benchmark.items = items;
            try {
                benchmark.setup(); benchmark.parseLayout(); benchmark.renderWarm(); benchmark.encodeWarm();
                String json = benchmark.serializeWarm(); benchmark.openPipeline(); benchmark.refreshDynamic();
                if (benchmark.fixture.document.menu().nodes().size() != items * 2
                    || benchmark.frame.sprites().size() != items)
                    throw new AssertionError("Pipeline fixture lost text or images");
                System.out.println("Fixture pipeline items=" + items + " json-utf8-bytes="
                    + json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                cases += 6;
            } finally { benchmark.teardown(); }
        }
        for (int players : new int[]{1, 50}) for (int rows : new int[]{12, 60}) {
            var benchmark = new SessionBenchmark(); benchmark.activePlayers = players; benchmark.rows = rows;
            try {
                benchmark.setup(); benchmark.bindAndPopulate(); benchmark.refreshValues(); benchmark.replaceCollection();
                benchmark.formSubmit();
                int before = benchmark.host.presentations;
                benchmark.bindInitialCollection();
                if (benchmark.host.presentations != before + 1 || benchmark.host.visibleRows != rows)
                    throw new AssertionError("Initial collection must need one complete presentation");
                cases += 5;
            } finally { benchmark.teardown(); }
        }
        var resources = new ResourceBenchmark();
        for (int menus : new int[]{1, 13}) {
            for (int workers : new int[]{1, 4}) {
                var cold = new ResourceBenchmark.Cold(); cold.menus = menus; cold.workers = workers;
                try { cold.setup(); resources.coldBuild(cold); cases++; } finally { cold.teardown(); }
            }
            var warm = new ResourceBenchmark.Warm(); warm.menus = menus;
            try {
                warm.setup(); resources.cacheHit(warm); resources.restoreDiskCache(warm); resources.reloadYaml(warm); cases += 3;
            } finally { warm.teardown(); }
        }
        for (int characters : new int[]{32, 512}) {
            var benchmark = new TextBenchmark(); benchmark.characters = characters;
            benchmark.setup(); benchmark.parseAndGlyphs(); cases++;
        }
        for (int fields : new int[]{2, 12}) {
            var benchmark = new NativeControlsBenchmark(); benchmark.fields = fields;
            benchmark.setup(); benchmark.parse(); benchmark.validateSubmission(); cases += 2;
        }
        System.out.println("PASS benchmark fixture cases=" + cases + " (offline; no timing assertions)");
    }
}
