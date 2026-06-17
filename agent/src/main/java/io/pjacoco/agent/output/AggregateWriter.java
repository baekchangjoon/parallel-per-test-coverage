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

    /** Absolute path → used as-is; otherwise resolved under the output directory. */
    static Path resolve(Path outDir, String aggregateFile) {
        Path p = Paths.get(aggregateFile);
        return p.isAbsolute() ? p : outDir.resolve(aggregateFile);
    }
}
