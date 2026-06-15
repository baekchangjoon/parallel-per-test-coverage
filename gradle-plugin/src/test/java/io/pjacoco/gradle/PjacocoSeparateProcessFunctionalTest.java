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
 * AC6: the separate-process path. A consumer build launches the SUT in its OWN JVM via {@code JavaExec},
 * wiring the plugin's exposed {@code pjacoco.agentJvmArg} (+ {@code controlUrlArg}) onto that JVM — NOT
 * the test JVM. The launched program starts an embedded servlet app, opens a per-test boundary, hits
 * itself with the baggage header, and closes it; the agent in that separate JVM writes the per-test
 * {@code .exec}. Proves the documented "wire agentJvmArg onto a separately-launched server" UX works.
 */
class PjacocoSeparateProcessFunctionalTest {

    @Test
    void agentJvmArgWiredOntoASeparateJvmProducesPerTestExec(@TempDir Path consumer) throws IOException {
        String version = System.getProperty("pjacoco.it.version", "1.1.0");
        Path repo = Files.createDirectories(consumer.resolve("flatrepo"));
        Files.copy(Path.of(System.getProperty("pjacoco.it.agentJar")), repo.resolve("pjacoco-agent-" + version + ".jar"));
        Files.copy(Path.of(System.getProperty("pjacoco.it.testkitJar")), repo.resolve("pjacoco-testkit-" + version + ".jar"));

        write(consumer.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"\n");
        write(consumer.resolve("build.gradle.kts"),
                "plugins {\n"
              + "    application\n"
              + "    id(\"io.pjacoco.gradle\")\n"
              + "}\n"
              + "repositories {\n"
              + "    mavenCentral()\n"
              + "    flatDir { dirs(\"" + repo.toUri().getPath() + "\") }\n"
              + "}\n"
              + "dependencies {\n"
              + "    implementation(\"io.pjacoco:pjacoco-testkit:" + version + "\")\n"
              + "    implementation(\"org.eclipse.jetty:jetty-server:9.4.55.v20240627\")\n"
              + "    implementation(\"org.eclipse.jetty:jetty-servlet:9.4.55.v20240627\")\n"
              + "    implementation(\"javax.servlet:javax.servlet-api:3.1.0\")\n"
              + "}\n"
              + "pjacoco {\n"
              + "    port.set(6340)\n"
              + "    includes.set(listOf(\"com.consumer.Calc\"))\n"   // NO attachTo: this JVM is separate
              + "}\n"
              + "val ext = extensions.getByType(io.pjacoco.gradle.PjacocoGradleExtension::class.java)\n"
              + "tasks.register<JavaExec>(\"runWithAgent\") {\n"
              + "    dependsOn(\"classes\")\n"
              + "    classpath = sourceSets[\"main\"].runtimeClasspath\n"
              + "    mainClass.set(\"com.consumer.ScenarioMain\")\n"
              + "    doFirst { jvmArgs(ext.agentJvmArg.get(), ext.controlUrlArg.get()) }\n"
              + "}\n");

        write(consumer.resolve("src/main/java/com/consumer/Calc.java"),
                "package com.consumer;\n"
              + "public class Calc {\n"
              + "    public int classify(int n) {\n"
              + "        if (n < 0) { return -1; }\n"
              + "        if (n == 0) { return 0; }\n"
              + "        return 1;\n"
              + "    }\n"
              + "}\n");

        write(consumer.resolve("src/main/java/com/consumer/CalcServlet.java"),
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

        // Runs in the JavaExec'd JVM (agent attached there). Opens the boundary, self-requests with the
        // baggage header, closes it; the agent flushes SEP1.exec on stop.
        write(consumer.resolve("src/main/java/com/consumer/ScenarioMain.java"),
                "package com.consumer;\n"
              + "import io.pjacoco.testkit.Pjacoco;\n"
              + "import java.net.*;\n"
              + "import org.eclipse.jetty.server.Server;\n"
              + "import org.eclipse.jetty.servlet.ServletHandler;\n"
              + "public class ScenarioMain {\n"
              + "    public static void main(String[] args) throws Exception {\n"
              + "        Server server = new Server(0);\n"
              + "        ServletHandler h = new ServletHandler();\n"
              + "        h.addServletWithMapping(CalcServlet.class, \"/run\");\n"
              + "        server.setHandler(h); server.start();\n"
              + "        int port = server.getURI().getPort();\n"
              + "        if (!Pjacoco.enabled()) { throw new IllegalStateException(\"control-url not set on the separate JVM\"); }\n"
              + "        Pjacoco.start(\"SEP1\", null);\n"
              + "        HttpURLConnection c = (HttpURLConnection) new URL(\"http://127.0.0.1:\" + port + \"/run?n=5\").openConnection();\n"
              + "        c.setRequestProperty(\"baggage\", \"test.id=SEP1\");\n"
              + "        if (c.getResponseCode() != 200) { throw new IllegalStateException(\"app call failed\"); }\n"
              + "        Pjacoco.stop(\"SEP1\", \"passed\");\n"   // flushes SEP1.exec synchronously
              + "        server.stop();\n"
              + "        System.exit(0);\n"                          // don't let Jetty threads keep the JVM (+ its port) alive
              + "    }\n"
              + "}\n");

        BuildResult result = GradleRunner.create()
                .withProjectDir(consumer.toFile())
                .withPluginClasspath()
                .withArguments("runWithAgent", "--stacktrace")
                .build();

        assertTrue(result.task(":runWithAgent").getOutcome() == TaskOutcome.SUCCESS, "runWithAgent must pass");

        Path exec = consumer.resolve("build/pjacoco/SEP1.exec");
        Path sidecar = consumer.resolve("build/pjacoco/SEP1.json");
        assertTrue(Files.exists(exec) && Files.size(exec) > 0,
                "the separately-launched JVM (agentJvmArg) must produce SEP1.exec");
        String json = new String(Files.readAllBytes(sidecar), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"classCount\":1"), "SEP1 must have recorded Calc; was: " + json);
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }
}
