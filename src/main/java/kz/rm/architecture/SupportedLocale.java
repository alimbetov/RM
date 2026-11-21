package kz.rm.architecture;

/**
 * Canonical product locale codes (BCP-47).
 * Locale is presentation metadata, never a partitioning or tenant identity key.
 */
public enum SupportedLocale {
    KK("kk"), RU("ru"), EN("en"), ZH_HANS("zh-Hans");
    private final String code;
    SupportedLocale(String code) { this.code=code; }
    public String code() { return code; }
    public static SupportedLocale fromCode(String code) {
        for (var locale : values()) if (locale.code.equals(code)) return locale;
        throw new IllegalArgumentException("Unsupported product locale");
    }
}
