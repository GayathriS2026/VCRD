package com.vcrd.util;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Format and check-digit validation for Indian GSTINs. */
public final class GstinValidator {

    public static final Pattern GSTIN = Pattern.compile("\\b\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]\\b");
    /** A token that may be a GSTIN with OCR misreads (letters for digits and back, one extra character). */
    public static final Pattern LOOSE_GSTIN = Pattern.compile("(?<![A-Z0-9])[0-9A-Z]{15,16}(?![A-Z0-9])");
    private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LETTER_LIKE = "ODQILZSBGT";
    private static final String DIGIT_FOR_LETTER = "0001125867";
    private static final String DIGIT_LIKE = "012568";
    private static final String LETTER_FOR_DIGIT = "OIZSGB";

    private GstinValidator() {
    }

    public static boolean isValid(String gstin) {
        if (gstin == null || !GSTIN.matcher(gstin).matches()) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int product = CHARS.indexOf(gstin.charAt(i)) * (i % 2 == 0 ? 1 : 2);
            sum += product / 36 + product % 36;
        }
        return CHARS.charAt((36 - sum % 36) % 36) == gstin.charAt(14);
    }

    /**
     * Corrects a GSTIN misread by OCR ("0BAAGCJO0507N1ZG" for 08AAGCJ0507N1ZG): confusable characters are
     * mapped to the kind each position requires and, for 16 characters, each one is tried as the extra one.
     * The check digit decides; the result is returned only when exactly one correction is valid, so a
     * correction is never guessed.
     */
    public static String repair(String token) {
        if (isValid(token)) {
            return token;
        }
        Set<String> valid = new LinkedHashSet<>();
        if (token.length() == 15) {
            collect(token, valid);
        } else if (token.length() == 16) {
            for (int skip = 0; skip < 16; skip++) {
                collect(token.substring(0, skip) + token.substring(skip + 1), valid);
            }
        }
        return valid.size() == 1 ? valid.iterator().next() : null;
    }

    private static void collect(String s, Set<String> valid) {
        char[] c = s.toCharArray();
        for (int i : new int[] {0, 1, 7, 8, 9, 10}) {
            c[i] = toDigit(c[i]);
        }
        for (int i : new int[] {2, 3, 4, 5, 6, 11}) {
            c[i] = toLetter(c[i]);
        }
        c[13] = c[13] == '2' ? 'Z' : c[13];
        // Positions 12 and 14 may be a letter or a digit: try each reading.
        for (char p12 : variants(c[12])) {
            for (char p14 : variants(c[14])) {
                c[12] = p12;
                c[14] = p14;
                String candidate = new String(c);
                // More than two corrected characters is no longer a misread but a guess.
                if (differences(s, candidate) <= MAX_CORRECTIONS && isValid(candidate)) {
                    valid.add(candidate);
                }
            }
        }
    }

    private static final int MAX_CORRECTIONS = 2;

    public static int differences(String a, String b) {
        int n = 0;
        for (int i = 0; i < Math.min(a.length(), b.length()); i++) {
            if (a.charAt(i) != b.charAt(i)) n++;
        }
        return n + Math.abs(a.length() - b.length());
    }

    private static char[] variants(char ch) {
        return new char[] {ch, toDigit(ch), toLetter(ch)};
    }

    private static char toDigit(char ch) {
        int i = LETTER_LIKE.indexOf(ch);
        return i >= 0 ? DIGIT_FOR_LETTER.charAt(i) : ch;
    }

    private static char toLetter(char ch) {
        int i = DIGIT_LIKE.indexOf(ch);
        return i >= 0 ? LETTER_FOR_DIGIT.charAt(i) : ch;
    }

    /** PAN embedded in a GSTIN (characters 3 to 12). */
    public static String pan(String gstin) {
        return gstin.substring(2, 12);
    }
}
