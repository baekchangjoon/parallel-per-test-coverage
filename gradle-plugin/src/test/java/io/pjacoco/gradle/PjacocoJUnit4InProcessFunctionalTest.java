package io.pjacoco.gradle;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * AC-IP2: JUnit 4 in-process per-test coverage. Build A exercises the agent-side zero-touch path
 * (ParentRunner.runLeaf weave; no @Rule; result="unknown"), plus the @Parameterized (distinct .exec
 * per set) and @Test(timeout) (no .exec — FailOnTimeout runs the body on a new thread) cases. Build B
 * sets junit4Auto=false and exercises the explicit @Rule (result="passed") and confirms a no-@Rule
 * test then produces no .exec. JUnit 4 runs via the Vintage engine.
 */
class PjacocoJUnit4InProcessFunctionalTest {

    @Test
    void zeroTouchAgentPath_parameterized_andTimeout(@TempDir Path consumer) throws IOException {
        Path repo = prepareRepo(consumer);
        write(consumer.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"\n");
        write(consumer.resolve("build.gradle.kts"), buildScript(repo, 6342, /* junit4Auto = */ true));

        sutClasses(consumer);
        // (a) zero-touch: NO @Rule — bracketed by the agent-side runLeaf weave.
        write(consumer.resolve("src/test/java/com/consumer/ZeroTouchTest.java"),
                "package com.consumer;\n"
              + "import org.junit.Test;\n"
              + "import static org.junit.Assert.*;\n"
              + "public class ZeroTouchTest {\n"
              + "    @Test public void coversA() { assertEquals(1, new SutA().f(5)); }\n"
              + "}\n");
        // (b) @Parameterized: two parameter sets -> Description.getMethodName() = run[0]/run[1].
        write(consumer.resolve("src/test/java/com/consumer/ParamTest.java"),
                "package com.consumer;\n"
              + "import java.util.*;\n"
              + "import org.junit.Test;\n"
              + "import org.junit.runner.RunWith;\n"
              + "import org.junit.runners.Parameterized;\n"
              + "import org.junit.runners.Parameterized.Parameters;\n"
              + "@RunWith(Parameterized.class)\n"
              + "public class ParamTest {\n"
              + "    @Parameters public static Collection<Object[]> data() { return Arrays.asList(new Object[]{1}, new Object[]{2}); }\n"
              + "    private final int n;\n"
              + "    public ParamTest(int n) { this.n = n; }\n"
              + "    @Test public void run() { new SutB().g(n); }\n"
              + "}\n");
        // (c) @Test(timeout): FailOnTimeout runs the body on a NEW thread -> no context -> empty -> no .exec.
        write(consumer.resolve("src/test/java/com/consumer/TimeoutTest.java"),
                "package com.consumer;\n"
              + "import org.junit.Test;\n"
              + "public class TimeoutTest {\n"
              + "    @Test(timeout = 2000) public void coversTimeout() { new SutA().f(7); }\n"
              + "}\n");

        BuildResult result = run(consumer);
        assertTrue(result.task(":test").getOutcome() == TaskOutcome.SUCCESS, "consumer :test must pass");
        Path out = consumer.resolve("build/pjacoco");

        // Agent-side zero-touch: per-test .exec with result=unknown.
        String zt = read(out.resolve("com.consumer.ZeroTouchTest#coversA.json"));
        assertTrue(zt.contains("\"classCount\":1"), "zero-touch must record SutA; was: " + zt);
        assertTrue(zt.contains("\"result\":\"unknown\""), "agent-side path uses result=unknown; was: " + zt);

        // @Parameterized: a distinct .exec per parameter set (run[0], run[1]).
        assertTrue(Files.exists(out.resolve("com.consumer.ParamTest#run[0].exec")), "param set 0 must have its own .exec");
        assertTrue(Files.exists(out.resolve("com.consumer.ParamTest#run[1].exec")), "param set 1 must have its own .exec");

        // @Test(timeout): empty store guard -> no .exec (documented limitation, not a failure).
        assertFalse(Files.exists(out.resolve("com.consumer.TimeoutTest#coversTimeout.exec")),
                "a @Test(timeout) test runs on a new thread -> no per-test .exec");
    }

