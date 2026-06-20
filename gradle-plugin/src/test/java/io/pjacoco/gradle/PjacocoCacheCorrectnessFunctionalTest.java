package io.pjacoco.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P4-A: Gradle's up-to-date check must account for pjacoco's coverage output and options. Before the
 * fix, {@code AgentArgs} carried no {@code @Input}/{@code @Classpath} inputs and {@code build/pjacoco}
 * was not a declared {@code Test} output, so deleting the {@code .exec} (or changing a pjacoco option)
 * left {@code :test} {@code UP-TO-DATE} — silently producing empty/stale coverage. Vanilla JaCoCo binds
 * the exec to the task outputs and does not have this problem.
 */
class PjacocoCacheCorrectnessFunctionalTest {

    @Test
    void deletingCoverageOutputReExecutesTestTask(@TempDir Path consumer) throws IOException {
        writeConsumer(consumer, /*ephemeral port — these tests don't use the control endpoint*/ 0, "com.consumer.*");
        Path aggregate = consumer.resolve("build/pjacoco/aggregate.exec");

        BuildResult run1 = run(consumer);
        assertEquals(TaskOutcome.SUCCESS, run1.task(":test").getOutcome());
        assertTrue(Files.exists(aggregate), "run1 must produce the coverage output");

        // Delete only the coverage output, then re-run WITHOUT changing sources.
        deleteRecursively(consumer.resolve("build/pjacoco"));
        BuildResult run2 = run(consumer);

        assertNotEquals(TaskOutcome.UP_TO_DATE, run2.task(":test").getOutcome(),
                ":test must re-run when its declared coverage output was deleted (not stay UP-TO-DATE)");
        assertTrue(Files.exists(aggregate),
                "deleted coverage output must be regenerated on re-run, not silently left missing");
    }

    @Test
    void changingPjacocoOptionsReExecutesTestTask(@TempDir Path consumer) throws IOException {
        writeConsumer(consumer, 0, "com.consumer.*");
        BuildResult run1 = run(consumer);
        assertEquals(TaskOutcome.SUCCESS, run1.task(":test").getOutcome());

        // Change a pjacoco option (includes) with no source change; the agent args feed the test JVM,
        // so the task must be considered out-of-date.
        writeConsumer(consumer, 0, "com.consumer.Calc");
        BuildResult run2 = run(consumer);

        assertNotEquals(TaskOutcome.UP_TO_DATE, run2.task(":test").getOutcome(),
                ":test must re-run when a pjacoco option (includes) changed");
    }

    private static BuildResult run(Path consumer) {
        return GradleRunner.create()
                .withProjectDir(consumer.toFile())
                .withPluginClasspath()
                .withArguments("test", "--stacktrace")
                .build();
    }

    private static void writeConsumer(Path consumer, int port, String includes) throws IOException {
        String version = ItSupport.itVersion();
        Path repo = Files.createDirectories(consumer.resolve("flatrepo"));
        Files.copy(Path.of(System.getProperty("pjacoco.it.agentJar")),
                repo.resolve("pjacoco-agent-" + version + ".jar"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

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
              + "    testImplementation(\"org.junit.jupiter:junit-jupiter:5.10.3\")\n"
              + "    testRuntimeOnly(\"org.junit.platform:junit-platform-launcher\")\n"
              + "}\n"
              + "pjacoco {\n"
              + "    agentVersion.set(\"" + version + "\")\n"
              + "    port.set(" + port + ")\n"
              + "    includes.set(listOf(\"" + includes + "\"))\n"
              + "    attachTo.set(listOf(\"test\"))\n"
              + "}\n"
              + "tasks.test { useJUnitPlatform() }\n");

        write(consumer.resolve("src/main/java/com/consumer/Calc.java"),
                "package com.consumer;\n"
              + "public class Calc {\n"
              + "    public int classify(int n) { return n < 0 ? -1 : (n == 0 ? 0 : 1); }\n"
              + "}\n");
        write(consumer.resolve("src/test/java/com/consumer/CalcTest.java"),
                "package com.consumer;\n"
              + "import org.junit.jupiter.api.Test;\n"
              + "import static org.junit.jupiter.api.Assertions.*;\n"
              + "class CalcTest {\n"
              + "    @Test void classifies() { assertEquals(1, new Calc().classify(5)); }\n"
              + "}\n");
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException e) { throw new RuntimeException(e); }
            });
        }
    }
}
