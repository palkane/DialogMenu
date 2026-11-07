package dev.dialogmenu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 同一 RGBA 协议，按资源格式选择原版着色器接口。 */
final class RgbaShaders {
    record Variant(String directory, int min, int max, List<Integer> minimum, List<Integer> maximum) {}
    static final List<Variant> OVERLAYS = List.of(
        new Variant("dialogmenu_330", 69, 83, List.of(69, 0), List.of(83, Integer.MAX_VALUE)),
        new Variant("dialogmenu_lightmap", 84, 87, List.of(84, 0), List.of(87, Integer.MAX_VALUE)),
        new Variant("dialogmenu_26_2", 88, 96, List.of(88, 0), List.of(96, Integer.MAX_VALUE)),
        new Variant("dialogmenu_26_3", 97, 97, List.of(97, 0), List.of(97, 1)));

    static Map<String, Object> metadata() {
        return Map.of("entries", OVERLAYS.stream().map(v -> Map.of(
            "directory", v.directory(),
            "min_format", v.minimum(), "max_format", v.maximum())).toList());
    }

    static void write(Path pack) throws IOException {
        remove(pack);
        for (String name : List.of("vertex.glsl", "fragment.glsl"))
            write(pack.resolve("assets/dialogmenu/shaders/include/rgba_" + name), read(name));
        pair(pack, "legacy/", 150, false);
        pair(pack.resolve("dialogmenu_330"), "legacy/", 330, false);
        pair(pack.resolve("dialogmenu_lightmap"), "legacy/", 330, true);
        pair(pack.resolve("dialogmenu_26_2"), "26_2/", 330, false);
        pair(pack.resolve("dialogmenu_26_3"), "", 330, false);
    }

    static void remove(Path pack) throws IOException {
        List<Path> roots = new ArrayList<>();
        roots.add(pack);
        for (var variant : OVERLAYS) roots.add(pack.resolve(variant.directory()));
        for (Path root : roots) for (String name : List.of("text", "rendertype_text"))
            for (String extension : List.of(".vsh", ".fsh"))
                Files.deleteIfExists(root.resolve("assets/minecraft/shaders/core/" + name + extension));
    }

    private static void pair(Path root, String source, int version, boolean lightmap) throws IOException {
        for (String extension : List.of(".vsh", ".fsh")) {
            String content = read(source + "text" + extension).replace("#version 150", "#version " + version);
            if (lightmap && extension.equals(".vsh"))
                content = content.replace("#moj_import <minecraft:projection.glsl>",
                    "#moj_import <minecraft:projection.glsl>\n#moj_import <minecraft:sample_lightmap.glsl>")
                    .replace("texelFetch(Sampler2, UV2 / 16, 0)", "sample_lightmap(Sampler2, UV2)");
            write(root.resolve("assets/minecraft/shaders/core/"
                + (source.equals("legacy/") ? "rendertype_text" : "text") + extension), content);
        }
    }

    private static String read(String name) throws IOException {
        try (var input = RgbaShaders.class.getResourceAsStream("/rgba/" + name)) {
            if (input == null) throw new IOException("Missing RGBA shader: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
