package dev.dialogmenu;

import java.util.List;

/** One explicit target for the server's resource snapshot, never a per-player guess. */
record ResourceVersion(String id) {
    static final List<String> SUPPORTED = List.of("1.21.7", "1.21.8", "1.21.9", "1.21.10",
        "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3");
    static final ResourceVersion DEFAULT = new ResourceVersion("26.3");

    ResourceVersion {
        if (!SUPPORTED.contains(id))
            throw new IllegalArgumentException("resource-version must be one of: " + String.join(", ", SUPPORTED));
    }

    boolean bitmap() { return id.equals("1.21.7") || id.equals("1.21.8"); }
    boolean itemsAtlas() { return id.equals("26.2") || id.equals("26.3"); }
    String imageAtlas() { return id.equals("1.21.9") || id.equals("1.21.10") ? "blocks" : "items"; }
    String manifest() {
        return equals(DEFAULT) ? "/vanilla-assets.properties" : "/vanilla-assets/" + id + ".properties";
    }
}
