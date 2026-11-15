package dev.dialogmenu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import java.util.function.Function;
import static dev.dialogmenu.MenuModel.*;

/** 复用正式显示路径的快照级缓存；比较旧版本时只替换此适配器。 */
final class BenchmarkRendering {
    private final PreparedLayouts prepared;
    BenchmarkRendering(FontPack fonts) { prepared = new PreparedLayouts(fonts); }
    Rendered frame(MenuRenderer renderer, Menu menu, Context context, int width, int pixels) throws Exception {
        return prepared.frame(renderer, menu, context, width, pixels);
    }
    Component encode(SpriteCanvas canvas, Menu menu, Rendered frame, Context context,
                     FontPack fonts, ActionTokens.Page page) {
        return encode(canvas, menu, frame, context, fonts, region -> page.bind(() -> {}));
    }
    Component encode(SpriteCanvas canvas, Menu menu, Rendered frame, Context context,
                     FontPack fonts, Function<Region, ClickEvent> bind) {
        return canvas.encode(menu, frame, context, fonts, bind, prepared).component();
    }
}
