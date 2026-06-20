package io.pjacoco.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * P1-1 / P3-6 guards: the plugin's default agent version is single-sourced from its OWN version (not a
 * hardcoded literal that can be forgotten on a bump), and the functional-test version helper fails fast
 * instead of masking a missing injection with a stale literal.
 */
class PluginVersionSingleSourceTest {

    @Test
    void defaultAgentVersionTracksThePluginOwnVersion() {
        // The build injects pjacoco.it.version = project.version; DEFAULT_AGENT_VERSION must equal it,
        // proving the constant is sourced from the generated version resource rather than a stale literal.
        assertEquals(ItSupport.itVersion(), PjacocoPlugin.DEFAULT_AGENT_VERSION,
                "DEFAULT_AGENT_VERSION must track the plugin's own (project) version");
    }

    @Test
    void defaultAgentVersionResourceWasGeneratedAndLoaded() {
        assertNotEquals("0.0.0-UNKNOWN", PjacocoPlugin.DEFAULT_AGENT_VERSION,
                "the generated version.properties resource must be present and loaded");
    }

    @Test
    void itVersionReturnsTheInjectedValue() {
        assertEquals(System.getProperty("pjacoco.it.version"), ItSupport.itVersion());
    }

    @Test
    void itVersionFailsFastWhenUnset() {
        String saved = System.getProperty("pjacoco.it.version");
        try {
            System.clearProperty("pjacoco.it.version");
            assertThrows(IllegalStateException.class, ItSupport::itVersion,
                    "a missing pjacoco.it.version must fail loudly, not fall back to a stale literal");
        } finally {
            if (saved != null) System.setProperty("pjacoco.it.version", saved);
        }
    }
}
