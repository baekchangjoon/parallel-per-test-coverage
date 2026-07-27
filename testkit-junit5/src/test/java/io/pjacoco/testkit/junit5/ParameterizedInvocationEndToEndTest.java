package io.pjacoco.testkit.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * Behavioral regression test for BUG-2 on the HTTP (black-box) path: runs a real
 * {@code @ParameterizedTest} through {@link PjacocoExtension} via the JUnit Platform launcher and
 * asserts each invocation opened its own coverage boundary. Before the fix all three invocations
 * sent {@code testId=...#add} (one shared id) — the agent-side retry-overwrite race then lost
 * per-test .exec files under parallel execution, and the reflection-only unit test could not have
 * caught a missed call site (this extension WAS such a missed call site in the first fix attempt).
 */
class ParameterizedInvocationEndToEndTest {

    private static final List<String> startedTestIds = Collections.synchronizedList(new ArrayList<String>());
    private static HttpServer stubAgent;

    @BeforeAll
    static void startStubAgent() throws Exception {
        stubAgent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubAgent.createContext("/__coverage__/test/start", exchange -> {
            for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
                if (pair.startsWith("testId=")) {
                    startedTestIds.add(URLDecoder.decode(pair.substring("testId=".length()), "UTF-8"));
                }
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        stubAgent.createContext("/__coverage__/test/stop", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        stubAgent.start();
        System.setProperty(io.pjacoco.testkit.Pjacoco.CONTROL_URL_PROPERTY,
                "http://127.0.0.1:" + stubAgent.getAddress().getPort());
    }

    @AfterAll
    static void stopStubAgent() {
        System.clearProperty(io.pjacoco.testkit.Pjacoco.CONTROL_URL_PROPERTY);
        if (stubAgent != null) stubAgent.stop(0);
    }

    @Test
    void eachParameterizedInvocationOpensItsOwnCoverageBoundary() {
        startedTestIds.clear();
        LauncherFactory.create().execute(request().selectors(selectClass(Target.class)).build());

        String base = Target.class.getName() + "#add";
        assertEquals(new TreeSet<String>(java.util.Arrays.asList(
                        base + "[1]", base + "[2]", base + "[3]")),
                new TreeSet<String>(startedTestIds),
                "each @ParameterizedTest invocation must get an invocation-unique testId — a shared"
                        + " id re-enters TestStoreRegistry.start() as a retry-overwrite and loses"
                        + " sibling in-flight stores under parallel execution (BUG-2)");
    }

    /** Executed only via the launcher above; standalone discovery is harmless (extension no-ops
     *  without the control-url property, and the test bodies are empty). */
    @ExtendWith(PjacocoExtension.class)
    static class Target {
        @ParameterizedTest
        @ValueSource(ints = {1, 2, 3})
        void add(int value) { }
    }
}
