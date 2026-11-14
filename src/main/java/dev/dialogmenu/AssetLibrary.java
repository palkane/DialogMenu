package dev.dialogmenu;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/** Fixed, verified source data; all preparation runs on the caller's background worker. */
final class AssetLibrary {
    record Asset(String name, long size, String sha256) {}
    private final Path directory;
    private final URI base;
    private final boolean embedded;
    private final Duration timeout;
    private final List<Asset> assets;
    private final ResourceVersion target;

    AssetLibrary(Path data) throws IOException {
        this(data, ResourceVersion.DEFAULT);
    }

    AssetLibrary(Path data, ResourceVersion target) throws IOException {
        this(data, target, null, true, Duration.ofSeconds(45));
    }

    AssetLibrary(Path data, URI base, boolean embedded, Duration timeout) throws IOException {
        this(data, ResourceVersion.DEFAULT, base, embedded, timeout);
    }

    AssetLibrary(Path data, ResourceVersion target, URI base, boolean embedded, Duration timeout) throws IOException {
        this.target = target;
        var properties = new Properties();
        try (var input = AssetLibrary.class.getResourceAsStream(target.manifest())) {
            if (input == null) throw new IOException("Missing vanilla asset manifest");
            properties.load(input);
        }
        String version = properties.getProperty("version");
        if (version == null || !version.matches("[a-z0-9][a-z0-9._-]*"))
            throw new IOException("Invalid vanilla asset version");
        directory = data.toAbsolutePath().normalize().resolve("libraries").resolve(version);
        this.base = base == null ? URI.create(properties.getProperty("base-url")) : base;
        this.embedded = embedded;
        this.timeout = timeout;
        var entries = new ArrayList<Asset>();
        for (String name : List.of("glyphs.bin.gz", "vanilla-icons.zip", "vanilla-inventory.zip")) {
            long size = Long.parseLong(properties.getProperty(name + ".size"));
            String hash = properties.getProperty(name + ".sha256");
            if (size <= 0 || hash == null || !hash.matches("[0-9a-f]{64}"))
                throw new IOException("Invalid vanilla asset manifest: " + name);
            entries.add(new Asset(name, size, hash));
        }
        assets = List.copyOf(entries);
    }

    List<Path> paths() { return assets.stream().map(asset -> directory.resolve(asset.name())).toList(); }
    ResourceVersion target() { return target; }

    void prepare(Consumer<String> progress) throws IOException {
        interrupted();
        if (valid()) return;
        Files.createDirectories(directory);
        try (var channel = FileChannel.open(directory.resolve(".download.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = lock(channel)) {
            for (Asset asset : assets) {
                interrupted();
                if (valid(asset)) continue;
                progress.accept("准备原版资源库：" + asset.name());
                Path temporary = Files.createTempFile(directory, ".download-", ".tmp");
                try {
                    // Present only in development/test classpaths, not the slim plugin JAR.
                    String bundledPath = target.equals(ResourceVersion.DEFAULT) ? "/" + asset.name()
                        : "/vanilla-assets/" + target.id() + "/" + asset.name();
                    try (var bundled = embedded ? AssetLibrary.class.getResourceAsStream(bundledPath) : null) {
                        if (bundled != null) {
                            Files.copy(bundled, temporary, StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            download(asset, temporary);
                        }
                    }
                    if (!valid(temporary, asset)) throw new IOException("大小或 SHA-256 不匹配");
                    interrupted();
                    Files.move(temporary, directory.resolve(asset.name()),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (InterruptedIOException error) {
                    throw error;
                } catch (IOException error) {
                    throw new IOException("原版资源库准备失败：" + asset.name() + "；" + error.getMessage()
                        + "。可手动从 " + base.resolve(asset.name()) + " 下载到 "
                        + directory + "，再执行 /dialogmenu reload all", error);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
        }
    }

    private FileLock lock(FileChannel channel) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos() * assets.size();
        while (true) {
            interrupted();
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException ignored) {
                // Another plugin instance in this JVM may share the same data directory.
            }
            if (System.nanoTime() >= deadline) throw new IOException("等待共享原版资源库下载超时");
            try { Thread.sleep(50); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("原版资源库准备已取消");
            }
        }
    }

    byte[] read(String name) throws IOException {
        Asset asset = assets.stream().filter(entry -> entry.name().equals(name)).findFirst()
            .orElseThrow(() -> new IOException("Unknown vanilla asset: " + name));
        interrupted();
        Path path = directory.resolve(name);
        if (Files.size(path) != asset.size()) throw new IOException("原版资源库大小不匹配：" + path);
        byte[] bytes = Files.readAllBytes(path);
        if (!asset.sha256().equals(SourceFiles.digest(bytes)))
            throw new IOException("原版资源库 SHA-256 不匹配：" + path);
        return bytes;
    }

    void verify() throws IOException {
        if (!valid()) throw new IOException("原版资源库在使用期间发生变化，请执行 /dialogmenu reload all");
    }

    private boolean valid() throws IOException {
        for (Asset asset : assets) if (!valid(asset)) return false;
        return true;
    }

    private boolean valid(Asset asset) throws IOException { return valid(directory.resolve(asset.name()), asset); }

    private static boolean valid(Path file, Asset asset) throws IOException {
        interrupted();
        if (!Files.isRegularFile(file) || Files.size(file) != asset.size()) return false;
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = new byte[16 * 1024];
            for (int size; (size = input.read(bytes)) != -1;) {
                interrupted();
                digest.update(bytes, 0, size);
            }
        }
        return asset.sha256().equals(HexFormat.of().formatHex(digest.digest()));
    }

    private void download(Asset asset, Path temporary) throws IOException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
        CompletableFuture<?> pending = null;
        try {
            var request = HttpRequest.newBuilder(base.resolve(asset.name())).timeout(timeout)
                .header("User-Agent", "DialogMenu-AssetLibrary").GET().build();
            pending = client.sendAsync(request, response -> {
                if (response.statusCode() != 200)
                    throw new UncheckedIOException(new IOException("HTTP " + response.statusCode()));
                return bounded(temporary, asset.size());
            });
            pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("原版资源库下载已取消");
        } catch (TimeoutException error) {
            throw new IOException("下载超时", error);
        } catch (ExecutionException error) {
            throw new IOException("下载失败：" + error.getCause().getMessage(), error.getCause());
        } finally {
            if (pending != null) pending.cancel(true);
            client.shutdownNow();
        }
    }

    static HttpResponse.BodySubscriber<Path> bounded(Path file, long limit) {
        // prepare owns creation. A late onSubscribe after cancellation must not recreate its deleted file.
        var target = HttpResponse.BodySubscribers.ofFile(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        return new HttpResponse.BodySubscriber<>() {
            private Flow.Subscription subscription;
            private long received;
            private boolean failed;
            public CompletionStage<Path> getBody() { return target.getBody(); }
            public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                target.onSubscribe(value);
            }
            public void onNext(List<ByteBuffer> buffers) {
                if (failed) return;
                for (var buffer : buffers) received += buffer.remaining();
                if (received > limit) {
                    failed = true;
                    subscription.cancel();
                    target.onError(new IOException("下载内容超过预期大小"));
                } else target.onNext(buffers);
            }
            public void onError(Throwable error) { if (!failed) target.onError(error); }
            public void onComplete() { if (!failed) target.onComplete(); }
        };
    }

    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("原版资源库准备已取消");
    }
}
