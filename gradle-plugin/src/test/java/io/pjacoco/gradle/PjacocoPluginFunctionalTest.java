package io.pjacoco.gradle;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AC2 (in-JVM path): apply {@code io.pjacoco.gradle} to a real consumer build and prove the plugin
 * resolves the agent, composes/injects {@code -javaagent} + {@code -Dpjacoco.control-url} into the
 * test JVM, and the agent produces a per-test {@code .exec} (+ sidecar) for an in-JVM black-box
 * scenario (embedded Jetty servlet in the test JVM). The freshly built shaded agent + testkit jars
 * are served to the consumer via a flatDir repo (no publishing).
 *
 * <p>The separate-process path (AC6 — SUT in its own JVM via {@code JavaExec}, wired with the exposed
 * {@code pjacoco.agentJvmArg}) is not covered here; see the design spec's open items.
 */
class PjacocoPluginFunctionalTest {

    static final int PORT = 6320;

    @Test
    void pluginAttachesAgentAndProducesPerTestExec(@TempDir Path consumer) throws IOException {
        String version = System.getProperty("pjacoco.it.version", "1.0.0");
        Path repo = Files.createDirectories(consumer.resolve("flatrepo"));
        // flatDir resolves group:name:version -> name-version.jar (group ignored).
        Files.copy(Path.of(System.getProperty("pjacoco.it.agentJar")), repo.resolve("pjacoco-agent-" + version + ".jar"));
        Files.copy(Path.of(System.getProperty("pjacoco.it.testkitJar")), repo.resolve("pjacoco-testkit-" + version + ".jar"));

        write(consumer.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"\n");
        write(consumer.resolve("build.gradle.kts"),
                "plugins {\n"
              + "    java\n"
              + "    id(\"io.pjacoco.gradle\")\n"
              + "}\n"
              + "repositories {\n"
              + "    mavenCentral()\n"
              + "    flatDir { dirs(\"" + repo.toUri().getPath() + "\") }\n"
              + "}\n"
              + "dependencies {\n"
              + "    testImplementation(\"io.pjacoco:pjacoco-testkit:" + version + "\")\n"
              + "    testImplementation(\"org.junit.jupiter:junit-jupiter:5.10.3\")\n"
              + "    testImplementation(\"org.eclipse.jetty:jetty-server:9.4.55.v20240627\")\n"
              + "    testImplementation(\"org.eclipse.jetty:jetty-servlet:9.4.55.v20240627\")\n"
              + "    testImplementation(\"javax.servlet:javax.servlet-api:3.1.0\")\n"
              + "    testRuntimeOnly(\"org.junit.platform:junit-platform-launcher\")\n"
              + "}\n"
              + "pjacoco {\n"
              + "    agentVersion.set(\"" + version + "\")\n"
              + "    port.set(" + PORT + ")\n"
              + "    includes.set(listOf(\"com.consumer.Calc\"))\n"
              + "    attachTo.set(listOf(\"test\"))\n"
              + "}\n"
              + "tasks.test { useJUnitPlatform() }\n");

        // SUT compiled by the consumer build (Java 11+ bytecode on JDK 11/17 -> Condy path).
        write(consumer.resolve("src/main/java/com/consumer/Calc.java"),
                "package com.consumer;\n"
              + "public class Calc {\n"
              + "    public int classify(int n) {\n"
              + "        if (n < 0) { return -1; }\n"
              + "        if (n == 0) { return 0; }\n"
              + "        return 1;\n"
              + "    }\n"
              + "}\n");

        // A javax.servlet under test: the agent's inbound advice reads the baggage header off servlet
        // requests and activates the per-test context (a plain HttpServer is NOT hooked).
        write(consumer.resolve("src/test/java/com/consumer/CalcServlet.java"),
                "package com.consumer;\n"
              + "import java.io.IOException;\n"
              + "import javax.servlet.http.*;\n"
              + "public class CalcServlet extends HttpServlet {\n"
              + "    private final Calc calc = new Calc();\n"
              + "    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {\n"
              + "        calc.classify(Integer.parseInt(req.getParameter(\"n\")));\n"
              + "        resp.setStatus(200); resp.getWriter().write(\"ok\");\n"
              + "    }\n"
              + "}\n");

        // In-JVM black-box test: embedded Jetty serves CalcServlet; the testkit opens/closes the
        // boundary and the request carries the baggage header so the agent attributes Calc to T1.
        write(consumer.resolve("src/test/java/com/consumer/CalcCoverageTest.java"),
                "package com.consumer;\n"
              + "import io.pjacoco.testkit.Pjacoco;\n"
              + "import java.net.*;\n"
              + "import org.eclipse.jetty.server.Server;\n"
              + "import org.eclipse.jetty.servlet.ServletHandler;\n"
              + "import org.junit.jupiter.api.*;\n"
              + "import static org.junit.jupiter.api.Assertions.*;\n"
              + "class CalcCoverageTest {\n"
              + "    static Server server; static int appPort;\n"
              + "    @BeforeAll static void up() throws Exception {\n"
              + "        server = new Server(0);\n"
              + "        ServletHandler h = new ServletHandler();\n"
              + "        h.addServletWithMapping(CalcServlet.class, \"/run\");\n"
              + "        server.setHandler(h); server.start();\n"
              + "        appPort = server.getURI().getPort();\n"
              + "    }\n"
              + "    @AfterAll static void down() throws Exception { server.stop(); }\n"
              + "    @Test void routesCoverage() throws Exception {\n"
              + "        assertTrue(Pjacoco.enabled(), \"plugin must set -Dpjacoco.control-url\");\n"
              + "        Pjacoco.start(\"T1\", null);\n"
              + "        HttpURLConnection c = (HttpURLConnection) new URL(\"http://127.0.0.1:\" + appPort + \"/run?n=5\").openConnection();\n"
              + "        c.setRequestProperty(\"baggage\", \"test.id=T1\");\n"
              + "        assertEquals(200, c.getResponseCode());\n"
              + "        Pjacoco.stop(\"T1\", \"passed\");\n"
              + "    }\n"
              + "}\n");

        BuildResult result = GradleRunner.create()
                .withProjectDir(consumer.toFile())
                .withPluginClasspath()
                .withArguments("test", "--stacktrace")
                .build();

        assertTrue(result.task(":test").getOutcome() == TaskOutcome.SUCCESS, "consumer :test must pass");

        Path exec = consumer.resolve("build/pjacoco/T1.exec");
        Path sidecar = consumer.resolve("build/pjacoco/T1.json");
        assertTrue(Files.exists(exec) && Files.size(exec) > 0, "plugin-attached agent must produce T1.exec");
        assertTrue(Files.exists(sidecar), "sidecar T1.json must be produced");
        String json = new String(Files.readAllBytes(sidecar), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"classCount\":1"),
                "T1 must have recorded exactly Calc's coverage (classCount=1); was: " + json);
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }
}
