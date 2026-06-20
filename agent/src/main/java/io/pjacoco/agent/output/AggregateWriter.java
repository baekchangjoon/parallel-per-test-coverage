package io.pjacoco.agent.output;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.runtime.RuntimeData;

/**
 * Writes the agent's retained whole-run {@link RuntimeData} as a single vanilla-JaCoCo-format
 * {@code .exec} at shutdown (jacoco {@code dumponexit} equivalent). Authored against
 * {@code org.jacoco.*} exactly like {@link ExecWriter}, so the shadow plugin relocates it to
 * {@code io.pjacoco.shaded.jacoco.*}, matching the retained {@code RuntimeData} instance's type.
 */
public final class AggregateWriter {

    /** Collect the whole-run data and serialize it to {@code aggregateFile} (resolved under {@code outDir}).
     *  {@code collect} populates the SessionInfoStore with the runtime's own session (id + dump time),
     *  so no timestamp argument is needed. */
    public void write(Path outDir, String aggregateFile, RuntimeData data) throws Exception {
        ExecutionDataStore execStore = new ExecutionDataStore();
        SessionInfoStore sessionStore = new SessionInfoStore();
        data.collect(execStore, sessionStore, false);

        Path target = resolve(outDir, aggregateFile);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        OutputStream os = new BufferedOutputStream(Files.newOutputStream(target));
        try {
            ExecutionDataWriter w = new ExecutionDataWriter(os);
            sessionStore.accept(w);
            execStore.accept(w);
        } finally {
            os.close();
        }
    }

    /** Per-JVM token substituted for {@code %p} in aggregateFile so multi-module reactors that share a
     *  destfile dir don't overwrite each other's whole-run dump (REQ-U02). PID via RuntimeMXBean
     *  ("pid@host", HotSpot convention) with a nanoTime-hex fallback for JVMs whose format differs.
     *  Resolved once per JVM so the name is stable across calls. */
    static final String PID_TOKEN = computePidToken();

    private static String computePidToken() {
        try {
            String vmName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = vmName.indexOf('@');
            if (at > 0) {
                String pid = vmName.substring(0, at);
                if (pid.matches("\\d+")) return pid;
            }
        } catch (Throwable ignored) { /* fall through to unique fallback */ }
        return Long.toHexString(System.nanoTime());
    }

    /** Absolute path → used as-is; otherwise resolved under the output directory. {@code %p} in the
     *  file name is substituted with the per-JVM {@link #PID_TOKEN} (REQ-U02). */
    static Path resolve(Path outDir, String aggregateFile) {
        String name = aggregateFile.contains("%p") ? aggregateFile.replace("%p", PID_TOKEN) : aggregateFile;
        Path p = Paths.get(name);
        return p.isAbsolute() ? p : outDir.resolve(name);
    }
}
