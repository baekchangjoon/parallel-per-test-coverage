package io.pjacoco.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** AC-IP4: the in-JVM activation API must stay at its un-relocated FQN inside the built shaded agent
 *  jar, because the testkit resolves it reflectively by that exact name. Guards against a future
 *  relocation rule that would rename io.pjacoco.agent.api.*. */
class ShadedJarContainsApiTest {

    @Test
    @EnabledIfSystemProperty(named = "pjacoco.shadedJar", matches = ".+")
    void shadedJarContainsCoverageControlAtStableFqn() throws Exception {
        File jar = new File(System.getProperty("pjacoco.shadedJar"));
        assertTrue(jar.isFile(), "shaded jar not found: " + jar);
        boolean found = false;
        try (ZipFile zf = new ZipFile(jar)) {
            for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
                if (e.nextElement().getName().equals("io/pjacoco/agent/api/CoverageControl.class")) {
                    found = true;
                    break;
                }
            }
        }
        assertTrue(found, "io/pjacoco/agent/api/CoverageControl.class must be present (un-relocated) in the shaded jar");
    }
}
