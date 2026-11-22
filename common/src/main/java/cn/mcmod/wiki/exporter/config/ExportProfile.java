package cn.mcmod.wiki.exporter.config;

import java.util.Locale;

public enum ExportProfile {
    DATA("data"),
    IMAGES("images"),
    ALL("all");

    private final String wireId;

    ExportProfile(String wireId) {
        this.wireId = wireId;
    }

    public String wireId() {
        return wireId;
    }

    public boolean includesData() {
        return this == DATA || this == ALL;
    }

    public boolean includesImages() {
        return this == IMAGES || this == ALL;
    }

    public static ExportProfile parse(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ALL;
        }
    }
}
