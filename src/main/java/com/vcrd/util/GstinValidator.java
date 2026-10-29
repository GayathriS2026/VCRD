package com.vcrd.util;

import java.util.regex.Pattern;

/** Format and check-digit validation for Indian GSTINs. */
public final class GstinValidator {

    public static final Pattern GSTIN = Pattern.compile("\\b\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]\\b");
    private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

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
}
