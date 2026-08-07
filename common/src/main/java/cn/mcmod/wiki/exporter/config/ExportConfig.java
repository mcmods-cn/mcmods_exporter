package cn.mcmod.wiki.exporter.config;

import cn.mcmod.wiki.exporter.McModsExporter;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public final class ExportConfig {
    private static final List<Integer> REQUIRED_IMAGE_SIZES = List.of(32, 128, 256);
    private static final String CONFIG_PATH_PROPERTY = "mcmods.export.config";
    private static final String EXCLUDED_RECIPE_TYPES_PROPERTY = "mcmods.export.recipeTypes.exclude";
    private static final String TEMPLATE_ONLY_RECIPE_TYPES_PROPERTY = "mcmods.export.recipeTypes.templateOnly";
    private static final String DEFAULT_CONFIG = """
        # mcmods_exporter configuration. JVM -D properties override values in this file.
        mcmods.export.output=mcmods-export
        mcmods.export.namespaces=
        mcmods.export.languages=zh_cn,en_us
        mcmods.export.sizes=32,128,256
        mcmods.export.modules=all
        mcmods.export.profile=all
        mcmods.export.tickBudgetMs=12
        mcmods.export.exit=false

        # Comma-separated JEI recipe_type_id values (namespace:path).
        # exclude removes the category, templates, backgrounds, and concrete recipes.
        mcmods.export.recipeTypes.exclude=
        # templateOnly keeps category metadata, catalysts, backgrounds, and templates,
        # but writes no concrete recipes. Example: minecraft:anvil
        mcmods.export.recipeTypes.templateOnly=
        """;

    private final Path outputRoot;
    private final Path configFile;
    private final Set<String> namespaces;
    private final List<String> languages;
    private final List<Integer> imageSizes;
    private final Set<String> modules;
    private final ExportProfile profile;
    private final boolean automatic;
    private final boolean exitAfterExport;
    private final long tickBudgetNanos;
    private final Set<String> excludedRecipeTypes;
    private final Set<String> templateOnlyRecipeTypes;

    private ExportConfig(
        Path outputRoot,
        Path configFile,
        Set<String> namespaces,
        List<String> languages,
        List<Integer> imageSizes,
        Set<String> modules,
        ExportProfile profile,
        boolean automatic,
        boolean exitAfterExport,
        long tickBudgetNanos,
        Set<String> excludedRecipeTypes,
        Set<String> templateOnlyRecipeTypes
    ) {
        this.outputRoot = outputRoot;
        this.configFile = configFile;
        this.namespaces = Collections.unmodifiableSet(namespaces);
        this.languages = Collections.unmodifiableList(languages);
        this.imageSizes = Collections.unmodifiableList(imageSizes);
        this.modules = Collections.unmodifiableSet(modules);
        this.profile = profile;
        this.automatic = automatic;
        this.exitAfterExport = exitAfterExport;
        this.tickBudgetNanos = tickBudgetNanos;
        this.excludedRecipeTypes = Collections.unmodifiableSet(excludedRecipeTypes);
        this.templateOnlyRecipeTypes = Collections.unmodifiableSet(templateOnlyRecipeTypes);
    }

    public static boolean autoEnabled() {
        return Boolean.parseBoolean(System.getProperty("mcmods.export.auto", "false"));
    }

    public static ExportConfig fromSystemProperties(Path gameDirectory, boolean automatic, String namespaceOverride, ExportProfile profileOverride) {
        Path configFile = resolveConfigFile(gameDirectory);
        Properties fileSettings = loadFileSettings(configFile);
        String outputValue = setting(fileSettings, "mcmods.export.output", "mcmods-export").trim();
        Path output = Path.of(outputValue);
        if (!output.isAbsolute()) {
            output = gameDirectory.resolve(output);
        }

        Set<String> namespaces = parseNamespaces(
            namespaceOverride == null ? setting(fileSettings, "mcmods.export.namespaces", "") : namespaceOverride
        );
        List<String> languages = parseLanguages(setting(fileSettings, "mcmods.export.languages", "zh_cn,en_us"));
        List<Integer> sizes = parseSizes(setting(fileSettings, "mcmods.export.sizes", "32,128,256"));
        Set<String> modules = parseModules(setting(fileSettings, "mcmods.export.modules", "all"));
        ExportProfile profile = profileOverride == null
            ? ExportProfile.parse(setting(fileSettings, "mcmods.export.profile", "all"))
            : profileOverride;
        boolean exit = automatic && Boolean.parseBoolean(setting(fileSettings, "mcmods.export.exit", "false"));
        long budgetMs = parseLong(setting(fileSettings, "mcmods.export.tickBudgetMs", "12"), 12L, 1L, 100L);
        Set<String> excludedRecipeTypes = parseResourceIds(setting(fileSettings, EXCLUDED_RECIPE_TYPES_PROPERTY, ""));
        Set<String> templateOnlyRecipeTypes = parseResourceIds(setting(fileSettings, TEMPLATE_ONLY_RECIPE_TYPES_PROPERTY, ""));
        templateOnlyRecipeTypes.removeAll(excludedRecipeTypes);

        return new ExportConfig(
            output.normalize(),
            configFile,
            namespaces,
            languages,
            sizes,
            modules,
            profile,
            automatic,
            exit,
            budgetMs * 1_000_000L,
            excludedRecipeTypes,
            templateOnlyRecipeTypes
        );
    }

    private static Path resolveConfigFile(Path gameDirectory) {
        String configured = System.getProperty(CONFIG_PATH_PROPERTY, "config/mcmods_exporter.properties").trim();
        Path path = Path.of(configured.isEmpty() ? "config/mcmods_exporter.properties" : configured);
        return (path.isAbsolute() ? path : gameDirectory.resolve(path)).normalize();
    }

    private static Properties loadFileSettings(Path configFile) {
        Properties properties = new Properties();
        try {
            Path parent = configFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.notExists(configFile)) {
                try {
                    Files.writeString(
                        configFile,
                        DEFAULT_CONFIG,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW
                    );
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // Another initialization path created it between the checks.
                }
            }
            try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        } catch (IOException exception) {
            McModsExporter.LOGGER.warn("Unable to load exporter config {}", configFile, exception);
        }
        return properties;
    }

    private static String setting(Properties fileSettings, String key, String fallback) {
        String systemValue = System.getProperty(key);
        return systemValue == null ? fileSettings.getProperty(key, fallback) : systemValue;
    }

    private static Set<String> parseNamespaces(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value == null || value.isBlank() || value.trim().equals("*") || value.trim().equalsIgnoreCase("all")) {
            return result;
        }
        for (String part : value.split(",")) {
            String namespace = part.trim().toLowerCase(Locale.ROOT);
            if (!namespace.isEmpty()) {
                result.add(namespace);
            }
        }
        return result;
    }

    private static List<String> parseLanguages(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value != null) {
            for (String part : value.split(",")) {
                String language = part.trim().toLowerCase(Locale.ROOT);
                if (language.matches("[a-z0-9_-]+")) {
                    result.add(language);
                }
            }
        }
        result.add("en_us");
        return new ArrayList<>(result);
    }

    private static List<Integer> parseSizes(String value) {
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        if (value != null) {
            for (String part : value.split(",")) {
                try {
                    int size = Integer.parseInt(part.trim());
                    if (size >= 16 && size <= 2048) {
                        result.add(size);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        result.addAll(REQUIRED_IMAGE_SIZES);
        ArrayList<Integer> sorted = new ArrayList<>(result);
        Collections.sort(sorted);
        return sorted;
    }

    private static Set<String> parseModules(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value == null || value.isBlank() || value.trim().equals("*") || value.trim().equalsIgnoreCase("all")) {
            return result;
        }
        for (String part : value.split(",")) {
            String module = part.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            if (module.matches("[a-z0-9_]+")) {
                result.add(module);
            }
        }
        return result;
    }

    private static Set<String> parseResourceIds(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String part : value.split(",")) {
            String id = part.trim().toLowerCase(Locale.ROOT);
            if (id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                result.add(id);
            } else if (!id.isEmpty()) {
                McModsExporter.LOGGER.warn("Ignoring invalid recipe_type_id in exporter config: {}", id);
            }
        }
        return result;
    }

    private static long parseLong(String value, long fallback, long minimum, long maximum) {
        try {
            return Math.max(minimum, Math.min(maximum, Long.parseLong(value)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public boolean matchesNamespace(String namespace) {
        return namespaces.isEmpty() ? !McModsExporter.MOD_ID.equals(namespace) : namespaces.contains(namespace);
    }

    public Path outputRoot() {
        return outputRoot;
    }

    public Path configFile() {
        return configFile;
    }

    public Set<String> namespaces() {
        return namespaces;
    }

    public List<String> languages() {
        return languages;
    }

    public List<Integer> imageSizes() {
        return imageSizes;
    }

    /** Empty means every module allowed by the selected profile. */
    public Set<String> modules() {
        return modules;
    }

    public boolean moduleEnabled(String id) {
        return modules.isEmpty() || modules.contains(id);
    }

    public ExportProfile profile() {
        return profile;
    }

    public boolean automatic() {
        return automatic;
    }

    public boolean exitAfterExport() {
        return exitAfterExport;
    }

    public long tickBudgetNanos() {
        return tickBudgetNanos;
    }

    public Set<String> excludedRecipeTypes() {
        return excludedRecipeTypes;
    }

    public Set<String> templateOnlyRecipeTypes() {
        return templateOnlyRecipeTypes;
    }

    public boolean excludesRecipeType(String recipeTypeId) {
        return excludedRecipeTypes.contains(recipeTypeId);
    }

    public boolean exportsRecipeTypeTemplatesOnly(String recipeTypeId) {
        return templateOnlyRecipeTypes.contains(recipeTypeId);
    }
}
