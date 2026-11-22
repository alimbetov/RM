package kz.rm.organization;

import java.text.Normalizer;

/** Narrow, shared normalization for create and full-profile update. */
final class LocationFields {
    private LocationFields() {}

    record Profile(String name, String katoCode, String addressLine) {}

    static Profile profile(String name, String katoCode, String addressLine) {
        String normalizedName = text(name, 200, true, "Location name");
        String normalizedAddress = text(addressLine, 500, false, "Location address");
        if (katoCode == null || !katoCode.matches("[0-9]{2,16}"))
            throw new IllegalArgumentException("KATO must be 2..16 ASCII digits");
        return new Profile(normalizedName, katoCode, normalizedAddress);
    }

    private static String text(String source, int limit, boolean required, String field) {
        if (source == null) {
            if (required) throw new IllegalArgumentException(field + " required");
            return null;
        }
        String result = Normalizer.normalize(source.strip(), Normalizer.Form.NFC);
        if (result.isBlank()) {
            if (required) throw new IllegalArgumentException(field + " required");
            return null;
        }
        int codePoints = result.codePointCount(0, result.length());
        if (codePoints > limit) throw new IllegalArgumentException(field + " exceeds " + limit + " codepoints");
        for (int i = 0; i < result.length();) {
            int cp = result.codePointAt(i);
            // Reject malformed UTF-16, controls and Unicode bidirectional overrides/isolation.
            if ((cp >= 0xD800 && cp <= 0xDFFF) || Character.isISOControl(cp)
                    || cp == 0x061C || cp == 0x200E || cp == 0x200F
                    || (cp >= 0x202A && cp <= 0x202E)
                    || (cp >= 0x2066 && cp <= 0x2069))
                throw new IllegalArgumentException(field + " contains invalid Unicode");
            i += Character.charCount(cp);
        }
        return result;
    }
}
