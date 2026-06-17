package io.pjacoco.testkit.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The auto-detection services file must list EXACTLY the in-process extension — never the HTTP-path
 *  PjacocoExtension (auto-registering that would open control-plane calls suite-wide). */
class ServicesFileTest {

    @Test
    void servicesFileListsOnlyTheInProcessExtension() throws Exception {
        InputStream in = getClass().getClassLoader()
                .getResourceAsStream("META-INF/services/org.junit.jupiter.api.extension.Extension");
        assertNotNull(in, "services file must be on the classpath");
        List<String> entries = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    entries.add(t);
                }
            }
        }
        assertEquals(1, entries.size(), "exactly one service entry; was: " + entries);
        assertEquals("io.pjacoco.testkit.junit5.PjacocoInProcessExtension", entries.get(0));
    }
}
