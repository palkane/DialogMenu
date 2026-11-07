package dev.dialogmenu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** 仅 GUI 使用的文字编码：RGB 为识别标记、10 位 Y 和 8 位色表索引，A 保持不透明。 */
final class RgbaOffsets {
    static final int MAGIC = 0xD40000;
    static final int TEXTURE_RGB = 0x174783;
    private final Map<Integer, Integer> palette = new LinkedHashMap<>();

    RgbaOffsets() { NamedTextColor.NAMES.values().forEach(color -> add(color.value())); }

    static String commonCharacters() throws IOException {
        try (var input = RgbaOffsets.class.getResourceAsStream("/rgba/characters-3500.txt")) {
            if (input == null) throw new IOException("Missing shader character set");
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        }
    }

    void prepare(Component text, int fallback) {
        add(fallback);
        for (var glyph : MenuText.glyphs(text)) {
            validateStyle(glyph);
            if (glyph.style().color() != null) add(glyph.style().color().value());
        }
    }

    private static void validateStyle(MenuText.Glyph glyph) {
        for (var decoration : List.of(TextDecoration.ITALIC, TextDecoration.UNDERLINED,
            TextDecoration.STRIKETHROUGH, TextDecoration.OBFUSCATED))
            if (glyph.style().hasDecoration(decoration))
                throw new IllegalArgumentException("此菜单不支持该文字样式，请移除斜体、下划线、删除线或混淆格式。");
    }

    int add(int color) {
        int rgb = color & 0xFFFFFF;
        if (!palette.containsKey(rgb)) {
            if (palette.size() == 256) throw new IllegalArgumentException("Shader文字和图片共享颜色超过256种");
            palette.put(rgb, palette.size());
        }
        return palette.get(rgb);
    }

    int index(int color) {
        Integer index = palette.get(color & 0xFFFFFF);
        if (index == null) throw new IllegalArgumentException("图片颜色未准备，请执行 /dialogmenu reload all");
        return index;
    }

    Component encode(Component text, int fallback, int top) {
        if (top < 0 || top > 1023) throw new IllegalArgumentException("RGBA Y exceeds 0..1023");
        var result = Component.text();
        for (var glyph : MenuText.glyphs(text)) {
            validateStyle(glyph);
            int rgb = glyph.style().color() == null ? fallback & 0xFFFFFF : glyph.style().color().value();
            Integer index = palette.get(rgb);
            if (index == null) throw new IllegalArgumentException("文字颜色未准备，请在text-colors声明#"
                + String.format(java.util.Locale.ROOT, "%06X", rgb) + "后执行 /dialogmenu reload all");
            result.append(glyph.component().color(TextColor.color(MAGIC | top << 8 | index)));
        }
        return result.build().compact();
    }

    void write(Path pack) throws IOException {
        Path include = pack.resolve("assets/dialogmenu/shaders/include/rgba_palette.glsl");
        Files.createDirectories(include.getParent());
        StringBuilder glsl = new StringBuilder("vec3 dialogmenu_color(int index) {\n    switch (index) {\n");
        for (var entry : palette.entrySet()) {
            int rgb = entry.getKey();
            glsl.append("        case ").append(entry.getValue()).append(": return vec3(")
                .append(rgb >> 16 & 255).append(".0, ").append(rgb >> 8 & 255).append(".0, ")
                .append(rgb & 255).append(".0) / 255.0;\n");
        }
        glsl.append("    }\n    return vec3(1.0, 0.0, 1.0);\n}\n");
        Files.writeString(include, glsl);
        RgbaShaders.write(pack);
    }
}
