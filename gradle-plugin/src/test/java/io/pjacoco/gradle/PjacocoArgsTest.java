package io.pjacoco.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class PjacocoArgsTest {

    @Test
    void composesJavaagentWithIncludes() {
        String arg = PjacocoArgs.javaagent("/x/agent.jar", 6310, "/out/pjacoco",
                Arrays.asList("com.a.*", "com.b.*"), Collections.<String>emptyList());
        assertEquals("-javaagent:/x/agent.jar=destfile=/out/pjacoco,port=6310,includes=com.a.*:com.b.*", arg);
    }

    @Test
    void omitsIncludesAndExcludesWhenEmpty() {
        String arg = PjacocoArgs.javaagent("/x/agent.jar", 6310, "/out",
                Collections.<String>emptyList(), Collections.<String>emptyList());
        assertEquals("-javaagent:/x/agent.jar=destfile=/out,port=6310", arg);
    }

    @Test
    void includesExcludes() {
        String arg = PjacocoArgs.javaagent("/a.jar", 6311, "/o",
                Arrays.asList("com.a.*"), Arrays.asList("com.a.gen.*"));
        assertEquals("-javaagent:/a.jar=destfile=/o,port=6311,includes=com.a.*,excludes=com.a.gen.*", arg);
    }

    @Test
    void controlUrlArg() {
        assertEquals("-Dpjacoco.control-url=http://127.0.0.1:6310", PjacocoArgs.controlUrlArg(6310));
    }
}
