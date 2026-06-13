package io.pjacoco.agent.inbound;

import java.lang.instrument.Instrumentation;

/** Strategy for activating CoverageContext from an inbound request, per transport. */
public interface InboundActivator {
    /** Install this activator's instrumentation (its own body-only ByteBuddy advice). */
    void install(Instrumentation inst);
}
