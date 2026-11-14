package dev.dialogmenu;

import java.io.*;
import java.util.*;
import net.kyori.adventure.nbt.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

/** 入口声明可离线校验；物品只在服务器主线程读取和创建。 */
final class MenuOpeners {
    record Item(String material, String craftEngine, String name, List<String> lore, String nbt, String action) {}
    record Rule(String id, String menu, String permission, List<String> commands, List<String> keys, Item item) {}

    static List<Rule> parse(ConfigurationSection yaml) throws IOException {
        if (!yaml.contains("openers")) return List.of();
        ConfigurationSection entries = yaml.getConfigurationSection("openers");
        if (entries == null) throw new IllegalArgumentException("openers须为入口映射");
        List<Rule> result = new ArrayList<>();
        Set<String> commands = new HashSet<>(), keys = new HashSet<>();
        for (String id : entries.getKeys(false)) {
            LayoutStore.id(id);
            ConfigurationSection entry = entries.getConfigurationSection(id);
            if (entry == null) throw new IllegalArgumentException("openers." + id + "须为入口对象");
            LayoutStore.keys(entry, Set.of("menu", "permission", "commands", "keys", "item"));
            String menu = LayoutStore.id(entry.getString("menu"));
            List<String> labels = new ArrayList<>(), combinations = new ArrayList<>();
            for (String label : LayoutStore.strings(entry.get("commands"))) {
                label = label.toLowerCase(Locale.ROOT).replaceFirst("^/", "");
                if (!label.matches("[a-z0-9_-]+") || label.equals("dialogmenu"))
                    throw new IllegalArgumentException("入口指令须为不含参数的命令名，不能占用dialogmenu");
                if (!commands.add(label)) throw new IllegalArgumentException("重复入口指令：" + label);
                labels.add(label);
            }
            for (String value : LayoutStore.strings(entry.get("keys"))) {
                String key = key(value);
                if (!keys.add(key)) throw new IllegalArgumentException("重复入口组合键：" + key);
                combinations.add(key);
            }
            Item item = null;
            if (entry.contains("item")) {
                var spec = entry.getConfigurationSection("item");
                if (spec == null) throw new IllegalArgumentException("入口item须为物品对象");
                LayoutStore.keys(spec, Set.of("material", "craftengine", "name", "lore", "nbt", "action"));
                String material = spec.getString("material", "");
                String ce = spec.getString("craftengine", "");
                if (material.isBlank() == ce.isBlank())
                    throw new IllegalArgumentException("item须指定material或craftengine其中之一");
                if (!material.isBlank()) {
                    Material type = Material.matchMaterial(material);
                    if (type == null || Set.of("AIR", "CAVE_AIR", "VOID_AIR").contains(type.name())
                        || type.name().startsWith("LEGACY_"))
                        throw new IllegalArgumentException("无效入口物品：" + material);
                    material = type.name();
                } else if (!ce.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+"))
                    throw new IllegalArgumentException("craftengine须为完整物品ID");
                String nbt = spec.getString("nbt", "");
                if (!nbt.isBlank()) TagStringIO.builder().build().asCompound(nbt);
                String action = key(spec.getString("action", "RIGHT_CLICK"));
                if (!action.endsWith("RIGHT_CLICK") && !action.endsWith("LEFT_CLICK"))
                    throw new IllegalArgumentException("物品action须为LEFT_CLICK/RIGHT_CLICK及其SHIFT/SPRINT组合");
                item = new Item(material, ce, spec.getString("name"),
                    spec.contains("lore") ? List.copyOf(LayoutStore.strings(spec.get("lore"))) : null, nbt, action);
            }
            if (labels.isEmpty() && combinations.isEmpty() && item == null)
                throw new IllegalArgumentException("入口至少需要commands、keys或item：" + id);
            result.add(new Rule(id, menu, entry.getString("permission", ""), List.copyOf(labels),
                List.copyOf(combinations), item));
        }
        return List.copyOf(result);
    }

    static void validate(List<Rule> rules, Set<String> menus) {
        for (Rule rule : rules) if (!menus.contains(rule.menu()))
            throw new IllegalArgumentException("入口" + rule.id() + "的菜单不存在：" + rule.menu());
    }

    static String key(String input) {
        String[] parts = input.toUpperCase(Locale.ROOT).replace(" ", "").split("\\+", -1);
        String action = parts[parts.length - 1];
        if (!Set.of("F", "Q", "LEFT_CLICK", "RIGHT_CLICK").contains(action))
            throw new IllegalArgumentException("可用动作：F、Q、LEFT_CLICK、RIGHT_CLICK；修饰键：SHIFT、SPRINT");
        Set<String> modifiers = new HashSet<>();
        for (int i = 0; i < parts.length - 1; i++)
            if (!Set.of("SHIFT", "SPRINT").contains(parts[i]) || !modifiers.add(parts[i]))
                throw new IllegalArgumentException("无效组合键：" + input);
        return (modifiers.contains("SPRINT") ? "SPRINT+" : "") + (modifiers.contains("SHIFT") ? "SHIFT+" : "") + action;
    }

    static String key(String action, boolean sneak, boolean sprint) {
        return (sprint ? "SPRINT+" : "") + (sneak ? "SHIFT+" : "") + action;
    }

    static boolean contains(CompoundBinaryTag actual, CompoundBinaryTag expected) {
        for (var entry : expected) {
            BinaryTag found = actual.get(entry.getKey());
            if (entry.getValue() instanceof CompoundBinaryTag compound) {
                if (!(found instanceof CompoundBinaryTag nested) || !contains(nested, compound)) return false;
            } else if (!entry.getValue().equals(found)) return false;
        }
        return true;
    }

    static CompoundBinaryTag read(byte[] bytes) throws IOException {
        return BinaryTagIO.reader().read(new ByteArrayInputStream(bytes), BinaryTagIO.Compression.GZIP);
    }

    static byte[] write(CompoundBinaryTag tag) throws IOException {
        var output = new ByteArrayOutputStream();
        BinaryTagIO.writer().write(tag, output, BinaryTagIO.Compression.GZIP);
        return output.toByteArray();
    }

    static CompoundBinaryTag customData(CompoundBinaryTag item) {
        return item.getCompound("components").getCompound("minecraft:custom_data");
    }

    static CompoundBinaryTag merge(CompoundBinaryTag original, CompoundBinaryTag changes) {
        var result = CompoundBinaryTag.builder().put(original);
        for (var entry : changes) {
            BinaryTag value = entry.getValue();
            if (value instanceof CompoundBinaryTag nested && original.get(entry.getKey()) instanceof CompoundBinaryTag old)
                value = merge(old, nested);
            result.put(entry.getKey(), value);
        }
        return result.build();
    }

    static boolean matches(Item spec, ItemStack item) throws Exception {
        if (item == null || item.getType().isAir()) return false;
        if (!spec.material().isBlank() && !item.getType().name().equals(spec.material())) return false;
        if (!spec.craftEngine().isBlank()
            && !spec.craftEngine().equals(String.valueOf(ceApi().getMethod("getCustomItemId", ItemStack.class).invoke(null, item))))
            return false;
        var meta = item.getItemMeta();
        if (spec.name() != null && !sameText(meta.displayName(), MenuText.parse(spec.name()))) return false;
        if (spec.lore() != null) {
            List<Component> lore = meta.lore();
            if (lore == null) lore = List.of();
            if (lore.size() != spec.lore().size()) return false;
            for (int i = 0; i < lore.size(); i++) if (!sameText(lore.get(i), MenuText.parse(spec.lore().get(i)))) return false;
        }
        return spec.nbt().isBlank() || contains(customData(read(item.serializeAsBytes())),
            TagStringIO.builder().build().asCompound(spec.nbt()));
    }

    static boolean sameText(Component actual, Component expected) {
        if (actual == null) return false;
        var legacy = LegacyComponentSerializer.legacySection();
        return legacy.serialize(actual).equals(legacy.serialize(expected));
    }

    private static Class<?> ceApi() throws ClassNotFoundException {
        var ce = Bukkit.getPluginManager().getPlugin("CraftEngine");
        if (ce == null || !ce.isEnabled()) throw new IllegalStateException("此入口物品需要CraftEngine");
        return Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineItems", true,
            ce.getClass().getClassLoader());
    }

