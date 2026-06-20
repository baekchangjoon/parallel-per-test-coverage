package io.pjacoco.agent.output;

/** Minimal dependency-free JSON object emitter. Null string values are omitted. */
public final class Json {
    private final StringBuilder sb = new StringBuilder("{");
    private boolean first = true;

    public Json put(String key, String value) {
        if (value == null) return this;            // omit nulls
        sep().append('"').append(esc(key)).append("\":\"").append(esc(value)).append('"');
        return this;
    }

    public Json put(String key, long value) {
        sep().append('"').append(esc(key)).append("\":").append(value);
        return this;
    }

    public Json put(String key, boolean value) {
        sep().append('"').append(esc(key)).append("\":").append(value);
        return this;
    }

    private StringBuilder sep() {
        if (!first) sb.append(',');
        first = false;
        return sb;
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    @Override public String toString() { return sb.toString() + "}"; }
}
