package cn.mcmod.wiki.exporter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class McModsExporter {
    public static final String MOD_ID = "mcmods_exporter";
    public static final String VERSION = "0.5.0";
    public static final String SCHEMA_VERSION = "mcmods-export/v1";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private McModsExporter() {
    }
}
