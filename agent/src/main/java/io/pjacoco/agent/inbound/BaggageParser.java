package io.pjacoco.agent.inbound;

import java.net.URLDecoder;

/** Parses a W3C / OpenTelemetry {@code baggage} header value and returns the {@code test.id} member. */
public final class BaggageParser {
    private static final String KEY = "test.id";
    private BaggageParser() {}

    public static String testId(String header) {
        if (header == null || header.isEmpty()) return null;
        for (String member : header.split(",")) {
            String m = member.trim();
            int semi = m.indexOf(';');                 // drop ;properties
            if (semi >= 0) m = m.substring(0, semi);
            int eq = m.indexOf('=');
            if (eq <= 0) continue;
            String key = m.substring(0, eq).trim();
            if (KEY.equals(key)) {
                String val = m.substring(eq + 1).trim();
                try {
                    return URLDecoder.decode(val, "UTF-8");
                } catch (Exception e) {
                    return val;
                }
            }
        }
        return null;
    }
}
