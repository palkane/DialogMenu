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
        for (int players : new int[]{1, 50}) for (int items : new int[]{12, 48})
                for (int variables : new int[]{0, 32}) {
            var benchmark = new ClickBenchmark(); benchmark.activePlayers = players; benchmark.items = items;
            benchmark.variables = variables;
            try {
                benchmark.setup();
                checkClicks(benchmark);
                cases += 6;
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
        for (int variables : new int[]{12, 64}) {
            var benchmark = new ExpansionBenchmark(); benchmark.variables = variables;
            benchmark.setup(); benchmark.verify(); cases += 3;
        }
        System.out.println("PASS benchmark fixture cases=" + cases + " (offline; no timing assertions)");
    }

    private static void checkClicks(ClickBenchmark benchmark) {
        var host = benchmark.host;
        for (var client : benchmark.clients) {
            for (String token : client.buttons.values())
                if (!client.json.contains(token)) throw new AssertionError("Token absent from rendered JSON");
            var stranger = benchmark.activePlayers == 1 ? BenchmarkFixtures.player(2)
                : benchmark.clients[(java.util.Arrays.asList(benchmark.clients).indexOf(client) + 1)
                    % benchmark.activePlayers].player;
            String stale = client.buttons.get("frontend.icon0");
            if (host.tokens.takeResponse(stranger.getUniqueId(), stale) != null)
                throw new AssertionError("Another player consumed click token");
            int before = client.presentations;
            host.click(client, "frontend.icon0");
            if (!client.document.menu().id().equals("detail") || client.visibleRows != 0
                    || client.presentations != before + 1 || client.history.size() != 1)
                throw new AssertionError("Open must build exactly one destination page");
            if (host.tokens.takeResponse(client.player.getUniqueId(), stale) != null)
                throw new AssertionError("Old page token was accepted");
            host.click(client, "frontend.back");
            if (!client.document.menu().id().equals("dashboard") || !client.history.isEmpty()
                    || client.presentations != before + 2 || client.visibleRows != benchmark.items)
                throw new AssertionError("Back must restore page and history");

            before = client.presentations;
            host.click(client, "frontend.refresh");
            if (!host.sessions.expand(client.player, "{flag}").equals("true")
                    || client.presentations != before + 1)
                throw new AssertionError("State click must change state and render once");
            if (benchmark.variables > 0 && !host.sessions.expand(client.player, "{label0}").equals("Value 0 true"))
                throw new AssertionError("Declared variable did not observe the new frame state");

            String oldRow = host.firstEntry(client);
            before = client.presentations;
            host.click(client, "frontend.next");
            if (client.page != 1 || client.buttons.containsKey(oldRow)
                    || !client.buttons.containsKey(host.firstEntry(client))
                    || client.visibleRows != benchmark.items || client.presentations != before + 1)
                throw new AssertionError("Pagination must replace visible rows and render once");

            String selected = host.firstEntry(client).substring("entry:".length());
            before = client.presentations;
            host.click(client, host.firstEntry(client));
            if (!client.document.menu().id().equals("detail")
                    || !host.sessions.expand(client.player, "{selected}").equals(selected)
                    || client.presentations != before + 1)
                throw new AssertionError("Entry callback must build exactly one detail page");
            host.click(client, "frontend.back");
            if (client.visibleRows != benchmark.items || client.presentations != before + 2)
                throw new AssertionError("Entry return lost the list page");
        }
        // 逐个调用正式入口，检查每种 op 的构建次数；不为微基准加入计时断言。
        for (int i = 0; i < benchmark.activePlayers; i++) benchmark.clickOpenPage();
        for (int i = 0; i < benchmark.activePlayers; i++) benchmark.clickOpenPage();
        for (int i = 0; i < benchmark.activePlayers; i++) benchmark.clickOpenUncachedPage();
        for (int i = 0; i < benchmark.activePlayers; i++) benchmark.clickOpenUncachedPage();
        for (var client : benchmark.clients) {
            if (!client.document.menu().id().equals("dashboard")) throw new AssertionError("Wrong page after navigation");
            client.presentations = 0;
        }
        for (int i = 0; i < benchmark.activePlayers; i++) {
            benchmark.clickOpenAndBack(); benchmark.clickEntryAndBack();
            benchmark.clickRefreshState(); benchmark.clickNextListPage();
        }
        // sequence 在多玩家下循环分配：每轮四种操作合计六次构建。
        int presentations = java.util.Arrays.stream(benchmark.clients).mapToInt(c -> c.presentations).sum();
        if (presentations != 6 * benchmark.activePlayers) throw new AssertionError("Unexpected presentation count");
        System.out.println("Fixture clicks items=" + benchmark.items + " activePlayers=" + benchmark.activePlayers
            + " variables=" + benchmark.variables
            + " json-utf8-bytes=" + benchmark.clients[0].json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }
}
