package dev.dialogmenu;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real HTTP transfers, disk cache and independent JVMs, without GitHub availability in unit checks. */
public final class AssetLibraryTests {
    private static final List<String> NAMES = List.of("glyphs.bin.gz", "vanilla-icons.zip", "vanilla-inventory.zip");
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("prepare")) {
            new AssetLibrary(Path.of(args[1]), URI.create(args[2]), false, Duration.ofSeconds(5))
                .prepare(ignored -> {});
            return;
        }
        System.out.println("PASS asset library checks=" + run(Path.of(args[0])));
    }

    static int run(Path root) throws Exception {
        Path data = Files.createTempDirectory(root, "asset-download-");
        Path cancelledFile = Files.createTempFile(data, "late-subscription-", ".tmp");
        var late = AssetLibrary.bounded(cancelledFile, 1);
        Files.delete(cancelledFile);
        var cancelledSubscription = new AtomicBoolean();
        late.onSubscribe(new Flow.Subscription() {
            public void request(long count) {}
            public void cancel() { cancelledSubscription.set(true); }
        });
        check(cancelledSubscription.get() && !Files.exists(cancelledFile),
            "A response subscribed after cleanup must not recreate the cancelled temporary file");
        try {
            late.getBody().toCompletableFuture().join();
            throw new AssertionError("Late subscription completed despite deleted destination");
        } catch (CompletionException expected) { checks++; }
        var fixtures = new HashMap<String, byte[]>();
        for (String name : NAMES) try (var input = AssetLibraryTests.class.getResourceAsStream("/" + name)) {
            fixtures.put(name, Objects.requireNonNull(input).readAllBytes());
        }
        var requests = new AtomicInteger();
        var mode = new AtomicReference<>("valid");
        var bodyStarted = new Semaphore(0);
        var gate = new Semaphore(0);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var serverWorkers = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "asset-test-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(serverWorkers);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String name = exchange.getRequestURI().getPath().substring(1);
            String behavior = mode.get();
            byte[] bytes = fixtures.get(name);
            try (exchange) {
                if (bytes == null || behavior.equals("404")) { exchange.sendResponseHeaders(404, -1); return; }
                if (behavior.equals("hash")) { bytes = bytes.clone(); bytes[0] ^= 1; }
                if (behavior.equals("short")) bytes = Arrays.copyOf(bytes, bytes.length - 1);
                if (behavior.equals("large")) bytes = Arrays.copyOf(bytes, bytes.length + 1);
                exchange.sendResponseHeaders(200, bytes.length);
                bodyStarted.release();
                if (behavior.equals("slow")) {
                    try { gate.tryAcquire(10, TimeUnit.SECONDS); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
                }
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // Timed-out/cancelled clients close their sockets.
            }
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        var workers = Executors.newFixedThreadPool(4);
        try {
            var library = library(data, base, 3000);
            library.prepare(ignored -> {});
            check(requests.get() == 3, "first preparation downloads each library once");
            for (int i = 0; i < NAMES.size(); i++) {
                check(Arrays.equals(fixtures.get(NAMES.get(i)), library.read(NAMES.get(i))),
                    "downloaded library bytes match the published sources");
            }
            var time = Files.getLastModifiedTime(library.paths().getFirst());
            library.prepare(stage -> { throw new AssertionError("valid cache must not download"); });
            check(requests.get() == 3 && time.equals(Files.getLastModifiedTime(library.paths().getFirst())),
                "warm startup does not access the server or rewrite cached resources");
            library(data, URI.create("http://127.0.0.1:1/"), 500).prepare(ignored -> {});
            check(requests.get() == 3, "manually installed resources work with the network unavailable");

            Path damaged = library.paths().getFirst();
            byte[] wrong = fixtures.get(NAMES.getFirst()).clone();
            wrong[wrong.length - 1] ^= 1;
            Files.write(damaged, wrong);
            mode.set("404");
            failure(() -> library.prepare(ignored -> {}), "failed repair reports the offline install path");
            check(Arrays.equals(wrong, Files.readAllBytes(damaged)), "failed repair never publishes partial replacement");
            noTemporary(library);
            mode.set("valid");
            library.prepare(ignored -> {});
            check(Arrays.equals(fixtures.get(NAMES.getFirst()), Files.readAllBytes(damaged)),
                "same-size corrupted cache is detected by SHA and repaired");
            Files.delete(damaged);
            library.prepare(ignored -> {});
            check(Files.exists(damaged), "missing library is downloaded again");

            for (String behavior : List.of("hash", "short", "large", "404")) {
                mode.set(behavior);
                var invalid = library(data.resolve(behavior), base, 3000);
                failure(() -> invalid.prepare(ignored -> {}), behavior + " download is rejected");
                check(invalid.paths().stream().noneMatch(Files::exists), behavior + " bytes never become active cache");
                noTemporary(invalid);
            }
            mode.set("slow");
            bodyStarted.drainPermits();
            var timed = library(data.resolve("timeout"), base, 300);
            long start = System.nanoTime();
            failure(() -> timed.prepare(ignored -> {}), "total body download has a deadline");
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000, "timeout is bounded");
            noTemporary(timed);

            bodyStarted.drainPermits();
            var cancelled = library(data.resolve("cancel"), base, 5000);
            var cancelledDone = new CountDownLatch(1);
            var task = workers.submit(() -> {
                try { cancelled.prepare(ignored -> {}); throw new AssertionError("cancelled download completed"); }
                catch (InterruptedIOException expected) { return null; }
                finally { cancelledDone.countDown(); }
            });
            check(bodyStarted.tryAcquire(3, TimeUnit.SECONDS), "cancellation exercises an active HTTP body transfer");
            task.cancel(true);
            check(cancelledDone.await(3, TimeUnit.SECONDS), "interruption stops the actual download worker");
            noTemporary(cancelled);
            check(cancelled.paths().stream().noneMatch(Files::exists), "cancelled bytes are not published");
            gate.release(10);
            mode.set("valid");

            int before = requests.get();
            Path shared = Files.createDirectories(data.resolve("shared"));
            Path alias = data.resolve("shared-alias");
            Files.createSymbolicLink(alias, shared);
            var prepared = new ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++) {
                Path target = i % 2 == 0 ? shared : alias;
                prepared.add(workers.submit(() -> {
                    library(target, base, 3000).prepare(ignored -> {});
                    return null;
                }));
            }
            for (var preparedTask : prepared) preparedTask.get(10, TimeUnit.SECONDS);
            check(requests.get() - before == 3, "concurrent aliased data directories share one verified download");
            library(shared, base, 3000).verify();
            checks++;

            Path processes = Files.createDirectories(data.resolve("processes"));
            before = requests.get();
            var children = new ArrayList<Process>();
            for (int i = 0; i < 2; i++) {
                Path log = processes.resolve("child-" + i + ".log");
                children.add(new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-cp", System.getProperty("java.class.path"), AssetLibraryTests.class.getName(),
                    "prepare", processes.toString(), base.toString()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start());
            }
            try {
                for (Process child : children)
                    check(child.waitFor(15, TimeUnit.SECONDS) && child.exitValue() == 0,
                        "independent JVM shares the cache successfully: " + processes);
            } finally {
                for (Process child : children) if (child.isAlive()) child.destroyForcibly();
            }
            check(requests.get() - before == 3, "cross-process locking prevents duplicate/partial downloads");
            noTemporary(library(processes, base, 3000));
        } finally {
            gate.release(20);
            workers.shutdownNow();
            server.stop(0);
            serverWorkers.shutdownNow();
        }
        return checks;
    }

    private static AssetLibrary library(Path data, URI base, long timeout) throws IOException {
        return new AssetLibrary(data, base, false, Duration.ofMillis(timeout));
    }
    private static void noTemporary(AssetLibrary library) throws IOException {
        Path directory = library.paths().getFirst().getParent();
        try (var files = Files.list(directory)) {
            check(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                "failed, cancelled or completed transfers leave no partial files");
        }
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private static void failure(Operation operation, String message) throws Exception {
        try { operation.run(); throw new AssertionError(message); }
        catch (IOException expected) {
            check(expected.getMessage().contains("/dialogmenu reload all")
                && expected.getMessage().contains("libraries"), message);
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
