package io.pjacoco.gradle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import org.junit.jupiter.api.Test;

class PjacocoArgsInProcessTest {

    @Test
    void defaultsAppendNothingExtra() {
        String arg = PjacocoArgs.javaagent("/a.jar", 6310, "/out",
                Collections.emptyList(), Collections.emptyList(), true, null, true);
        assertFalse(arg.contains("aggregate="), "default aggregate=true is the agent default; omit it");
        assertFalse(arg.contains("aggregateFile="), "no aggregateFile override -> omit");
        assertFalse(arg.contains("junit4Auto="), "default junit4Auto=true -> omit");
    }

    @Test
    void overridesAreAppended() {
        String arg = PjacocoArgs.javaagent("/a.jar", 6310, "/out",
                Collections.emptyList(), Collections.emptyList(), false, "whole.exec", false);
        assertTrue(arg.contains(",aggregate=false"), arg);
        assertTrue(arg.contains(",aggregateFile=whole.exec"), arg);
        assertTrue(arg.contains(",junit4Auto=false"), arg);
    }
}
