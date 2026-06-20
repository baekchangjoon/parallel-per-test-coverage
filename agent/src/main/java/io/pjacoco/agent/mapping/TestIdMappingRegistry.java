package io.pjacoco.agent.mapping;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Bounded {@code traceId -> testId} map for the report/merge layer (REQ-011). LRU by access order with a
 * hard cap so a high-cardinality, long-running service cannot OOM. testIds are canonicalized on register
 * (REQ-014). Pure Java — no tracer dependency, no hot-path involvement (display mapping is report-time
 * only, design §5.3).
 */
public final class TestIdMappingRegistry implements TraceMapping {

    private final int maxEntries;
    private final Map<String, String> map;

    public TestIdMappingRegistry(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
        this.map = Collections.synchronizedMap(new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > TestIdMappingRegistry.this.maxEntries;
            }
        });
    }

    /** Register a mapping. No-op when {@code traceId} is null or {@code testId} normalizes to null. */
    public void register(String traceId, String testId) {
        try {
            if (traceId == null) return;
            String t = TestIdNormalizer.normalize(testId);
            if (t == null) return;
            map.put(traceId, t);
        } catch (Throwable ignored) {
            // best-effort: never disturb the control plane (REQ-003)
        }
    }

    @Override public String testIdFor(String traceId) {
        return traceId == null ? null : map.get(traceId);
    }

    public int size() { return map.size(); }

    /** Best-effort dump to a {@code key=value} properties file for offline/central merge (C3 seed). */
    public void writeTo(Path file) {
        try {
            Properties p = new Properties();
            synchronized (map) {
                for (Map.Entry<String, String> e : map.entrySet()) p.setProperty(e.getKey(), e.getValue());
            }
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            try (OutputStream os = Files.newOutputStream(file)) {
                p.store(os, "pjacoco traceId->testId map");
            }
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    /** Load an immutable mapping from a properties file. Missing/unreadable file -> always-null mapping. */
    public static TraceMapping loadFrom(Path file) {
        final Properties p = new Properties();
        try {
            if (Files.exists(file)) {
                try (InputStream is = Files.newInputStream(file)) { p.load(is); }
            }
        } catch (Throwable ignored) {
            // fall through to an empty (always-null) mapping
        }
        return new TraceMapping() {
            @Override public String testIdFor(String traceId) {
                return traceId == null ? null : p.getProperty(traceId);
            }
        };
    }
}
