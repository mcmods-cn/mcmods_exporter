package cn.mcmod.wiki.exporter.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Locale-tolerant parsing for probability text emitted by JEI integrations. */
public final class ProbabilityText {
    private static final Pattern PERCENT = Pattern.compile(
        "(?<![\\d.])([<>≤≥]?)\\s*(\\d{1,3}(?:[.,]\\d+)?)\\s*[%％]"
    );

    private ProbabilityText() {
    }

    public static Optional<Match> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = PERCENT.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        double percent;
        try {
            percent = Double.parseDouble(matcher.group(2).replace(',', '.'));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
        if (percent < 0.0D || percent > 100.0D) {
            return Optional.empty();
        }
        String comparator = switch (matcher.group(1)) {
            case "<" -> "less_than";
            case ">" -> "greater_than";
            case "≤" -> "less_than_or_equal";
            case "≥" -> "greater_than_or_equal";
            default -> "exact";
        };
        return Optional.of(new Match(percent / 100.0D, percent, comparator, text, matcher.start(), matcher.end()));
    }

    public record Match(
        double probability,
        double percent,
        String comparator,
        String text,
        int start,
        int end
    ) {
    }

    public record Draw(Match match, int x, int y) {
    }
}
