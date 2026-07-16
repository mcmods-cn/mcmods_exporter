package cn.mcmod.wiki.exporter.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbabilityTextTest {
    @Test
    void parsesLocalizedPercentAndComparator() {
        ProbabilityText.Match exact = ProbabilityText.parse("副产品概率：12,5％").orElseThrow();
        assertEquals(0.125D, exact.probability());
        assertEquals("exact", exact.comparator());

        ProbabilityText.Match lessThan = ProbabilityText.parse("Chance: <1%").orElseThrow();
        assertEquals(0.01D, lessThan.probability());
        assertEquals("less_than", lessThan.comparator());
    }

    @Test
    void rejectsNonProbabilityText() {
        assertTrue(ProbabilityText.parse("Processing time: 200 ticks").isEmpty());
    }
}
