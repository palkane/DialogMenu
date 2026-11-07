package dev.dialogmenu;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import static dev.dialogmenu.MenuModel.*;

/** 每个图形独立生成精灵，合成图仅用于离线预览。 */
final class MenuRenderer {
    final VanillaAssets assets;
    private record Graphic(String id, String type, double x, double y, double sx, double sy, double width,
                           double height, int argb, String source, double thickness, double rotation,
                           boolean reuse, TextureRegion atlas, boolean interactive, int canvasWidth, int canvasHeight,
                           int logicalHeight, double zoom) {}
    private final Map<Graphic, Sprite> runtimeSprites = new LinkedHashMap<>(16, .75f, true);
    private long cachedPixels;
    MenuRenderer(VanillaAssets assets) { this.assets = assets; }

    Rendered draw(Menu menu, Context context, int width, int maxPixels) throws IOException {
        return draw(menu, context, width, maxPixels, true);
    }
    Rendered drawRuntime(Menu menu, Context context, int width, int maxPixels) throws IOException {
        return draw(menu, context, width, maxPixels, false);
    }
    private Rendered draw(Menu menu, Context context, int width, int maxPixels, boolean preview) throws IOException {
        width = (int) Math.round(width * context.canvasScale());
        int height = Math.max(1, (int) Math.round(menu.height() * width / (double) menu.width()));
        if (width < 64 || width > 512 || height > 1024 || (long) width * height > maxPixels)
            throw new IllegalArgumentException("当前画布比例/尺寸超出正文范围，请减小尺寸；不自动重排布局");
        BufferedImage image = preview ? new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB) : null;
        Graphics2D root = preview ? image.createGraphics() : null;
        double zoom = width / (double) menu.width();
        List<Sprite> sprites = new ArrayList<>();
        List<Node> textNodes = new ArrayList<>();
        for (Node n : menu.nodes()) {
            if (n.type().equals("text")) {
                if (n.rotation() != 0) throw new IllegalArgumentException(n.id() + " 原生文字不支持旋转");
                textNodes.add(n);
                continue;
            }
            Graphic key = new Graphic(n.id(), n.type(), n.x(), n.y(), n.scaleX(), n.scaleY(), n.width(),
                n.height(), n.argb(), n.source(), n.thickness(), n.rotation(), n.reuseTexture(), n.atlas(),
                n.interaction() != null, width, height, menu.height(), zoom);
            if (!preview) {
                Sprite cached = runtimeSprites.get(key);
                if (cached != null) { sprites.add(cached); continue; }
            }
            double h = n.type().equals("line") ? n.thickness() : n.height();
            AffineTransform transform = new AffineTransform();
            transform.scale(zoom, zoom);
            transform.translate(menu.width() / 2.0 + n.x(), menu.height() / 2.0 - n.y());
            transform.rotate(-Math.toRadians(n.rotation()));
            transform.scale(n.scaleX(), n.scaleY());
            Rectangle2D box = transform.createTransformedShape(
                new Rectangle2D.Double(-n.width() / 2, -h / 2, n.width(), h)).getBounds2D();
            int left = (int) Math.floor(box.getMinX() + 1e-7), top = (int) Math.floor(box.getMinY() + 1e-7);
            Rectangle bounds = new Rectangle(left, top,
                (int) Math.ceil(box.getMaxX() - 1e-7) - left, (int) Math.ceil(box.getMaxY() - 1e-7) - top);
            BufferedImage shaderSource = null;
            if (n.type().equals("image") && n.rotation() == 0 && n.argb() >>> 24 == 255
                && !n.source().startsWith("minecraft:") && !n.reuseTexture() && n.atlas() == null
                && !assets.craftEngine.atlasReference(n.source())) {
                BufferedImage source = assets.image(n.source());
                int sw = Math.max(1, (int) Math.round(n.width() * n.scaleX() * zoom));
                int sh = Math.max(1, (int) Math.round(n.height() * n.scaleY() * zoom));
                if (source.getWidth() <= 254 && source.getHeight() <= 254 && sw <= 512 && sh <= 512) {
                    bounds = new Rectangle((int) Math.round((menu.width() / 2.0 + n.x()) * zoom - sw / 2.0),
                        (int) Math.round((menu.height() / 2.0 - n.y()) * zoom - sh / 2.0), sw, sh);
                    shaderSource = source;
                }
            }
            Rectangle unclipped = new Rectangle(bounds);
            bounds = bounds.intersection(new Rectangle(0, 0, width, height));
            if (bounds.isEmpty()) continue;
            if (shaderSource != null && bounds.equals(unclipped)) {
                Sprite reference = new Sprite(n.id(), bounds.x, bounds.y, null, null, shaderSource, null,
                    bounds.width, bounds.height);
                if (!preview && (n.interaction() == null || RgbaImages.exactHits(reference))) {
                    add(sprites, key, reference, false);
                    continue;
                }
                BufferedImage layer = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = layer.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(tint(shaderSource, n.argb()), 0, 0, bounds.width, bounds.height, null);
                g.dispose();
                add(sprites, key, new Sprite(n.id(), bounds.x, bounds.y, layer, null, shaderSource), preview);
                if (root != null) root.drawImage(layer, bounds.x, bounds.y, null);
                continue;
            }
            if (n.source().startsWith("minecraft:") || n.reuseTexture() || n.atlas() != null
                || assets.craftEngine.atlasReference(n.source())) {
                var reference = assets.reference(n.source(), n.atlas());
                boolean vanillaBitmap = n.source().startsWith("minecraft:");
                if (!vanillaBitmap && (n.argb() & 0xFFFFFF) != 0xFFFFFF)
                    throw new IOException(n.id() + " 外部纹理直引暂不支持染色；请使用自有PNG或color='#FFFFFF'");
                if (n.rotation() != 0 || n.argb() >>> 24 != 255 || !bounds.equals(unclipped)
                    || Math.abs(n.width() * n.scaleX() / (n.height() * n.scaleY())
                        - reference.image().getWidth() / (double) reference.image().getHeight()) > 1e-7)
                    throw new IOException(n.id() + " 纹理直引须完整显示、等比缩放、不旋转且opacity=255");
                // 只导出字体 provider，原纹理由 Minecraft 或 CE 提供。
                if (root != null) root.drawImage(vanillaBitmap ? tint(reference.image(), n.argb()) : reference.image(),
                    bounds.x, bounds.y, bounds.width, bounds.height, null);
                // 保留显示边界，不复制源 PNG。
                add(sprites, key, new Sprite(n.id(), bounds.x, bounds.y,
                    preview ? new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB) : null,
                    reference.texture(), reference.image(), reference.region(), bounds.width, bounds.height), preview);
                continue;
            }
            BufferedImage layer = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = layer.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.translate(-bounds.x, -bounds.y);
            g.transform(transform);
            g.setColor(new Color(n.argb(), true));
            double x = -n.width() / 2, y = -h / 2;
            switch (n.type()) {
                case "rectangle", "line" -> g.fill(new Rectangle2D.Double(x, y, n.width(), h));
                case "frame" -> {
                    double t = n.thickness();
                    g.fill(new Rectangle2D.Double(x, y, n.width(), t));
                    g.fill(new Rectangle2D.Double(x, y + h - t, n.width(), t));
                    g.fill(new Rectangle2D.Double(x, y, t, h));
                    g.fill(new Rectangle2D.Double(x + n.width() - t, y, t, h));
                }
                case "image" -> {
                    BufferedImage texture = tint(assets.image(n.source()), n.argb());
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (n.argb() >>> 24) / 255f));
                    g.drawImage(texture, (int) Math.round(x), (int) Math.round(y),
                        (int) Math.round(n.width()), (int) Math.round(h), null);
                }
                default -> throw new IllegalArgumentException("元素尚不支持：" + n.type());
            }
            g.dispose();
            add(sprites, key, new Sprite(n.id(), bounds.x, bounds.y, layer), preview);
            if (root != null) root.drawImage(layer, bounds.x, bounds.y, null);
        }
        if (root != null) root.dispose();
        return new Rendered(image, List.copyOf(sprites), List.copyOf(textNodes), width, height);
    }
    private void add(List<Sprite> sprites, Graphic key, Sprite sprite, boolean preview) {
        sprites.add(sprite);
        if (preview) return;
        runtimeSprites.put(key, sprite);
        cachedPixels += pixels(sprite);
        var iterator = runtimeSprites.entrySet().iterator();
        while (runtimeSprites.size() > 512 || cachedPixels > 1_048_576) {
            cachedPixels -= pixels(iterator.next().getValue());
            iterator.remove();
        }
    }
    private static long pixels(Sprite sprite) {
        return sprite.image() == null ? 0 : (long) sprite.width() * sprite.height();
    }
    private static BufferedImage tint(BufferedImage source, int argb) {
        if ((argb & 0xFFFFFF) == 0xFFFFFF) return source;
        BufferedImage image = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int red = argb >> 16 & 255, green = argb >> 8 & 255, blue = argb & 255;
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            int pixel = source.getRGB(x, y);
            image.setRGB(x, y, pixel & 0xFF000000
                | ((pixel >> 16 & 255) * red / 255) << 16
                | ((pixel >> 8 & 255) * green / 255) << 8 | (pixel & 255) * blue / 255);
        }
        return image;
    }
}
