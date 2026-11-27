package com.agy.util;

import android.net.Uri;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Redacts credentials and query strings before URLs enter logs or exported reports. */
public final class SensitiveDataRedactor {

    private static final Pattern URL = Pattern.compile("(?i)(?:https?|wss?)://[^\\s\\\"'<>]+");
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)([\\\"]?(?:access[_-]?token|refresh[_-]?token|id[_-]?token|"
                    + "csrf[_-]?token|auth[_-]?token|oauth[_-]?token|client[_-]?secret|"
                    + "device[_-]?code|code[_-]?verifier|session[_-]?state|password|passwd|"
                    + "api[_-]?key|secret)[\\\"]?"
                    + "\\s*[:=]\\s*[\\\"]?)([^\\\"'\\s,;&}]+)");
    private static final Pattern SENSITIVE_HEADER = Pattern.compile(
            "(?i)([\\\"]?(?:authorization|cookie)[\\\"]?\\s*[:=]\\s*)[^,}\\r\\n]+");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/-]+=*");

    private SensitiveDataRedactor() {
    }

    /** Returns only the scheme, host, and port; path, credentials, query and fragment are omitted. */
    public static String safeUrl(String value) {
        if (value == null || value.isEmpty()) {
            return "<url>";
        }
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isEmpty()) {
                return "<url>";
            }
            StringBuilder safe = new StringBuilder()
                    .append(scheme.toLowerCase(Locale.US)).append("://").append(host);
            int port = uri.getPort();
            if (port >= 0) {
                safe.append(':').append(port);
            }
            return safe.append("/<redacted>").toString();
        } catch (Exception ignored) {
            return "<url>";
        }
    }

    /** Redacts full URL paths and sensitive token fields embedded in arbitrary log text. */
    public static String redactLine(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        String current = line;
        if (current.contains("://")) {
            Matcher urls = URL.matcher(current);
            if (urls.find()) {
                StringBuffer output = new StringBuffer(current.length());
                do {
                    urls.appendReplacement(output, Matcher.quoteReplacement(safeUrl(urls.group())));
                } while (urls.find());
                urls.appendTail(output);
                current = output.toString();
            }
        }
        if (!mightContainSensitiveField(current)) {
            return current;
        }
        String redacted = SECRET_FIELD.matcher(current).replaceAll("$1<redacted>");
        redacted = SENSITIVE_HEADER.matcher(redacted).replaceAll("$1<redacted>");
        return BEARER.matcher(redacted).replaceAll("$1<redacted>");
    }

    private static boolean mightContainSensitiveField(String s) {
        return containsIgnoreCase(s, "token")
                || containsIgnoreCase(s, "secret")
                || containsIgnoreCase(s, "pass")
                || containsIgnoreCase(s, "key")
                || containsIgnoreCase(s, "code")
                || containsIgnoreCase(s, "verif")
                || containsIgnoreCase(s, "state")
                || containsIgnoreCase(s, "auth")
                || containsIgnoreCase(s, "cookie")
                || containsIgnoreCase(s, "bearer");
    }

    private static boolean containsIgnoreCase(String haystack, String lowerNeedle) {
        int needleLen = lowerNeedle.length();
        int maxStart = haystack.length() - needleLen;
        if (maxStart < 0) {
            return false;
        }
        char firstLower = lowerNeedle.charAt(0);
        char firstUpper = Character.toUpperCase(firstLower);
        for (int i = 0; i <= maxStart; i++) {
            char c = haystack.charAt(i);
            if ((c == firstLower || c == firstUpper)
                    && haystack.regionMatches(true, i, lowerNeedle, 0, needleLen)) {
                return true;
            }
        }
        return false;
    }
}
