package io.pjacoco.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AC-IP1 (isolation), AC-IP5 (aggregate), AC-IP6 (auto-registration): a real consumer build runs a
 * PURE unit suite (no servlet/HTTP) in parallel; each test calls a DIFFERENT SUT class directly on
 * the test thread. Proves the in-process path routes per-test (mutual exclusion), defaults the
 * whole-run aggregate ON, and works with NO {@code @ExtendWith} (services-file auto-registration).
 */
class PjacocoInProcessFunctionalTest {

    static final int PORT = 6341;

    @Test
    void pureUnitTestsGetIsolatedPerTestCoverageAndAggregate(@TempDir Path consumer) throws IOException {
        BuildResult result = writeAndRunConsumer(consumer, /* aggregateOff = */ false);

        assertTrue(result.task(":test").getOutcome() == TaskOutcome.SUCCESS, "consumer :test must pass");

        Path out = consumer.resolve("build/pjacoco");
        // testId is FQN#method (PjacocoInProcessExtension uses getName()). Read the .exec (a vanilla
        // jacoco file) to prove WHICH class each test recorded — classCount=1 alone wouldn't rule out
        // both tests recording the same class.
        Map<String, boolean[]> a = execProbes(out.resolve("com.consumer.SutTest#coversA.exec"));
        Map<String, boolean[]> b = execProbes(out.resolve("com.consumer.SutTest#coversB.exec"));

        // AC-IP1 mutual exclusion: coversA holds exactly SutA (not SutB); coversB exactly SutB (not SutA).
        assertEquals(Collections.singleton("com/consumer/SutA"), a.keySet(), "coversA must record only SutA");
        assertEquals(Collections.singleton("com/consumer/SutB"), b.keySet(), "coversB must record only SutB");

        // AC-IP5: aggregate defaults ON -> aggregate.exec exists, holds BOTH SUT classes, and is a
        // probe-level SUPERSET of every per-test .exec (jacoco's always-populated base layer).
        Map<String, boolean[]> agg = execProbes(out.resolve("aggregate.exec"));
        assertTrue(agg.containsKey("com/consumer/SutA") && agg.containsKey("com/consumer/SutB"),
                "aggregate must contain both SUT classes; was: " + agg.keySet());
        assertSuperset(agg, a);
        assertSuperset(agg, b);

        // AC-IP6: a clean in-process-only run emits no stop-missing warning.
        assertFalse(result.getOutput().contains("stop-missing"), "clean in-process run must not warn stop-missing");
    }

    /** AC-IP5 negative: with {@code aggregate=false} the aggregate file is absent; per-test .exec remain. */
    @Test
    void aggregateFalseProducesNoAggregateFile(@TempDir Path consumer) throws IOException {
        BuildResult result = writeAndRunConsumer(consumer, /* aggregateOff = */ true);
        assertTrue(result.task(":test").getOutcome() == TaskOutcome.SUCCESS, "consumer :test must pass");
        Path out = consumer.resolve("build/pjacoco");
        assertFalse(Files.exists(out.resolve("aggregate.exec")), "aggregate=false must write no aggregate.exec");
        assertTrue(Files.exists(out.resolve("com.consumer.SutTest#coversA.exec")), "per-test .exec must still exist");
    }

