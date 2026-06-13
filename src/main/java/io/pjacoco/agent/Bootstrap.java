package io.pjacoco.agent;

import java.lang.instrument.Instrumentation;

/** Java agent entry point. Full wiring is added in Task 14. */
public final class Bootstrap {
    private Bootstrap() {}

    public static void premain(String args, Instrumentation inst) {
        System.out.println("[pjacoco] agent loaded (skeleton)");
    }
}
