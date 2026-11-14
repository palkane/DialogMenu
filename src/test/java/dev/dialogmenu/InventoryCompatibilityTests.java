package dev.dialogmenu;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** 核对旧客户端的缺失图标补全，同时保留现有客户端材质和世界图集。 */
final class InventoryCompatibilityTests {
    static int run(Path root) throws Exception {
        Path pack = Files.createTempDirectory(root, "inventory-26-2-");
        var images = new RegisteredImages();
        images.prepare(InventoryAssets.images(), List.of(0), 0);
        images.write(pack);
        JsonArray base = JsonParser.parseString(Files.readString(pack.resolve("assets/minecraft/atlases/items.json")))
            .getAsJsonObject().getAsJsonArray("sources");
        JsonArray legacy = JsonParser.parseString(Files.readString(pack.resolve(
            "dialogmenu_26_2/assets/minecraft/atlases/items.json"))).getAsJsonObject().getAsJsonArray("sources");
        if (base.size() != legacy.size()) throw new AssertionError("26.2 图集必须保留所有原有 sprite");
        for (String texture : List.of("rose_bush_top", "rail", "glow_lichen")) {
            String resource = "minecraft:block/" + texture;
            String isolated = RegisteredImages.nativeSpriteId(resource);
            boolean found = base.asList().stream().map(JsonElement::getAsJsonObject).anyMatch(row ->
                row.get("resource").getAsString().equals(resource)
                    && row.get("sprite").getAsString().equals(isolated));
            if (!found) throw new AssertionError("原版方块图标须保留贴图并隔离精灵ID：" + resource);
        }
        if (base.asList().stream().map(JsonElement::getAsJsonObject).anyMatch(row ->
            row.get("sprite").getAsString().startsWith("minecraft:block/")))
            throw new AssertionError("菜单物品图集不能占用原版方块精灵");
        String component = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(
            images.component(ImageRegistry.PREFIX + "minecraft:item/rose_bush", 0, 16, 0, 0));
        if (!component.contains(RegisteredImages.nativeSpriteId("minecraft:block/rose_bush_top")))
            throw new AssertionError("实际菜单组件必须引用隔离后的精灵ID");
        int patched = 0;
        for (int i = 0; i < base.size(); i++) {
            var original = base.get(i).getAsJsonObject();
            var compatible = legacy.get(i).getAsJsonObject();
            if (!original.get("sprite").equals(compatible.get("sprite")))
                throw new AssertionError("补全不能改变运行时 sprite ID");
            String resource = compatible.get("resource").getAsString();
            if (resource.equals(original.get("resource").getAsString())) continue;
            if (!resource.startsWith("dialogmenu:inventory_26_2/"))
                throw new AssertionError("补全纹理必须使用插件命名空间");
            Path png = pack.resolve("assets/dialogmenu/textures/" + resource.substring(11) + ".png");
            var actual = ImageIO.read(png.toFile());
            String sprite = original.get("sprite").getAsString();
            String alias = InventoryAssets.nativeSprites().entrySet().stream()
                .filter(entry -> RegisteredImages.nativeSpriteId(entry.getValue()).equals(sprite)).findFirst().orElseThrow().getKey();
            var expected = InventoryAssets.images().get(alias).image();
            if (actual.getWidth() != expected.getWidth() || actual.getHeight() != expected.getHeight()
                || !Arrays.equals(actual.getRGB(0, 0, actual.getWidth(), actual.getHeight(), null, 0, actual.getWidth()),
                    expected.getRGB(0, 0, expected.getWidth(), expected.getHeight(), null, 0, expected.getWidth())))
                throw new AssertionError("缺失图标的像素不一致：" + sprite);
            patched++;
        }
        if (patched != 24) throw new AssertionError("应补全 24 个 26.2 缺失图标，实际 " + patched);
        for (String atlas : List.of("blocks", "banner_patterns", "beds", "chests"))
            if (Files.exists(pack.resolve("assets/minecraft/atlases/" + atlas + ".json")))
                throw new AssertionError("二维图标补全不能写入世界图集");
        images.write(pack);
        if (!legacy.equals(JsonParser.parseString(Files.readString(pack.resolve(
            "dialogmenu_26_2/assets/minecraft/atlases/items.json"))).getAsJsonObject().getAsJsonArray("sources")))
            throw new AssertionError("重复导出应得到相同图集");
        System.out.println("PASS 26.2 图标兼容：24 个内置回退，已有原版图标与世界图集保持原样（离线）");
        return 4 + base.size() + patched;
    }
}
