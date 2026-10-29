package com.vcrd.util;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an amount written in Indian English words ("Rupees Sixty Eight Thousand Four Hundred Forty Only",
 * "... Lakh ... and Fifty Paise Only"). Unknown words are skipped, so OCR noise between number words is
 * tolerated; a result is returned only when at least two number words were recognised.
 */
public final class AmountInWords {

    /** "Rupees ... Only" on one line; OCR may drop the R ("upees"). */
    public static final Pattern PHRASE = Pattern.compile("(?i)\\b(?:r?upees|rs\\.?|inr)\\s+([a-z ,\\-]+?)\\s*only\\b");

    private static final Map<String, Integer> UNITS = Map.ofEntries(
            Map.entry("zero", 0), Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3),
            Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7),
            Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11),
            Map.entry("twelve", 12), Map.entry("thirteen", 13), Map.entry("fourteen", 14),
            Map.entry("fifteen", 15), Map.entry("sixteen", 16), Map.entry("seventeen", 17),
            Map.entry("eighteen", 18), Map.entry("nineteen", 19), Map.entry("twenty", 20),
            Map.entry("thirty", 30), Map.entry("forty", 40), Map.entry("fourty", 40), Map.entry("fifty", 50),
            Map.entry("sixty", 60), Map.entry("seventy", 70), Map.entry("eighty", 80), Map.entry("ninety", 90));
    private static final Map<String, Long> SCALES = Map.of(
            "hundred", 100L, "thousand", 1_000L, "lakh", 100_000L, "lakhs", 100_000L, "lac", 100_000L,
            "lacs", 100_000L, "crore", 10_000_000L, "crores", 10_000_000L, "million", 1_000_000L);

    private static final java.util.Set<String> FILLERS = java.util.Set.of("and", "rupees", "upees", "inr", "only", "paise");

    private AmountInWords() {
    }

    /** First "Rupees ... Only" phrase in the line, as a number, or null. */
    public static BigDecimal find(String line) {
        Matcher m = PHRASE.matcher(line);
        return m.find() ? parse(m.group(1)) : null;
    }

    public static BigDecimal parse(String words) {
        String[] parts = words.toLowerCase(Locale.ROOT).replaceAll("only", " ").split("\\s+and\\s+(?=\\S+\\s+paise)");
        long[] rupees = number(parts[0]);
        if (rupees[1] < 2) {
            return null;
        }
        BigDecimal value = BigDecimal.valueOf(rupees[0]);
        if (parts.length > 1) {
            long[] paise = number(parts[1]);
            if (paise[1] > 0 && paise[0] < 100) {
                value = value.add(BigDecimal.valueOf(paise[0], 2));
            }
        }
        return value.setScale(2);
    }

    /** {value, number words recognised}. */
    private static long[] number(String words) {
        long total = 0;
        long current = 0;
        int recognised = 0;
        boolean unreadable = false;
        for (String raw : words.split("[\\s,\\-]+")) {
            String w = raw.replaceAll("[^a-z]", "");
            boolean numberWord = UNITS.containsKey(w) || SCALES.containsKey(w);
            if (!numberWord && w.length() >= 3 && !FILLERS.contains(w) && recognised > 0) {
                // A garbled word between number words ("Sixty Eight JRwkand Four Hundred") is usually a
                // misread scale word; reading around it would give a wrong amount, so give up instead.
                unreadable = true;
            } else if (numberWord && unreadable) {
                return new long[] {0, 0};
            }
            if (UNITS.containsKey(w)) {
                current += UNITS.get(w);
                recognised++;
            } else if ("hundred".equals(w)) {
                current = (current == 0 ? 1 : current) * 100;
                recognised++;
            } else if (SCALES.containsKey(w)) {
                total += (current == 0 ? 1 : current) * SCALES.get(w);
                current = 0;
                recognised++;
            }
        }
        return new long[] {total + current, recognised};
    }
}