    @Test
    void junit4AutoFalse_disablesAgentPath_butRuleStillWorks(@TempDir Path consumer) throws IOException {
        Path repo = prepareRepo(consumer);
        write(consumer.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"\n");
        write(consumer.resolve("build.gradle.kts"), buildScript(repo, 6343, /* junit4Auto = */ false));

        sutClasses(consumer);
        // Explicit @Rule still produces coverage (result=passed) even with junit4Auto=false.
        write(consumer.resolve("src/test/java/com/consumer/RuleTest.java"),
                "package com.consumer;\n"
              + "import io.pjacoco.testkit.junit4.PjacocoInProcessRule;\n"
              + "import org.junit.Rule;\n"
              + "import org.junit.Test;\n"
              + "import static org.junit.Assert.*;\n"
              + "public class RuleTest {\n"
              + "    @Rule public final PjacocoInProcessRule pjacoco = new PjacocoInProcessRule();\n"
              + "    @Test public void coversB() { assertEquals(2, new SutB().g(5)); }\n"
              + "}\n");
        // No @Rule + junit4Auto=false -> the agent-side path is OFF -> no .exec.
        write(consumer.resolve("src/test/java/com/consumer/NoRuleTest.java"),
                "package com.consumer;\n"
              + "import org.junit.Test;\n"
              + "import static org.junit.Assert.*;\n"
              + "public class NoRuleTest {\n"
              + "    @Test public void coversA() { assertEquals(1, new SutA().f(5)); }\n"
              + "}\n");

        BuildResult result = run(consumer);
        assertTrue(result.task(":test").getOutcome() == TaskOutcome.SUCCESS, "consumer :test must pass");
        Path out = consumer.resolve("build/pjacoco");

        String rule = read(out.resolve("com.consumer.RuleTest#coversB.json"));
        assertTrue(rule.contains("\"classCount\":1"), "@Rule must record SutB; was: " + rule);
        assertTrue(rule.contains("\"result\":\"passed\""), "@Rule reports pass/fail; was: " + rule);

        assertFalse(Files.exists(out.resolve("com.consumer.NoRuleTest#coversA.exec")),
                "junit4Auto=false must disable the agent-side path (no .exec for a no-@Rule test)");
    }

    // ---- shared scaffolding ----

    private Path prepareRepo(Path consumer) throws IOException {
        String version = System.getProperty("pjacoco.it.version", "1.0.0");
        Path repo = Files.createDirectories(consumer.resolve("flatrepo"));
        Files.copy(Path.of(System.getProperty("pjacoco.it.agentJar")), repo.resolve("pjacoco-agent-" + version + ".jar"));
        Files.copy(Path.of(System.getProperty("pjacoco.it.testkitJar")), repo.resolve("pjacoco-testkit-core-" + version + ".jar"));
        Files.copy(Path.of(System.getProperty("pjacoco.it.testkitJunit4Jar")), repo.resolve("pjacoco-testkit-junit4-" + version + ".jar"));
        return repo;
    }

    private static String buildScript(Path repo, int port, boolean junit4Auto) {
        String version = System.getProperty("pjacoco.it.version", "1.0.0");
        String junit4AutoLine = junit4Auto ? "" : "    junit4Auto.set(false)\n";
        return "plugins { java; id(\"io.pjacoco.gradle\") }\n"
              + "repositories { mavenCentral(); flatDir { dirs(\"" + repo.toUri().getPath() + "\") } }\n"
              + "dependencies {\n"
              + "    testImplementation(\"io.pjacoco:pjacoco-testkit-junit4:" + version + "\")\n"
              + "    testImplementation(\"io.pjacoco:pjacoco-testkit-core:" + version + "\")\n"
              + "    testImplementation(\"junit:junit:4.13.2\")\n"
              + "    testRuntimeOnly(\"org.junit.vintage:junit-vintage-engine:5.10.3\")\n"
              + "    testRuntimeOnly(\"org.junit.platform:junit-platform-launcher\")\n"
              + "}\n"
              + "pjacoco {\n"
              + "    agentVersion.set(\"" + version + "\")\n"
              + "    port.set(" + port + ")\n"
              + "    includes.set(listOf(\"com.consumer.SutA\", \"com.consumer.SutB\"))\n"
              + "    attachTo.set(listOf(\"test\"))\n"
              + junit4AutoLine
              + "}\n"
              + "tasks.test { useJUnitPlatform() }\n";
    }

    private void sutClasses(Path consumer) throws IOException {
        write(consumer.resolve("src/main/java/com/consumer/SutA.java"),
                "package com.consumer;\npublic class SutA { public int f(int n) { if (n < 0) return -1; return 1; } }\n");
        write(consumer.resolve("src/main/java/com/consumer/SutB.java"),
                "package com.consumer;\npublic class SutB { public int g(int n) { if (n == 0) return 0; return 2; } }\n");
    }

    private static BuildResult run(Path consumer) {
        return GradleRunner.create()
                .withProjectDir(consumer.toFile())
                .withPluginClasspath()
                .withArguments("test", "--stacktrace")
                .build();
    }

    private static String read(Path p) throws IOException {
        assertTrue(Files.exists(p), "expected sidecar: " + p);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }
}
