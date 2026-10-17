package com.authswitch.model;

/** Card-number helpers. PANs are sensitive: always log {@link #mask(String)}, never the raw value. */
public final class PanUtil {
    private PanUtil() {}

    public static boolean luhnValid(String pan) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            int d = pan.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    /** PCI-style masking: first 6 (BIN) and last 4 only. */
    public static String mask(String pan) {
        if (pan.length() < 13) return "****";
        return pan.substring(0, 6) + "*".repeat(pan.length() - 10) + pan.substring(pan.length() - 4);
    }

    /** Test helper: appends the Luhn check digit to a partial PAN. */
    public static String withCheckDigit(String partial) {
        int sum = 0;
        boolean doubleIt = true;
        for (int i = partial.length() - 1; i >= 0; i--) {
            int d = partial.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return partial + ((10 - sum % 10) % 10);
    }
}
