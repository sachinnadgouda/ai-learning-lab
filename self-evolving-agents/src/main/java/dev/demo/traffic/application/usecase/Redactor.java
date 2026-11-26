package dev.demo.traffic.application.usecase;

import java.util.regex.Pattern;

/** Removes credential-like material before anything is persisted. */
public class Redactor {
    private static final Pattern[] PATTERNS = {
        Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}"),
        Pattern.compile("(?i)((?:x-demo-admin|authorization|api[_-]?key|token|secret|password|passwd)\"?\\s*[:=]\\s*\"?)[^\\s\",}]{4,}"),
        Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
    };
    public static final String MASK = "[REDACTED]";

    public String redact(String text) {
        if (text == null) return null;
        String out = text;
        out = PATTERNS[0].matcher(out).replaceAll("$1" + MASK);
        out = PATTERNS[1].matcher(out).replaceAll("$1" + MASK);
        out = PATTERNS[2].matcher(out).replaceAll(MASK);
        return out;
    }
}
