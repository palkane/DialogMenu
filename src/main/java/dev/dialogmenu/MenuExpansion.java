package dev.dialogmenu;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.bukkit.configuration.ConfigurationSection;

/** 单次呈现的按需变量快照，不跨帧或业务动作缓存。 */
final class MenuExpansion implements UnaryOperator<String> {
    private record Variable(int index, String name, String token, String type, String source,
                            String fallback, ConfigurationSection mappings, int limit) {}
    /** Declaration metadata only; never stores the result of evaluating a variable. */
    static final class Plan {
        static final Plan EMPTY = new Plan(null);
        private final Map<String, Variable> named;
        private final List<Variable> ordered;
        final Set<String> externalKeys;
        private final boolean braceInName;

        Plan(ConfigurationSection specs) {
            Map<String, Variable> names = new LinkedHashMap<>();
            Set<String> external = new HashSet<>();
            if (specs != null) for (String name : specs.getKeys(false)) {
                var spec = Objects.requireNonNull(specs.getConfigurationSection(name), "variable须为对象");
                String type = spec.getString("type", "");
                String source = spec.getString("source", type.equals("external") ? name : "");
                if (type.equals("external")) external.add(source);
                names.put(name, new Variable(names.size(), name, "{" + name + "}", type, source,
                    spec.getString("fallback", ""), spec.getConfigurationSection("values"), spec.getInt("max-length", 0)));
            }
            ordered = List.copyOf(names.values());
            named = Map.copyOf(names);
            externalKeys = Set.copyOf(external);
            braceInName = names.keySet().stream().anyMatch(n -> n.indexOf('{') >= 0 || n.indexOf('}') >= 0);
        }
    }
    private record Placeholder(String source, String fallback) {}
    private final Map<String, String> base;
    private final Map<String, String> external;
    private final Plan plan;
    private final Map<String, String> resolved = new HashMap<>();
    private final Map<Placeholder, String> placeholders = new HashMap<>();
    private final BiFunction<String, String, String> papi;
    private final Function<String, String> skript;

    MenuExpansion(Map<String, String> base, Plan plan, Map<String, String> immutableExternal,
                  BiFunction<String, String, String> papi, Function<String, String> skript) {
        this.base = new LinkedHashMap<>(base);
        this.external = immutableExternal;
        this.plan = plan;
        this.papi = papi;
        this.skript = skript;
    }

    @Override public String apply(String text) {
        String expanded = replace(text, plan.ordered.size());
        return expanded.contains("%") ? placeholder(expanded, expanded) : expanded;
    }

    private String replace(String text, int before) {
        if (text.indexOf('{') < 0) return text;
        for (String name : base.keySet()) {
            String token = "{" + name + "}";
            if (!text.contains(token)) continue;
            String value = value(name, before);
            if (value != null) text = text.replace(token, value);
        }
        // Old YAML permits braces in the variable name itself. Preserve literal-token matching for it.
        if (plan.braceInName) {
            for (int i = 0; i < before; i++) {
                Variable v = plan.ordered.get(i);
                if (!base.containsKey(v.name) && text.contains(v.token))
                    text = text.replace(v.token, value(v.name, before));
            }
            return text;
        }
        // Declaration order is already encoded in Plan.ordered. Checking each token directly
        // preserves nested substitutions without rescanning the whole text and allocating
        // substrings for every brace pair.
        for (int i = 0; i < before; i++) {
            Variable variable = plan.ordered.get(i);
            if (base.containsKey(variable.name) || text.indexOf(variable.token) < 0) continue;
            text = text.replace(variable.token, value(variable.name, before));
        }
        return text;
    }

    private String value(String name, int before) {
        Variable variable = plan.named.get(name);
        if (variable == null || variable.index() >= before) return base.get(name);
        if (resolved.containsKey(name)) return resolved.get(name);
        String source = variable.source, fallback = variable.fallback;
        String value = switch (variable.type) {
            // 保持原先按声明顺序展开 literal/state 的语义。
            case "literal" -> replace(source, variable.index());
            case "state" -> Objects.toString(value(source, variable.index()), fallback);
            case "papi" -> placeholder(source, fallback);
            case "skript" -> Objects.toString(skript.apply(source), fallback);
            case "external" -> external.getOrDefault(source, fallback);
            default -> fallback;
        };
        var mappings = variable.mappings;
        if (mappings != null) value = mappings.getString(value, fallback);
        int limit = variable.limit;
        if (limit > 0) value = MenuSessions.shortNumber(value, limit);
        resolved.put(name, value);
        return value;
    }

    private String placeholder(String text, String fallback) {
        return placeholders.computeIfAbsent(new Placeholder(text, fallback), key -> papi.apply(text, fallback));
    }
}