    /** Writes the consumer project (two distinct SUT classes, no @ExtendWith) and runs its :test.
     *  When {@code aggregateOff}, adds {@code aggregate.set(false)} to the pjacoco block. */
    private BuildResult writeAndRunConsumer(Path consumer, boolean aggregateOff) throws IOException {
        String version = System.getProperty("pjacoco.it.version", "1.0.0");
        Path repo = Files.createDirectories(consumer.resolve("flatrepo"));
        Files.copy(Path.of(System.getProperty("pjacoco.it.agentJar")), repo.resolve("pjacoco-agent-" + version + ".jar"));
        Files.copy(Path.of(System.getProperty("pjacoco.it.testkitJar")), repo.resolve("pjacoco-testkit-core-" + version + ".jar"));
        Files.copy(Path.of(System.getProperty("pjacoco.it.testkitJunit5Jar")), repo.resolve("pjacoco-testkit-junit5-" + version + ".jar"));

        String aggregateLine = aggregateOff ? "    aggregate.set(false)\n" : "";
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
              + "    testImplementation(\"io.pjacoco:pjacoco-testkit-junit5:" + version + "\")\n"
              + "    testImplementation(\"io.pjacoco:pjacoco-testkit-core:" + version + "\")\n"
              + "    testImplementation(\"org.junit.jupiter:junit-jupiter:5.10.3\")\n"
              + "    testRuntimeOnly(\"org.junit.platform:junit-platform-launcher\")\n"
              + "}\n"
              + "pjacoco {\n"
              + "    agentVersion.set(\"" + version + "\")\n"
              + "    port.set(" + PORT + ")\n"
              + "    includes.set(listOf(\"com.consumer.SutA\", \"com.consumer.SutB\"))\n"
              + "    attachTo.set(listOf(\"test\"))\n"
              + aggregateLine
              + "}\n"
              + "tasks.test {\n"
              + "    useJUnitPlatform()\n"
              + "    systemProperty(\"junit.jupiter.execution.parallel.enabled\", \"true\")\n"
              + "    systemProperty(\"junit.jupiter.execution.parallel.mode.default\", \"concurrent\")\n"
              + "}\n");

        // Two DISTINCT SUT classes (Java 11+ bytecode -> Condy path on JDK 11/17).
        write(consumer.resolve("src/main/java/com/consumer/SutA.java"),
                "package com.consumer;\npublic class SutA { public int f(int n) { if (n < 0) return -1; return 1; } }\n");
        write(consumer.resolve("src/main/java/com/consumer/SutB.java"),
                "package com.consumer;\npublic class SutB { public int g(int n) { if (n == 0) return 0; return 2; } }\n");

        // NO @ExtendWith — relies on services-file auto-registration (AC-IP6). Each test calls a
        // different SUT directly on the test thread.
        write(consumer.resolve("src/test/java/com/consumer/SutTest.java"),
                "package com.consumer;\n"
              + "import org.junit.jupiter.api.*;\n"
              + "import static org.junit.jupiter.api.Assertions.*;\n"
              + "class SutTest {\n"
              + "    @Test void coversA() { assertEquals(1, new SutA().f(5)); }\n"
              + "    @Test void coversB() { assertEquals(2, new SutB().g(5)); }\n"
              + "}\n");

        return GradleRunner.create()
                .withProjectDir(consumer.toFile())
                .withPluginClasspath()
                .withArguments("test", "--stacktrace")
                .build();
    }

    /** Reads a vanilla jacoco .exec into VM-class-name -> probe array (merging if a class repeats). */
    private static Map<String, boolean[]> execProbes(Path exec) throws IOException {
        assertTrue(Files.exists(exec), "expected .exec: " + exec);
        Map<String, boolean[]> out = new java.util.HashMap<>();
        try (java.io.InputStream in = new java.io.BufferedInputStream(Files.newInputStream(exec))) {
            org.jacoco.core.data.ExecutionDataReader r = new org.jacoco.core.data.ExecutionDataReader(in);
            r.setSessionInfoVisitor(info -> { });
            r.setExecutionDataVisitor(d -> out.put(d.getName(), d.getProbes()));
            r.read();
        }
        return out;
    }

    /** Asserts every probe set in {@code sub} is also set in {@code sup} (probe-level superset). */
    private static void assertSuperset(Map<String, boolean[]> sup, Map<String, boolean[]> sub) {
        for (Map.Entry<String, boolean[]> e : sub.entrySet()) {
            boolean[] s = e.getValue();
            boolean[] g = sup.get(e.getKey());
            assertTrue(g != null, "aggregate missing class " + e.getKey());
            for (int i = 0; i < s.length; i++) {
                if (s[i]) {
                    assertTrue(i < g.length && g[i], "aggregate must cover probe " + i + " of " + e.getKey());
                }
            }
        }
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }
}