    static ItemStack create(Item spec) throws Exception {
        ItemStack item;
        if (spec.craftEngine().isBlank()) item = new ItemStack(Material.valueOf(spec.material()));
        else {
            Object definition = ceApi().getMethod("byId", String.class).invoke(null, spec.craftEngine());
            if (definition == null) throw new IllegalArgumentException("CE物品不存在：" + spec.craftEngine());
            item = ((ItemStack) definition.getClass().getMethod("buildBukkitItem").invoke(definition)).clone();
        }
        if (!spec.nbt().isBlank()) {
            CompoundBinaryTag original = read(item.serializeAsBytes());
            CompoundBinaryTag merged = merge(customData(original), TagStringIO.builder().build().asCompound(spec.nbt()));
            CompoundBinaryTag components = original.getCompound("components").put("minecraft:custom_data", merged);
            item = ItemStack.deserializeBytes(write(original.put("components", components)));
        }
        if (spec.name() != null || spec.lore() != null) {
            var meta = item.getItemMeta();
            if (spec.name() != null) meta.displayName(Component.empty().decoration(TextDecoration.ITALIC, false)
                .append(MenuText.parse(spec.name())));
            if (spec.lore() != null) meta.lore(spec.lore().stream().map(MenuText::parse)
                .map(line -> Component.empty().decoration(TextDecoration.ITALIC, false).append(line)).toList());
            item.setItemMeta(meta);
        }
        item.setAmount(1);
        return item;
    }
}
