package cn.mcmod.wiki.exporter.config;

import java.util.Locale;

public enum ExportProfile {
    DATA,
    IMAGES,
    ALL;

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
