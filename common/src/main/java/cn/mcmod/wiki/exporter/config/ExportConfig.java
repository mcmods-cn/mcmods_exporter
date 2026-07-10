package cn.mcmod.wiki.exporter.config;

import cn.mcmod.wiki.exporter.McWikiExporter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ExportConfig {
    private static final List<Integer> REQUIRED_IMAGE_SIZES = List.of(32, 128, 256);

    private final Path outputRoot;
    private final Set<String> namespaces;
    private final List<String> languages;
    private final List<Integer> imageSizes;
    private final ExportProfile profile;
    private final boolean automatic;
    private final boolean exitAfterExport;
    private final long tickBudgetNanos;

    private ExportConfig(
        Path outputRoot,
        Set<String> namespaces,
        List<String> languages,
        List<Integer> imageSizes,
        ExportProfile profile,
        boolean automatic,
        boolean exitAfterExport,
        long tickBudgetNanos
    ) {
        this.outputRoot = outputRoot;
        this.namespaces = Collections.unmodifiableSet(namespaces);
        this.languages = Collections.unmodifiableList(languages);
        this.imageSizes = Collections.unmodifiableList(imageSizes);
        this.profile = profile;
        this.automatic = automatic;
        this.exitAfterExport = exitAfterExport;
        this.tickBudgetNanos = tickBudgetNanos;
    }

    public static boolean autoEnabled() {
        return Boolean.parseBoolean(System.getProperty("mcwiki.export.auto", "false"));
    }

    public static ExportConfig fromSystemProperties(Path gameDirectory, boolean automatic, String namespaceOverride, ExportProfile profileOverride) {
        String outputValue = System.getProperty("mcwiki.export.output", "mcwiki-export").trim();
        Path output = Path.of(outputValue);
        if (!output.isAbsolute()) {
            output = gameDirectory.resolve(output);
        }

        Set<String> namespaces = parseNamespaces(
            namespaceOverride == null ? System.getProperty("mcwiki.export.namespaces", "") : namespaceOverride
        );
        List<String> languages = parseLanguages(System.getProperty("mcwiki.export.languages", "zh_cn,en_us"));
        List<Integer> sizes = parseSizes(System.getProperty("mcwiki.export.sizes", "32,128,256"));
        ExportProfile profile = profileOverride == null
            ? ExportProfile.parse(System.getProperty("mcwiki.export.profile", "all"))
            : profileOverride;
        boolean exit = automatic && Boolean.parseBoolean(System.getProperty("mcwiki.export.exit", "false"));
        long budgetMs = parseLong(System.getProperty("mcwiki.export.tickBudgetMs", "12"), 12L, 1L, 100L);

        return new ExportConfig(output.normalize(), namespaces, languages, sizes, profile, automatic, exit, budgetMs * 1_000_000L);
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

    private static long parseLong(String value, long fallback, long minimum, long maximum) {
        try {
            return Math.max(minimum, Math.min(maximum, Long.parseLong(value)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public boolean matchesNamespace(String namespace) {
        return namespaces.isEmpty() ? !McWikiExporter.MOD_ID.equals(namespace) : namespaces.contains(namespace);
    }

    public Path outputRoot() {
        return outputRoot;
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
}
