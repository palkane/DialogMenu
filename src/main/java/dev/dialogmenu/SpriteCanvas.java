package dev.dialogmenu;

import java.util.*;
import java.util.function.Function;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.*;
import net.kyori.adventure.text.format.*;
import static dev.dialogmenu.MenuModel.*;

/** 每条正文行内按原绘制顺序输出局部字形。 */
final class SpriteCanvas {
    record Output(Component component, int bodyWidth, int rows, int sprites) {}
    private record TextKey(FontPack fonts, String text, double wrapWidth, int maxLines, int size,
                           double stretchX, double stretchY) {}
    private record TextLayout(List<Component> lines, List<Integer> widths) {}
    private record LabelKey(FontPack fonts, String node, Component text, TextStyle style, int top, int row, int argb) {}
    private record InkKey(FontPack fonts, String font, int code, int color) {}
    private final Map<TextKey, TextLayout> staticText = new LinkedHashMap<>(16, .75f, true);
    private final Map<LabelKey, Component> staticLabels = new LinkedHashMap<>(16, .75f, true);
    private final Map<InkKey, Component> staticInks = new LinkedHashMap<>(16, .75f, true);
    static final Key SPACE = Key.key("dialogmenu", "space");
    static String move(int pixels) {
        if (pixels < -1024 || pixels > 1024) throw new IllegalArgumentException("Canvas movement out of range");
        return Character.toString(0xF0000 + pixels + 1024);
    }
    private static Component space(int pixels) { return Component.text(move(pixels)).font(SPACE); }
    private static Component positioned(Component ink, int x, int advance) {
        return Component.empty().append(space(x)).append(ink).append(space(-x - advance));
    }
    static int textTop(Menu menu, Node node, int width, int line) {
        return textTop(menu, node, width, line, node.size());
    }
    static int textSize(Menu menu, Node node, int width, double scale) {
        return textSize(menu, node, width, scale, 1);
    }
    static int textSize(Menu menu, Node node, int width, double scale, double canvasScale) {
        int size = (int) Math.round(node.size() * scale
            * (menu.scaleTextWithCanvas() ? width / (double) menu.width() : canvasScale));
        if (size < 1 || size > 128) throw new IllegalArgumentException(node.id() + " 实际字号须为1..128");
        return size;
    }
    static int textTop(Menu menu, Node node, int width, int line, int size) {
        return textTop(menu, node, width, line, size, 1);
    }
    static int textTop(Menu menu, Node node, int width, int line, int size, double stretchY) {
        return textTop(menu, node, width, line, new TextStyle(size, 1, stretchY));
    }
    static int textTop(Menu menu, Node node, int width, int line, TextStyle style) {
        return (int) Math.round((menu.height() / 2.0 - node.y()) * width / menu.width()
            - style.height() / 2.0 + line * style.stride());
    }
    Output encode(Menu menu, Rendered frame, Context context, FontPack fonts, Function<Region, ClickEvent> bind) {
        int width = frame.width(), height = frame.height(), rows = (height + 8) / 9;
        Map<String, Integer> nativeRows = RgbaRows.plan(menu, frame);
        List<List<Component>> parts = new ArrayList<>();
        for (int row = 0; row < rows; row++) parts.add(new ArrayList<>());
        Map<Region, ClickEvent> clicks = new HashMap<>();
        Map<String, Sprite> sprites = new HashMap<>();
        for (Sprite sprite : frame.sprites()) sprites.put(sprite.id(), sprite);
        // 文字和图形共用绘制顺序，LayoutStore 已按 Z 排序。
        for (Node node : menu.nodes()) {
            if (node.type().equals("text")) {
                addText(parts, menu, node, context, fonts, clicks, bind, width, height, nativeRows.get(node.id()));
            } else {
                Sprite sprite = sprites.get(node.id());
                if (sprite == null) continue;
                Region region = node.interaction() == null ? null : node.interaction().region(node);
                addSprite(parts, sprite, fonts, node.argb(), nativeRows.get(node.id()),
                    region != null,
                    region == null || !region.hasActions() ? null : clicks.computeIfAbsent(region, bind),
                    region == null ? null : hover(region, context));
            }
        }
        Component body = Component.empty();
        for (int row = 0; row < rows; row++) {
            for (Component part : parts.get(row)) body = body.append(part);
            body = body.append(space(width + 2));
            if (row + 1 < rows) body = body.append(Component.newline());
        }
        return new Output(body.decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false)
            .shadowColor(ShadowColor.none()), width + 10, rows, frame.sprites().size());
    }
    private void addText(List<List<Component>> parts, Menu menu, Node node, Context context,
                                FontPack fonts, Map<Region, ClickEvent> clicks,
                                Function<Region, ClickEvent> bind, int width, int height, int nativeRow) {
        int size = textSize(menu, node, width, context.textScale(), context.canvasScale());
        TextStyle style = new TextStyle(size, node.scaleX(), node.scaleY());
        double zoom = width / (double) menu.width();
        String expanded = context.expand(node.text());
        boolean fixed = expanded.equals(node.text()) && !MenuTemplate.dynamicText(node);
        TextKey key = new TextKey(fonts, expanded, node.width() * node.scaleX() * zoom, node.maxLines(),
            size, node.scaleX(), node.scaleY());
        TextLayout layout = fixed ? staticText.get(key) : null;
        if (layout == null) {
            List<Component> lines = TextFlow.lines(node, MenuText.parse(expanded), zoom,
                glyph -> fonts.glyphWidth(glyph, style));
            layout = new TextLayout(lines,
                lines.stream().map(text -> fonts.textWidth(text, style)).toList());
            if (fixed) {
                staticText.put(key, layout);
                if (staticText.size() > 256) staticText.remove(staticText.keySet().iterator().next());
            }
        }
        List<Component> lines = layout.lines();
        Region region = node.interaction() == null ? null : node.interaction().region(node);
        if (region != null && region.hasActions()) clicks.computeIfAbsent(region, bind);
        for (int line = 0; line < lines.size(); line++) {
            Component text = lines.get(line);
            int advance = layout.widths().get(line);
            double anchor = (menu.width() / 2.0 + node.x()) * zoom;
            int x = (int) Math.round(anchor - switch (node.alignment()) {
                case "left" -> 0;
                case "right" -> advance;
                default -> advance / 2.0;
            });
            int top = textTop(menu, node, width, line, style);
            if (top < 0 || top + style.height() > height || x < 0 || x + advance > width)
                throw new IllegalArgumentException(node.id() + " 原生文字超出画布");
            Key font = fonts.textFont(style, text);
            int relativeTop = top - nativeRow * 9;
            boolean fixedLabel = fixed && region == null;
            LabelKey labelKey = new LabelKey(fonts, node.id(), text, style, relativeTop, nativeRow, node.argb());
            Component cachedLabel = fixedLabel ? staticLabels.get(labelKey) : null;
            if (cachedLabel == null) {
                cachedLabel = fonts.rgbaText(text, node.argb(), relativeTop).font(font);
                if (fixedLabel) cache(staticLabels, labelKey, cachedLabel, 2048);
            }
            parts.get(nativeRow).add(positioned(cachedLabel, x, advance));
            if (region != null)
                parts.get(nativeRow).add(positioned(fonts.rgbaHit(relativeTop, style, text)
                    .clickEvent(clicks.get(region)).hoverEvent(hover(region, context)), x, advance));
        }
    }
    private void addSprite(List<List<Component>> parts, Sprite sprite, FontPack fonts, int argb,
                                  int nativeRow, boolean interactive, ClickEvent click, HoverEvent<Component> hover) {
        sprite = RgbaRows.relative(sprite, nativeRow);
        if (fonts.shaderImage(sprite, interactive)) {
            RgbaImages images = fonts.rgbaImages();
            int tint = fonts.imageTint(argb);
            RgbaImages.Glyph glyph = images.glyph(sprite, tint);
            int color = RgbaImages.payload(sprite.width(), sprite.height(), sprite.y() % 9);
            Component ink = cachedInk(fonts, "images_rgba", glyph.code(), color,
                Key.key("dialogmenu", "images_rgba"));
            parts.get(nativeRow).add(positioned(ink, sprite.x(), glyph.advance()));
            if (click != null || hover != null)
                for (var tile : RgbaImages.tiles(sprite.width(), sprite.height()))
                    parts.get(nativeRow).add(positioned(images.hit(sprite.y() + tile.y(), tile.size(), click, hover),
                        sprite.x() + tile.x(), tile.size() + 1));
            return;
        }
        for (Sprite part : CanvasRows.parts(sprite)) {
            FontPack.Glyph glyph = fonts.sprite(part);
            int textColor = sprite.texture() != null && sprite.texture().startsWith("minecraft:")
                ? argb & 0xFFFFFF : 0xFFFFFF;
            Component ink = cachedInk(fonts, "sprites", glyph.code(), textColor,
                Key.key("dialogmenu", "sprites"));
            if (click != null || hover != null) ink = ink.clickEvent(click).hoverEvent(hover);
            parts.get(nativeRow).add(positioned(ink, part.x(), glyph.advance()));
        }
    }
    private Component cachedInk(FontPack fonts, String font, int code, int color, Key key) {
        InkKey inkKey = new InkKey(fonts, font, code, color);
        Component ink = staticInks.get(inkKey);
        if (ink == null) {
            ink = Component.text(Character.toString(code), TextColor.color(color)).font(key);
            cache(staticInks, inkKey, ink, 4096);
        }
        return ink;
    }
    private static <K, V> void cache(Map<K, V> values, K key, V value, int limit) {
        values.put(key, value);
        if (values.size() > limit) values.remove(values.keySet().iterator().next());
    }
    private static HoverEvent<Component> hover(Region region, Context context) {
        return region.tooltip().isEmpty() ? null
            : HoverEvent.showText(legacy(context.expand(String.join("\n", region.tooltip()))));
    }
    static Component legacy(String text) { return MenuText.parse(text); }
}
