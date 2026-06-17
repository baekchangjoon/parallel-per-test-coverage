# In-Process Per-Test Activation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let pure in-JVM unit tests (SUT called directly on the test thread, no servlet boundary) get per-test + parallel coverage, add an always-on whole-run aggregate `.exec`, and make both JUnit 5 (auto-registration) and JUnit 4 (agent-side `runLeaf`) zero-touch — all without changing the existing servlet path or the `.exec` format.

**Architecture:** A new stable agent API `io.pjacoco.agent.api.CoverageControl` sets/clears the per-thread `CoverageContext` (the same seam `ServletAdvice` uses today), reached from the testkit reflectively across the classloader boundary via `InProcessBridge`. The agent's global jacoco `RuntimeData` is retained and dumped once at shutdown as the aggregate. A new `JUnit4InboundActivator` weaves `ParentRunner.runLeaf` for JUnit 4 zero-touch; a JUnit 5 services file + the plugin's `autodetection.enabled` give JUnit 5 zero-touch.

**Tech Stack:** Java 8 (agent + testkit), ByteBuddy advice, jacoco-core (`RuntimeData`/`ExecutionDataWriter`), JUnit 5/4, Gradle (`java-gradle-plugin` + GradleRunner functional tests), Maven plugin.

**Source of truth:** `docs/superpowers/specs/2026-06-16-in-process-activation-design.md` (§3–§9, AC-IP1–IP6).

---

## File Structure

**New files**

- `agent/src/main/java/io/pjacoco/agent/api/CoverageControl.java` — stable in-JVM activation API (not relocated).
- `agent/src/main/java/io/pjacoco/agent/output/AggregateWriter.java` — whole-run `.exec` writer from `RuntimeData`.
- `agent/src/main/java/io/pjacoco/agent/inbound/junit4/JUnit4InboundActivator.java` — installs the `runLeaf` advice.
- `agent/src/main/java/io/pjacoco/agent/inbound/junit4/RunLeafAdvice.java` — the woven advice (reflective `Description`).
- `testkit-core/src/main/java/io/pjacoco/testkit/inprocess/InProcessBridge.java` — reflective best-effort caller.
- `testkit-junit5/src/main/java/io/pjacoco/testkit/junit5/PjacocoInProcessExtension.java`
- `testkit-junit5/src/main/resources/META-INF/services/org.junit.jupiter.api.extension.Extension`
- `testkit-junit4/src/main/java/io/pjacoco/testkit/junit4/PjacocoInProcessRule.java`

**Modified files**

- `agent/src/main/java/io/pjacoco/agent/store/TestStoreRegistry.java` — add `peek` + `discard`.
- `agent/src/main/java/io/pjacoco/agent/probe/ProbeInstrumentation.java` — `install` retains + returns `RuntimeData`.
- `agent/src/main/java/io/pjacoco/agent/AgentOptions.java` — `aggregate`/`aggregateFile`/`junit4Auto`.
- `agent/src/main/java/io/pjacoco/agent/Bootstrap.java` — bind registry, retain `RuntimeData`, aggregate dump, JUnit4 activator.
- `agent/build.gradle.kts` — relocation guard note (no behavior change; guard test lives in agent test).
- `gradle-plugin/src/main/java/io/pjacoco/gradle/PjacocoArgs.java` — append aggregate/junit4 opts.
- `gradle-plugin/src/main/java/io/pjacoco/gradle/PjacocoGradleExtension.java` — `autoDetectExtensions`/`aggregate`/`aggregateFile`/`junit4Auto`.
- `gradle-plugin/src/main/java/io/pjacoco/gradle/PjacocoPlugin.java` — conventions + `autodetection.enabled` injection.
- `gradle-plugin/build.gradle.kts` — also serve the junit5/junit4 testkit jars to functional consumers.
- `maven-plugin/src/main/java/io/pjacoco/maven/PrepareAgentMojo.java` — aggregate/aggregateFile/junit4Auto params.
- `samples/gradle-sample/build.gradle.kts` + new `unitTest` source set — the pure-unit consumer sample.
- `README.md` — Scope + testkit usage + aggregate + zero-touch + limitations.

**New test files**

- `agent/src/test/java/io/pjacoco/agent/api/CoverageControlTest.java` (AC-IP3)
- `agent/src/test/java/io/pjacoco/agent/output/AggregateWriterTest.java`
- `agent/src/test/java/io/pjacoco/agent/store/TestStoreRegistryDiscardTest.java`
- `agent/src/test/java/io/pjacoco/agent/AgentOptionsInProcessTest.java`
- `agent/src/test/java/io/pjacoco/agent/inbound/junit4/RunLeafAdviceTest.java`
- `agent/src/test/java/io/pjacoco/agent/ShadedJarContainsApiTest.java` (AC-IP4)
- `testkit-core/src/test/java/io/pjacoco/testkit/inprocess/InProcessBridgeTest.java`
- `gradle-plugin/src/test/java/io/pjacoco/gradle/PjacocoArgsInProcessTest.java`
- `gradle-plugin/src/test/java/io/pjacoco/gradle/PjacocoInProcessFunctionalTest.java` (AC-IP1/IP5/IP6)
- `gradle-plugin/src/test/java/io/pjacoco/gradle/PjacocoJUnit4InProcessFunctionalTest.java` (AC-IP2)

---

## Conventions for every task

- One assertion-focused test per behavior. Run the named test, see it fail for the **stated** reason, implement minimally, see it pass.
- Build from the worktree root. Gradle module tests: `./gradlew :<module>:test --tests '<FQN>'`.
- Commit after each green task with the shown message. Commit messages end with the project's `Co-Authored-By` trailer.

---

## Phase A — Outer loop: author acceptance tests (RED)

These are authored first and are expected to FAIL until the feature exists. Do **not** weaken them to pass early.

### Task 1: AC-IP4 — shaded jar exposes the API (build guard)

**Files:**
- Test: `agent/src/test/java/io/pjacoco/agent/ShadedJarContainsApiTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Wire the shaded-jar path into the `test` task so the guard runs against the real artifact**

In `agent/build.gradle.kts`, inside `tasks.test { ... }` (the block that calls `useJUnitPlatform()`), add a dependency on `shadowJar` and pass its path. Replace:

```kotlin
tasks.test { useJUnitPlatform() }
```

with:

```kotlin
tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.shadowJar)
    val shadedJar = tasks.shadowJar.flatMap { it.archiveFile }
    doFirst { systemProperty("pjacoco.shadedJar", shadedJar.get().asFile.absolutePath) }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :agent:test --tests 'io.pjacoco.agent.ShadedJarContainsApiTest'`
Expected: FAIL — `CoverageControl.class must be present` (the class does not exist yet, so the shaded jar lacks it).

- [ ] **Step 4: Commit the red acceptance test**

```bash
git add agent/src/test/java/io/pjacoco/agent/ShadedJarContainsApiTest.java agent/build.gradle.kts
git commit -m "test(agent): AC-IP4 shaded-jar API-presence guard (red)"
```

### Task 2: Serve the JUnit 5/4 testkit jars to functional consumers

The in-process functional tests need the **testkit-junit5** and **testkit-junit4** jars (with the new extension/rule + services file) served via the flatDir repo, like the existing test serves `testkit-core`.

**Files:**
- Modify: `gradle-plugin/build.gradle.kts`

- [ ] **Step 1: Add the jar providers and system properties**

In `gradle-plugin/build.gradle.kts`, after the existing `val testkitJar = project(":testkit-core").tasks.named("jar")` line, add:

```kotlin
evaluationDependsOn(":testkit-junit5")
evaluationDependsOn(":testkit-junit4")
val testkitJunit5Jar = project(":testkit-junit5").tasks.named("jar")
val testkitJunit4Jar = project(":testkit-junit4").tasks.named("jar")
```

Then inside `tasks.test { ... }`, after the existing three `systemProperty("pjacoco.it.*", ...)` lines, add:

```kotlin
dependsOn(testkitJunit5Jar, testkitJunit4Jar)
systemProperty("pjacoco.it.testkitJunit5Jar", testkitJunit5Jar.get().outputs.files.singleFile.absolutePath)
systemProperty("pjacoco.it.testkitJunit4Jar", testkitJunit4Jar.get().outputs.files.singleFile.absolutePath)
```

Also add jacoco-core to the plugin's **test** dependencies (the functional tests read the produced `.exec` files to verify class identity + the aggregate superset). In the `dependencies { ... }` block add:

```kotlin
    testImplementation("org.jacoco:org.jacoco.core:0.8.12")
```

- [ ] **Step 2: Verify the build still configures**

Run: `./gradlew :gradle-plugin:help -q`
Expected: BUILD SUCCESSFUL (no compile/config error).

- [ ] **Step 3: Commit**

```bash
git add gradle-plugin/build.gradle.kts
git commit -m "build(gradle-plugin): serve junit5/junit4 testkit jars to functional consumers"
```

### Task 3: AC-IP1 + AC-IP5 + AC-IP6 — pure-unit in-process functional test (RED)

This is the headline E2E: a real consumer build, JUnit 5 **parallel**, two **distinct** SUT classes, asserting mutual exclusion from the per-test `.exec` sidecars, the whole-run `aggregate.exec` superset, and the no-`@ExtendWith` auto-registration path.

**Files:**
- Test: `gradle-plugin/src/test/java/io/pjacoco/gradle/PjacocoInProcessFunctionalTest.java`

- [ ] **Step 1: Write the failing functional test**

```java
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
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :gradle-plugin:test --tests 'io.pjacoco.gradle.PjacocoInProcessFunctionalTest'`
Expected: FAIL — the consumer `:test` fails (the `pjacoco {}` block has no `autoDetectExtensions`/`aggregate` yet, the services file / extension don't exist, so no per-test `.exec` and no `aggregate.exec`).

- [ ] **Step 3: Commit the red acceptance test**

```bash
git add gradle-plugin/src/test/java/io/pjacoco/gradle/PjacocoInProcessFunctionalTest.java
git commit -m "test(gradle-plugin): AC-IP1/IP5/IP6 pure-unit in-process functional test (red)"
```

### Task 4: AC-IP2 — JUnit 4 zero-touch + @Rule functional test (RED)

**Files:**
- Test: `gradle-plugin/src/test/java/io/pjacoco/gradle/PjacocoJUnit4InProcessFunctionalTest.java`

> **Why two consumer builds (not one):** the spec (§7c) says *do not* combine the agent-side `runLeaf`
> weave with the `@Rule` on the same test. If you did, the rule's `finished` flushes the store first
> (`result=passed`), then the agent-side `@OnMethodExit` finds `peek==null` and emits a harmless
> `stop-missing` warning. To verify each path cleanly — and to cover the spec's AC-IP2 negatives
> (`junit4Auto=false` disables the agent path; `@Parameterized` distinct `.exec`; `@Test(timeout)`
> empty) — we use **two** consumer builds:
> - **Build A (zero-touch, `junit4Auto` default on, no `@Rule`):** a plain test → `result=unknown`; a
>   `@Parameterized` test → a distinct `.exec` per parameter set; a `@Test(timeout)` test → no `.exec`.
> - **Build B (`junit4Auto=false`):** a `@Rule` test → `result=passed`; a no-`@Rule` test → no `.exec`
>   (proving `junit4Auto=false` disabled the agent-side path).

- [ ] **Step 1: Write the failing functional test**

```java
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
```

> **Note:** the `@Parameterized` sidecar method name is `run[0]`/`run[1]` (JUnit 4
> `Description.getMethodName()` for parameterized runners includes the `[index]` suffix). If the agent
> emits a different exact form, read the `build/pjacoco` directory listing in the test output and adjust
> the expected file names — the invariant is **two distinct** `.exec`, one per parameter set.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :gradle-plugin:test --tests 'io.pjacoco.gradle.PjacocoJUnit4InProcessFunctionalTest'`
Expected: FAIL — no `JUnit4InboundActivator`, no `PjacocoInProcessRule`, so the consumer build produces no per-test `.exec`.

- [ ] **Step 3: Commit the red acceptance test**

```bash
git add gradle-plugin/src/test/java/io/pjacoco/gradle/PjacocoJUnit4InProcessFunctionalTest.java
git commit -m "test(gradle-plugin): AC-IP2 JUnit4 zero-touch + @Rule functional test (red)"
```

---

## Phase B — Agent core: `CoverageControl` + registry helpers (inner-loop TDD)

### Task 5: `TestStoreRegistry.peek` + `discard`

**Files:**
- Modify: `agent/src/main/java/io/pjacoco/agent/store/TestStoreRegistry.java`
- Test: `agent/src/test/java/io/pjacoco/agent/store/TestStoreRegistryDiscardTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.pjacoco.agent.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestStoreRegistryDiscardTest {

    private TestStoreRegistry newRegistry(Path dir) {
        return new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(),
                false, 1000, () -> 1L);
    }

    @Test
    void peekReturnsRegisteredStoreWithoutSideEffects(@TempDir Path dir) {
        TestStoreRegistry reg = newRegistry(dir);
        reg.start("T1", null, null);
        assertNotNull(reg.peek("T1"), "peek must return the registered store");
        assertNull(reg.peek("MISSING"), "peek must return null for an unknown testId");
    }

    @Test
    void discardRemovesWithoutWritingAnyFile(@TempDir Path dir) {
        TestStoreRegistry reg = newRegistry(dir);
        reg.start("T1", null, null);
        reg.discard("T1");
        assertNull(reg.peek("T1"), "discard must remove the store");
        assertFalse(Files.exists(dir.resolve("T1.exec")), "discard must not write an .exec");
        assertFalse(Files.exists(dir.resolve("T1.json")), "discard must not write a sidecar");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :agent:test --tests 'io.pjacoco.agent.store.TestStoreRegistryDiscardTest'`
Expected: FAIL — `peek`/`discard` are not defined (compile error).

- [ ] **Step 3: Add the two methods**

In `TestStoreRegistry.java`, after the `active(...)` method add:

```java
    /** Non-removing, side-effect-free lookup (for the empty-store guard in CoverageControl). */
    public TestStore peek(String testId) {
        return stores.get(testId);
    }

    /** Remove a store WITHOUT flushing (empty-store guard: an activation that recorded nothing). */
    public synchronized void discard(String testId) {
        stores.remove(testId);
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :agent:test --tests 'io.pjacoco.agent.store.TestStoreRegistryDiscardTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/java/io/pjacoco/agent/store/TestStoreRegistry.java agent/src/test/java/io/pjacoco/agent/store/TestStoreRegistryDiscardTest.java
git commit -m "feat(agent): TestStoreRegistry.peek + discard (no-flush removal)"
```

### Task 6: `CoverageControl` API (AC-IP3)

**Files:**
- Create: `agent/src/main/java/io/pjacoco/agent/api/CoverageControl.java`
- Test: `agent/src/test/java/io/pjacoco/agent/api/CoverageControlTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.pjacoco.agent.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoverageControlTest {

    @AfterEach
    void unbind() {
        CoverageControl.bindRegistry(null);
        CoverageContext.clear();
    }

    private TestStoreRegistry registry(Path dir) {
        return new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(), false, 1000, () -> 1L);
    }

    @Test
    void unboundIsNotReadyAndActivateIsNoOp() {
        assertFalse(CoverageControl.isReady(), "no registry bound -> not ready");
        CoverageControl.activate("T1", null);   // must not throw
        assertNull(CoverageContext.get(), "activate with no registry must not set a context");
        CoverageControl.deactivate("T1", "passed"); // must not throw
    }

    @Test
    void activateSetsContextToTheRegisteredStore(@TempDir Path dir) {
        TestStoreRegistry reg = registry(dir);
        CoverageControl.bindRegistry(reg);
        assertTrue(CoverageControl.isReady());
        CoverageControl.activate("T1", "shardA");
        assertSame(reg.peek("T1"), CoverageContext.get(), "context must be the registered store");
    }

    @Test
    void deactivateFlushesANonEmptyStore(@TempDir Path dir) {
        TestStoreRegistry reg = registry(dir);
        CoverageControl.bindRegistry(reg);
        CoverageControl.activate("T1", null);
        TestStore store = CoverageContext.get();
        store.record(1L, "com/x/A", 0, 1);   // make it non-empty
        CoverageControl.deactivate("T1", "passed");
        assertNull(CoverageContext.get(), "deactivate clears the thread context");
        assertTrue(Files.exists(dir.resolve("T1.exec")), "non-empty store must flush an .exec");
    }

    @Test
    void deactivateDiscardsAnEmptyStoreWithoutWriting(@TempDir Path dir) throws Exception {
        TestStoreRegistry reg = registry(dir);
        CoverageControl.bindRegistry(reg);
        CoverageControl.activate("T1", null);   // nothing recorded -> empty
        CoverageControl.deactivate("T1", "passed");
        assertFalse(Files.exists(dir.resolve("T1.exec")), "empty store must NOT flush an .exec");
        assertNull(reg.peek("T1"), "empty store must be removed");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :agent:test --tests 'io.pjacoco.agent.api.CoverageControlTest'`
Expected: FAIL — `CoverageControl` does not exist (compile error).

- [ ] **Step 3: Implement `CoverageControl`**

```java
package io.pjacoco.agent.api;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;

/**
 * Stable, reflectively-invoked in-JVM activation API. The testkit's {@code InProcessBridge} resolves
 * this class by its exact FQN across the classloader boundary, so the method names and signatures
 * here are a CONTRACT: do not change them without a version bump. NOT relocated by the shadow plugin
 * (see {@code agent/build.gradle.kts}); a build guard asserts its presence in the shaded jar.
 *
 * <p>All methods are best-effort and never throw into test code (mirrors {@code ServletAdvice}).
 * Activation sets the per-thread {@link CoverageContext}; the caller must run
 * activate / test body / deactivate on the SAME thread.
 */
public final class CoverageControl {

    /** Bound once at premain by {@code Bootstrap}; null until then (and in out-of-process JVMs). */
    private static volatile TestStoreRegistry registry;

    private CoverageControl() {}

    /** Wiring: called once from {@code Bootstrap.premain}. */
    public static void bindRegistry(TestStoreRegistry reg) {
        registry = reg;
    }

    /** @return true once the agent has wired the registry (i.e. the agent is installed in this JVM). */
    public static boolean isReady() {
        return registry != null;
    }

    /** Register the per-test store and set it as the active context on the calling thread. */
    public static void activate(String testId, String shardId) {
        try {
            TestStoreRegistry reg = registry;
            if (reg == null || testId == null) {
                return;
            }
            reg.start(testId, shardId, null);
            TestStore store = reg.active(testId);
            if (store != null) {
                CoverageContext.set(store);
            }
            // else: start() was swallowed by the best-effort path; leave context unset (mirrors ServletAdvice).
        } catch (Throwable ignored) {
            // never disturb the test
        }
    }

    /** Clear the thread context and flush the per-test {@code .exec}; an empty store is discarded. */
    public static void deactivate(String testId, String result) {
        try {
            CoverageContext.clear();
            TestStoreRegistry reg = registry;
            if (reg == null || testId == null) {
                return;
            }
            TestStore store = reg.peek(testId);
            if (store != null && store.classCount() == 0) {
                reg.discard(testId);   // empty-store guard: no garbage file (NOT in the shared writer)
            } else {
                reg.stop(testId, result);
            }
        } catch (Throwable ignored) {
            // never disturb the test
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :agent:test --tests 'io.pjacoco.agent.api.CoverageControlTest'`
Expected: PASS (all four tests).

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/java/io/pjacoco/agent/api/CoverageControl.java agent/src/test/java/io/pjacoco/agent/api/CoverageControlTest.java
git commit -m "feat(agent): CoverageControl in-JVM activation API with empty-store guard (AC-IP3)"
```

---

## Phase C — Whole-run aggregate (default ON)

### Task 7: `AgentOptions` — `aggregate` / `aggregateFile` / `junit4Auto`

**Files:**
- Modify: `agent/src/main/java/io/pjacoco/agent/AgentOptions.java`
- Test: `agent/src/test/java/io/pjacoco/agent/AgentOptionsInProcessTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.pjacoco.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentOptionsInProcessTest {

    @Test
    void aggregateDefaultsOnWithDefaultFileName() {
        AgentOptions o = AgentOptions.empty();
        assertTrue(o.aggregate(), "aggregate must default to true");
        assertEquals("aggregate.exec", o.aggregateFile(), "aggregateFile default name");
        assertTrue(o.junit4Auto(), "junit4Auto must default to true");
    }

    @Test
    void aggregateCanBeDisabledAndFileRenamed() {
        AgentOptions o = AgentOptions.parse("aggregate=false,aggregateFile=whole.exec,junit4Auto=false");
        assertFalse(o.aggregate());
        assertEquals("whole.exec", o.aggregateFile());
        assertFalse(o.junit4Auto());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :agent:test --tests 'io.pjacoco.agent.AgentOptionsInProcessTest'`
Expected: FAIL — `aggregate()`/`aggregateFile()`/`junit4Auto()` undefined.

- [ ] **Step 3: Add the accessors**

In `AgentOptions.java`, after the `commitSha()` accessor add:

```java
    /** Whether to dump the whole-run aggregate {@code .exec} at shutdown. Default true. */
    public boolean aggregate() { return Boolean.parseBoolean(get("aggregate", "true")); }
    /** Aggregate file name (relative to the output dir) or absolute path. Default {@code aggregate.exec}. */
    public String aggregateFile() { return get("aggregateFile", "aggregate.exec"); }
    /** Whether to weave JUnit 4's {@code ParentRunner.runLeaf} for zero-touch activation. Default true. */
    public boolean junit4Auto() { return Boolean.parseBoolean(get("junit4Auto", "true")); }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :agent:test --tests 'io.pjacoco.agent.AgentOptionsInProcessTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/java/io/pjacoco/agent/AgentOptions.java agent/src/test/java/io/pjacoco/agent/AgentOptionsInProcessTest.java
git commit -m "feat(agent): aggregate/aggregateFile/junit4Auto options (aggregate default on)"
```

### Task 8: `AggregateWriter`

**Files:**
- Create: `agent/src/main/java/io/pjacoco/agent/output/AggregateWriter.java`
- Test: `agent/src/test/java/io/pjacoco/agent/output/AggregateWriterTest.java`

- [ ] **Step 1: Write the failing test**

This test builds a real jacoco `RuntimeData` by instrumenting + running a class (the proven `GoldenEquivalenceIT` pattern), dumps the aggregate, and re-reads it with jacoco's `ExecutionDataReader` to confirm a valid, non-empty `.exec`. Because it needs the jacoco runtime, place it in the agent **unit** test set (jacoco-core is a `testImplementation` dependency).

```java
package io.pjacoco.agent.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.IExecutionDataVisitor;
import org.jacoco.core.data.ISessionInfoVisitor;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AggregateWriterTest {

    // AggTarget is a TOP-LEVEL class (defined at the bottom of this file), so its compiled resource is
    // /io/pjacoco/agent/output/AggTarget.class — matching NAME. A nested class would compile to
    // AggregateWriterTest$AggTarget.class and readBytes(NAME) would NPE on a null stream.
    static final String NAME = "io.pjacoco.agent.output.AggTarget";

    @Test
    void writesAValidNonEmptyExecFromRuntimeData(@TempDir Path dir) throws Exception {
        // Instrument AggTarget into a child loader and run it so RuntimeData accumulates probes.
        byte[] original = readBytes(NAME);
        IRuntime runtime = new LoggerRuntime();
        Instrumenter instrumenter = new Instrumenter(runtime);
        byte[] instrumented = instrumenter.instrument(original, NAME);
        RuntimeData data = new RuntimeData();
        runtime.startup(data);

        MemoryLoader loader = new MemoryLoader(getClass().getClassLoader());
        loader.add(NAME, instrumented);
        Class<?> target = loader.loadClass(NAME);
        target.getMethod("classify", int.class).invoke(target.getDeclaredConstructor().newInstance(), 5);

        Path exec = dir.resolve("aggregate.exec");
        new AggregateWriter().write(dir, "aggregate.exec", data);
        runtime.shutdown();

        assertTrue(Files.exists(exec) && Files.size(exec) > 0, "aggregate.exec must be written and non-empty");

        // Re-read it: exactly the one class, with at least one covered probe.
        Map<Long, boolean[]> read = new HashMap<>();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(exec))) {
            ExecutionDataReader r = new ExecutionDataReader(in);
            r.setSessionInfoVisitor(new ISessionInfoVisitor() {
                public void visitSessionInfo(SessionInfo info) { }
            });
            r.setExecutionDataVisitor(new IExecutionDataVisitor() {
                public void visitClassExecution(ExecutionData d) { read.put(d.getId(), d.getProbes()); }
            });
            r.read();
        }
        assertEquals(1, read.size(), "exactly AggTarget recorded");
        boolean any = false;
        for (boolean[] p : read.values()) for (boolean b : p) any |= b;
        assertTrue(any, "at least one probe must be covered");
    }

    @Test
    void resolvesRelativeUnderOutputDirAndAbsoluteAsIs(@TempDir Path dir) {
        assertEquals(dir.resolve("aggregate.exec"), AggregateWriter.resolve(dir, "aggregate.exec"));
        Path abs = dir.resolve("sub").resolve("x.exec").toAbsolutePath();
        assertEquals(abs, AggregateWriter.resolve(dir, abs.toString()));
    }

    private static byte[] readBytes(String fqcn) throws Exception {
        try (InputStream in = AggregateWriterTest.class.getResourceAsStream("/" + fqcn.replace('.', '/') + ".class")) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    static final class MemoryLoader extends ClassLoader {
        private final Map<String, byte[]> defs = new HashMap<>();
        MemoryLoader(ClassLoader parent) { super(parent); }
        void add(String name, byte[] bytes) { defs.put(name, bytes); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] b = defs.get(name);
            if (b != null) {
                Class<?> c = findLoadedClass(name);
                if (c == null) c = defineClass(name, b, 0, b.length);
                if (resolve) resolveClass(c);
                return c;
            }
            return super.loadClass(name, resolve);
        }
    }
}

/** Top-level SUT for AggregateWriterTest — compiles to io/pjacoco/agent/output/AggTarget.class. */
class AggTarget {
    public int classify(int n) {
        if (n < 0) {
            return -1;
        }
        return 1;
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :agent:test --tests 'io.pjacoco.agent.output.AggregateWriterTest'`
Expected: FAIL — `AggregateWriter` does not exist.

- [ ] **Step 3: Implement `AggregateWriter`**

```java
package io.pjacoco.agent.output;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.runtime.RuntimeData;

/**
 * Writes the agent's retained whole-run {@link RuntimeData} as a single vanilla-JaCoCo-format
 * {@code .exec} at shutdown (jacoco {@code dumponexit} equivalent). Authored against
 * {@code org.jacoco.*} exactly like {@link ExecWriter}, so the shadow plugin relocates it to
 * {@code io.pjacoco.shaded.jacoco.*}, matching the retained {@code RuntimeData} instance's type.
 */
public final class AggregateWriter {

    /** Collect the whole-run data and serialize it to {@code aggregateFile} (resolved under {@code outDir}).
     *  {@code collect} populates the SessionInfoStore with the runtime's own session (id + dump time),
     *  so no timestamp argument is needed. */
    public void write(Path outDir, String aggregateFile, RuntimeData data) throws Exception {
        ExecutionDataStore execStore = new ExecutionDataStore();
        SessionInfoStore sessionStore = new SessionInfoStore();
        data.collect(execStore, sessionStore, false);

        Path target = resolve(outDir, aggregateFile);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        OutputStream os = new BufferedOutputStream(Files.newOutputStream(target));
        try {
            ExecutionDataWriter w = new ExecutionDataWriter(os);
            sessionStore.accept(w);
            execStore.accept(w);
        } finally {
            os.close();
        }
    }

    /** Absolute path → used as-is; otherwise resolved under the output directory. */
    static Path resolve(Path outDir, String aggregateFile) {
        Path p = Paths.get(aggregateFile);
        return p.isAbsolute() ? p : outDir.resolve(aggregateFile);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :agent:test --tests 'io.pjacoco.agent.output.AggregateWriterTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/java/io/pjacoco/agent/output/AggregateWriter.java agent/src/test/java/io/pjacoco/agent/output/AggregateWriterTest.java
git commit -m "feat(agent): AggregateWriter (whole-run .exec from retained RuntimeData)"
```

### Task 9: `ProbeInstrumentation.install` retains + returns `RuntimeData`

**Files:**
- Modify: `agent/src/main/java/io/pjacoco/agent/probe/ProbeInstrumentation.java`

- [ ] **Step 1: Change the signature to return the retained `RuntimeData`**

In `ProbeInstrumentation.java`, change the `install` method. Replace:

```java
    public static void install(Instrumentation inst, AgentOptions options) throws Exception {
        installHookOnly(inst);

        // LoggerRuntime: in-process data channel for the instrumented classes' $jacocoInit. We don't
        // consume jacoco's global data (our bridge records per-test) — the runtime just satisfies the
        // instrumented code. Matches the validated spike.
        IRuntime runtime = new LoggerRuntime();
        runtime.startup(new RuntimeData());

        Instrumenter instrumenter = new Instrumenter(runtime);
        // Force ProbeInserter to load + be advised now (clean context). If it first loads later inside
        // our own transform(), ByteBuddy skips advising it and per-test routing silently no-ops.
        warmUp(instrumenter);
        inst.addTransformer(new JacocoTransformer(instrumenter, options), false);
    }
```

with:

```java
    /**
     * @return the global {@link RuntimeData} the instrumented classes write to. Previously a throwaway
     *     local; now RETAINED so {@code Bootstrap} can dump the whole-run aggregate at shutdown
     *     (jacoco's always-populated base layer). Per-test routing is unaffected.
     */
    public static RuntimeData install(Instrumentation inst, AgentOptions options) throws Exception {
        installHookOnly(inst);

        // LoggerRuntime: in-process data channel for the instrumented classes' $jacocoInit. The global
        // RuntimeData accumulates EVERY probe (jacoco's base layer); the per-test bridge records the
        // additive per-test layer on top. Matches the validated spike.
        IRuntime runtime = new LoggerRuntime();
        RuntimeData data = new RuntimeData();
        runtime.startup(data);

        Instrumenter instrumenter = new Instrumenter(runtime);
        // Force ProbeInserter to load + be advised now (clean context). If it first loads later inside
        // our own transform(), ByteBuddy skips advising it and per-test routing silently no-ops.
        warmUp(instrumenter);
        inst.addTransformer(new JacocoTransformer(instrumenter, options), false);
        return data;
    }
```

- [ ] **Step 2: Verify the agent still compiles (Bootstrap currently ignores the return value)**

Run: `./gradlew :agent:compileJava`
Expected: BUILD SUCCESSFUL (changing `void` → `RuntimeData` does not break the existing `ProbeInstrumentation.install(inst, options);` call statement).

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/java/io/pjacoco/agent/probe/ProbeInstrumentation.java
git commit -m "refactor(agent): retain + return the global RuntimeData from install"
```

### Task 10: `Bootstrap` — bind registry, retain `RuntimeData`, aggregate dump

This task wires `CoverageControl` and the aggregate shutdown dump. The JUnit 4 activator install is added in **Task 11** (after its class exists) — so this task has no forward dependency and is independently compilable/committable. The aggregate dump is verified end-to-end by AC-IP5 (Task 3).

**Files:**
- Modify: `agent/src/main/java/io/pjacoco/agent/Bootstrap.java`

- [ ] **Step 1: Replace `Bootstrap.premain` with the restructured version**

Add these imports alongside the other `io.pjacoco.agent.*` imports:

```java
import io.pjacoco.agent.api.CoverageControl;
import io.pjacoco.agent.output.AggregateWriter;
import org.jacoco.core.runtime.RuntimeData;
```

Replace the entire `premain` method body with:

```java
    public static void premain(String args, Instrumentation inst) throws Exception {
        AgentOptions options = AgentOptions.parse(args);
        Metrics metrics = new Metrics();
        AgentLog log = new AgentLog();

        String commitSha = options.commitSha() != null ? options.commitSha()
                : System.getenv("PJACOCO_COMMIT");

        final Path outDir = Paths.get(options.outputDir());
        final TestStoreRegistry registry = new TestStoreRegistry(
                outDir, new ExecWriter(), metrics, log,
                options.autoRegister(), options.maxStores(), new java.util.function.LongSupplier() {
                    public long getAsLong() { return System.currentTimeMillis(); }
                });

        // In-JVM activation API: the testkit reaches this reflectively; the JUnit 4 runLeaf advice calls it.
        CoverageControl.bindRegistry(registry);

        // Global meta header, written once at startup (persists commitSha, no per-stop contention).
        try {
            Files.createDirectories(outDir);
            String header = new Json()
                    .put("schemaVersion", 1)
                    .put("jacocoVersion", "0.8.12")
                    .put("commitSha", commitSha)        // null -> omitted
                    .put("precision", "line")
                    .toString();
            Files.write(outDir.resolve("manifest.json"), header.getBytes("UTF-8"));
        } catch (Exception e) {
            log.warn("manifest", "could not write manifest header: " + e);
        }

        CoverageBridge.bindMetrics(metrics);

        // Retain the global RuntimeData so the shutdown hook can dump the whole-run aggregate.
        final RuntimeData runtimeData = ProbeInstrumentation.install(inst, options);

        // Best-effort control endpoint (the in-process path never calls it; a bind clash is harmless there).
        final ControlEndpoint[] endpointRef = new ControlEndpoint[1];
        try {
            ControlEndpoint endpoint = new ControlEndpoint(registry, options.controlHost(), options.controlPort());
            int port = endpoint.start();
            endpointRef[0] = endpoint;
            log.info("control endpoint on " + options.controlHost() + ":" + port);
        } catch (Exception e) {
            log.warn("control", "failed to start control endpoint: " + e);
        }

        // Shutdown hook registered UNCONDITIONALLY (independent of endpoint start) so the default-on
        // aggregate dump and the partial-store dump always run — even when a port-bind clash skips the
        // endpoint. Order: partial dump -> aggregate -> stop endpoint (if started).
        final AgentOptions opts = options;
        final Metrics m = metrics;
        final AgentLog l = log;
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                registry.dumpRemainingAsPartial();
                if (opts.aggregate()) {
                    try {
                        new AggregateWriter().write(outDir, opts.aggregateFile(), runtimeData);
                    } catch (Exception e) {
                        l.warn("aggregate", "failed to write whole-run aggregate: " + e);
                    }
                }
                if (endpointRef[0] != null) {
                    endpointRef[0].stop();
                }
                l.info(m.summary());
            }
        }));

        // Inbound activation: resolve TestStore from the registry per request, set/clear CoverageContext.
        new ServletInboundActivator(registry, metrics, log).install(inst);

        // (Task 11 inserts the JUnit 4 activator install here.)

        log.info("agent installed (output=" + options.outputDir()
                + ", mode=" + (options.autoRegister() ? "auto-register" : "strict") + ")");
    }
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :agent:compileJava`
Expected: BUILD SUCCESSFUL (all referenced classes — `CoverageControl`, `AggregateWriter`, `RuntimeData` — now exist from Tasks 6, 8, 9).

- [ ] **Step 3: Run the full agent unit suite (no regression)**

Run: `./gradlew :agent:test`
Expected: PASS (existing + new unit tests). AC-IP3 (`CoverageControlTest`) and `AggregateWriterTest` are green.

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/java/io/pjacoco/agent/Bootstrap.java
git commit -m "feat(agent): bind CoverageControl, dump whole-run aggregate at shutdown (hook always registered)"
```

---

## Phase D — JUnit 4 agent-side activation

### Task 11: `JUnit4InboundActivator` + `RunLeafAdvice`

**Files:**
- Create: `agent/src/main/java/io/pjacoco/agent/inbound/junit4/JUnit4InboundActivator.java`
- Create: `agent/src/main/java/io/pjacoco/agent/inbound/junit4/RunLeafAdvice.java`
- Test: `agent/src/test/java/io/pjacoco/agent/inbound/junit4/RunLeafAdviceTest.java`

- [ ] **Step 1: Write the failing test (advice logic is unit-testable without weaving)**

The advice's `activate`/`deactivate` static methods extract a testId reflectively from a `Description`-shaped object and call `CoverageControl`. Test them with a fake description and a bound registry. (The actual `runLeaf` weave is covered end-to-end by AC-IP2.)

```java
package io.pjacoco.agent.inbound.junit4;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjacoco.agent.api.CoverageControl;
import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunLeafAdviceTest {

    /** A stand-in for org.junit.runner.Description (read reflectively by the advice). */
    public static final class FakeDescription {
        private final String className, methodName;
        FakeDescription(String c, String m) { this.className = c; this.methodName = m; }
        public String getClassName() { return className; }
        public String getMethodName() { return methodName; }
    }

    @AfterEach
    void cleanup() {
        CoverageControl.bindRegistry(null);
        CoverageContext.clear();
    }

    @Test
    void activateBracketsTestByFqnTestId(@TempDir Path dir) {
        TestStoreRegistry reg = new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(), false, 1000, () -> 1L);
        CoverageControl.bindRegistry(reg);

        RunLeafAdvice.activate(new FakeDescription("com.x.MyTest", "doesThing"));
        assertSame(reg.peek("com.x.MyTest#doesThing"), CoverageContext.get(),
                "advice must activate the FQN#method testId on this thread");
    }

    @Test
    void deactivateFlushesWithResultUnknown(@TempDir Path dir) {
        TestStoreRegistry reg = new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(), false, 1000, () -> 1L);
        CoverageControl.bindRegistry(reg);
        FakeDescription d = new FakeDescription("com.x.MyTest", "doesThing");
        RunLeafAdvice.activate(d);
        CoverageContext.get().record(1L, "com/x/A", 0, 1);   // non-empty
        RunLeafAdvice.deactivate(d);
        assertNull(CoverageContext.get(), "deactivate clears the context");
        assertTrue(Files.exists(dir.resolve("com.x.MyTest#doesThing.exec")), "must flush the per-test .exec");
        assertTrue(Files.exists(dir.resolve("com.x.MyTest#doesThing.json")), "must flush the sidecar");
    }

    @Test
    void nullDescriptionIsANoOp() {
        RunLeafAdvice.activate(null);   // must not throw
        RunLeafAdvice.deactivate(null);
        assertNull(CoverageContext.get());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :agent:test --tests 'io.pjacoco.agent.inbound.junit4.RunLeafAdviceTest'`
Expected: FAIL — `RunLeafAdvice` does not exist.

- [ ] **Step 3: Implement `RunLeafAdvice`**

```java
package io.pjacoco.agent.inbound.junit4;

import io.pjacoco.agent.api.CoverageControl;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

/**
 * Woven into {@code org.junit.runners.ParentRunner.runLeaf(Statement, Description, RunNotifier)} — the
 * single choke point that runs one leaf test (its {@code @Before}/{@code @Test}/{@code @After}/rules)
 * via {@code statement.evaluate()} INLINE on the calling thread. The {@code Description} is read
 * reflectively (no JUnit 4 dependency on the agent, like {@code ServletAdvice} reading the baggage
 * header). {@code activate}/{@code deactivate} are extracted as static methods for unit testing.
 *
 * <p>Result is the fixed string {@code "unknown"}: {@code runLeaf} catches all test exceptions
 * internally, so the advice has no pass/fail signal (the {@code @Rule} path does and writes
 * passed/failed). {@code @Test(timeout)}/{@code @Rule Timeout} run the body on a NEW thread, so such
 * tests are silently empty under this path (documented limitation).
 */
public final class RunLeafAdvice {

    private RunLeafAdvice() {}

    public static void activate(Object description) {
        try {
            String id = testId(description);
            if (id != null) {
                CoverageControl.activate(id, null);
            }
        } catch (Throwable ignored) {
            // never disturb the test runner
        }
    }

    public static void deactivate(Object description) {
        try {
            String id = testId(description);
            if (id != null) {
                CoverageControl.deactivate(id, "unknown");
            }
        } catch (Throwable ignored) {
            // never disturb the test runner
        }
    }

    private static String testId(Object description) {
        if (description == null) {
            return null;
        }
        try {
            Method gc = description.getClass().getMethod("getClassName");
            Method gm = description.getClass().getMethod("getMethodName");
            Object cn = gc.invoke(description);
            Object mn = gm.invoke(description);
            if (cn == null) {
                return null;
            }
            return cn + "#" + mn;
        } catch (Throwable t) {
            return null;   // not a Description / no such accessors
        }
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter(@Advice.Argument(1) Object description) {
        activate(description);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(@Advice.Argument(1) Object description) {
        deactivate(description);
    }
}
```

- [ ] **Step 4: Implement `JUnit4InboundActivator`**

```java
package io.pjacoco.agent.inbound.junit4;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.pjacoco.agent.inbound.InboundActivator;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

/**
 * Installs the JUnit 4 zero-touch path: body-only advice on
 * {@code org.junit.runners.ParentRunner.runLeaf(Statement, Description, RunNotifier)}. In an
 * out-of-process app JVM {@code ParentRunner} is never loaded, so the advice simply never matches.
 * Reuses the {@code ServletInboundActivator} pattern. Gated by {@code junit4Auto} in {@code Bootstrap}.
 */
public final class JUnit4InboundActivator implements InboundActivator {

    @Override
    public void install(Instrumentation inst) {
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(named("org.junit.runners.ParentRunner"))
                .transform((builder, type, classLoader, module, pd) -> builder.visit(
                        Advice.to(RunLeafAdvice.class).on(named("runLeaf").and(takesArguments(3)))))
                .installOn(inst);
    }
}
```

- [ ] **Step 5: Run to verify the unit test passes**

Run: `./gradlew :agent:test --tests 'io.pjacoco.agent.inbound.junit4.RunLeafAdviceTest'`
Expected: PASS.

- [ ] **Step 6: Wire the activator into `Bootstrap` (gated on `junit4Auto`)**

In `Bootstrap.java`, add the import:

```java
import io.pjacoco.agent.inbound.junit4.JUnit4InboundActivator;
```

and replace the placeholder line left by Task 10:

```java
        // (Task 11 inserts the JUnit 4 activator install here.)
```

with:

```java
        if (options.junit4Auto()) {
            new JUnit4InboundActivator().install(inst);
        }
```

- [ ] **Step 7: Compile + run the agent unit suite**

Run: `./gradlew :agent:compileJava && ./gradlew :agent:test`
Expected: BUILD SUCCESSFUL; PASS.

- [ ] **Step 8: Commit**

```bash
git add agent/src/main/java/io/pjacoco/agent/inbound/junit4/ agent/src/test/java/io/pjacoco/agent/inbound/junit4/ agent/src/main/java/io/pjacoco/agent/Bootstrap.java
git commit -m "feat(agent): JUnit4InboundActivator + RunLeafAdvice (zero-touch runLeaf weave)"
```

---

## Phase E — testkit: bridge + extension + rule

### Task 12: `InProcessBridge`

**Files:**
- Create: `testkit-core/src/main/java/io/pjacoco/testkit/inprocess/InProcessBridge.java`
- Test: `testkit-core/src/test/java/io/pjacoco/testkit/inprocess/InProcessBridgeTest.java`

- [ ] **Step 1: Write the failing test**

In a plain unit test the agent class `io.pjacoco.agent.api.CoverageControl` is NOT on the classpath, so the bridge must degrade to a no-op and `available()` must be false, never throwing. (The reachable-agent path is covered end-to-end by the functional tests.)

```java
package io.pjacoco.testkit.inprocess;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class InProcessBridgeTest {

    @Test
    void notAvailableWhenAgentClassAbsent() {
        // CoverageControl is not on the testkit's test classpath.
        assertFalse(InProcessBridge.available(), "no agent on classpath -> not available");
    }

    @Test
    void activateAndDeactivateAreNoOpAndNeverThrowWhenAbsent() {
        assertDoesNotThrow(() -> {
            InProcessBridge.activate("com.x.T#m", null);
            InProcessBridge.deactivate("com.x.T#m", "passed");
        });
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :testkit-core:test --tests 'io.pjacoco.testkit.inprocess.InProcessBridgeTest'`
Expected: FAIL — `InProcessBridge` does not exist.

- [ ] **Step 3: Implement `InProcessBridge`**

```java
package io.pjacoco.testkit.inprocess;

import io.pjacoco.testkit.Pjacoco;
import java.lang.reflect.Method;

/**
 * Best-effort reflective bridge to the agent's {@code io.pjacoco.agent.api.CoverageControl}, resolved
 * across the classloader boundary (the agent is on the system classloader; test code is a child). Used
 * by {@code PjacocoInProcessExtension} (JUnit 5) and {@code PjacocoInProcessRule} (JUnit 4). Java 8,
 * zero third-party deps. Every call is a no-op when the agent is absent (out-of-process JVM, or no
 * {@code -javaagent}) and never throws into test code.
 *
 * <p>On the first activation that cannot reach a ready agent it logs ONE warning so an unexpectedly
 * empty {@code .exec} is diagnosable — UNLESS {@code pjacoco.control-url} is set, which means the user
 * is on the black-box (servlet) path and a local agent is expected to be absent.
 */
public final class InProcessBridge {

    private static final String CONTROL_CLASS = "io.pjacoco.agent.api.CoverageControl";

    private static volatile boolean resolved;
    private static Method isReadyM;
    private static Method activateM;
    private static Method deactivateM;
    private static final java.util.concurrent.atomic.AtomicBoolean WARNED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private InProcessBridge() {}

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        try {
            Class<?> c = load();
            if (c != null) {
                isReadyM = c.getMethod("isReady");
                activateM = c.getMethod("activate", String.class, String.class);
                deactivateM = c.getMethod("deactivate", String.class, String.class);
            }
        } catch (Throwable t) {
            isReadyM = null;
            activateM = null;
            deactivateM = null;
        } finally {
            // Set LAST: a concurrent caller that reads resolved==true (outside this monitor) is then
            // guaranteed to see the fully-assigned handles. (All readers also enter this synchronized
            // method first, so the monitor already establishes the happens-before; this is belt + braces.)
            resolved = true;
        }
    }

    private static Class<?> load() {
        ClassLoader[] loaders = {
            ClassLoader.getSystemClassLoader(),
            Thread.currentThread().getContextClassLoader(),
            InProcessBridge.class.getClassLoader()
        };
        for (ClassLoader cl : loaders) {
            if (cl == null) {
                continue;
            }
            try {
                return Class.forName(CONTROL_CLASS, false, cl);
            } catch (Throwable ignored) {
                // try the next loader
            }
        }
        try {
            return Class.forName(CONTROL_CLASS);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** @return true only when the agent class is reachable AND its registry is bound (agent installed). */
    public static boolean available() {
        resolve();
        if (isReadyM == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(isReadyM.invoke(null));
        } catch (Throwable t) {
            return false;
        }
    }

    public static void activate(String testId, String shardId) {
        if (available()) {
            try {
                activateM.invoke(null, testId, shardId);
                return;
            } catch (Throwable ignored) {
                // fall through to warn
            }
        }
        warnOnce();
    }

    public static void deactivate(String testId, String result) {
        resolve();
        if (deactivateM == null) {
            return;
        }
        try {
            deactivateM.invoke(null, testId, result);
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    private static void warnOnce() {
        if (!WARNED.compareAndSet(false, true)) {
            return;   // atomic: exactly one thread ever wins, even under parallel execution
        }
        if (Pjacoco.controlUrl() != null) {
            return;   // black-box path: a local agent is expected to be absent; the warning would mislead
        }
        System.err.println("[pjacoco] in-process agent not reachable; per-test coverage disabled");
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :testkit-core:test --tests 'io.pjacoco.testkit.inprocess.InProcessBridgeTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add testkit-core/src/main/java/io/pjacoco/testkit/inprocess/InProcessBridge.java testkit-core/src/test/java/io/pjacoco/testkit/inprocess/InProcessBridgeTest.java
git commit -m "feat(testkit-core): InProcessBridge reflective best-effort agent bridge"
```

### Task 13: `PjacocoInProcessExtension` + services file (JUnit 5)

**Files:**
- Create: `testkit-junit5/src/main/java/io/pjacoco/testkit/junit5/PjacocoInProcessExtension.java`
- Create: `testkit-junit5/src/main/resources/META-INF/services/org.junit.jupiter.api.extension.Extension`
- Test: `testkit-junit5/src/test/java/io/pjacoco/testkit/junit5/PjacocoInProcessExtensionTest.java`

- [ ] **Step 1: Write the failing test**

The extension calls `InProcessBridge` (a no-op without the agent). The meaningful unit assertion is the **FQN testId** derivation and that callbacks don't throw. Use a minimal `ExtensionContext` stub via a real test run.

```java
package io.pjacoco.testkit.junit5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The extension is a no-op without the agent; assert it loads + brackets a test without throwing.
 *  (Per-test routing is verified end-to-end by the gradle-plugin functional test AC-IP1/IP6.) */
@ExtendWith(PjacocoInProcessExtension.class)
class PjacocoInProcessExtensionTest {

    @Test
    void runsCleanlyWithoutAnAgent() {
        assertDoesNotThrow(() -> { int x = 1 + 1; });
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :testkit-junit5:test --tests 'io.pjacoco.testkit.junit5.PjacocoInProcessExtensionTest'`
Expected: FAIL — `PjacocoInProcessExtension` does not exist (compile error).

- [ ] **Step 3: Implement the extension**

```java
package io.pjacoco.testkit.junit5;

import io.pjacoco.testkit.inprocess.InProcessBridge;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension for IN-PROCESS per-test coverage: brackets each test method with
 * {@code CoverageControl.activate}/{@code deactivate} (via {@link InProcessBridge}) on the test
 * thread, so a pure in-JVM test (SUT called directly, no servlet) gets a per-test {@code .exec}.
 *
 * <p>Register explicitly with {@code @ExtendWith(PjacocoInProcessExtension.class)}, or enable
 * suite-wide auto-registration (the {@code io.pjacoco.gradle} plugin sets
 * {@code junit.jupiter.extensions.autodetection.enabled=true} and this is the single service-registered
 * extension). Distinct from {@code PjacocoExtension} (the HTTP/servlet black-box path).
 *
 * <p>testId is the FULLY-QUALIFIED class name + method (no header-length constraint here), avoiding
 * collisions between same-named test classes in different packages.
 */
public final class PjacocoInProcessExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        InProcessBridge.activate(testId(context), null);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        String result = context.getExecutionException().isPresent() ? "failed" : "passed";
        InProcessBridge.deactivate(testId(context), result);
    }

    private static String testId(ExtensionContext context) {
        return context.getRequiredTestClass().getName() + "#" + context.getRequiredTestMethod().getName();
    }
}
```

- [ ] **Step 4: Create the services file (exactly one entry)**

`testkit-junit5/src/main/resources/META-INF/services/org.junit.jupiter.api.extension.Extension`:

```
io.pjacoco.testkit.junit5.PjacocoInProcessExtension
```

(The HTTP-path `PjacocoExtension` is deliberately NOT listed — Task 14 guards this.)

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :testkit-junit5:test --tests 'io.pjacoco.testkit.junit5.PjacocoInProcessExtensionTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add testkit-junit5/src/main/java/io/pjacoco/testkit/junit5/PjacocoInProcessExtension.java testkit-junit5/src/main/resources/META-INF/services/ testkit-junit5/src/test/java/io/pjacoco/testkit/junit5/PjacocoInProcessExtensionTest.java
git commit -m "feat(testkit-junit5): PjacocoInProcessExtension + auto-registration services file"
```

### Task 14: Services-file single-entry guard

**Files:**
- Test: `testkit-junit5/src/test/java/io/pjacoco/testkit/junit5/ServicesFileTest.java`

- [ ] **Step 1: Write the test**

```java
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
```

- [ ] **Step 2: Run to verify it passes**

Run: `./gradlew :testkit-junit5:test --tests 'io.pjacoco.testkit.junit5.ServicesFileTest'`
Expected: PASS (the file was created in Task 13).

- [ ] **Step 3: Commit**

```bash
git add testkit-junit5/src/test/java/io/pjacoco/testkit/junit5/ServicesFileTest.java
git commit -m "test(testkit-junit5): guard the services file lists exactly the in-process extension"
```

### Task 15: `PjacocoInProcessRule` (JUnit 4)

**Files:**
- Create: `testkit-junit4/src/main/java/io/pjacoco/testkit/junit4/PjacocoInProcessRule.java`
- Test: `testkit-junit4/src/test/java/io/pjacoco/testkit/junit4/PjacocoInProcessRuleTest.java`

- [ ] **Step 1: Write the failing test**

> **Why JUnit 5 style:** `testkit-junit4/build.gradle.kts` runs `tasks.test { useJUnitPlatform() }` with **no Vintage engine**. A test written with JUnit 4 `@Test`/`@Rule` would NOT be discovered (0 tests run, false green). The existing `PjacocoRuleTest` drives the rule via `rule.apply(base, desc).evaluate()` from a JUnit 5 test — do the same here.

```java
package io.pjacoco.testkit.junit4;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/** Drives {@link PjacocoInProcessRule} via its JUnit 4 TestWatcher API (apply().evaluate()), exactly
 *  like the existing PjacocoRuleTest; assertions run on the JUnit 5 platform. Without an agent the
 *  InProcessBridge is a no-op, so the rule must bracket the body without throwing. (Per-test routing
 *  is verified end-to-end by the gradle-plugin functional test AC-IP2.) */
class PjacocoInProcessRuleTest {

    static class SampleSuite { }

    @Test
    void bracketsAPassingTestWithoutThrowing() throws Throwable {
        PjacocoInProcessRule rule = new PjacocoInProcessRule();
        Description desc = Description.createTestDescription(SampleSuite.class, "doesThing");
        final boolean[] ran = { false };
        Statement base = new Statement() {
            public void evaluate() { ran[0] = true; }
        };
        rule.apply(base, desc).evaluate();
        assertTrue(ran[0], "rule must run the wrapped test body");
    }

    @Test
    void propagatesAFailingTest() {
        PjacocoInProcessRule rule = new PjacocoInProcessRule();
        Description desc = Description.createTestDescription(SampleSuite.class, "boom");
        Statement base = new Statement() {
            public void evaluate() { throw new AssertionError("boom"); }
        };
        assertThrows(AssertionError.class, () -> rule.apply(base, desc).evaluate());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :testkit-junit4:test --tests 'io.pjacoco.testkit.junit4.PjacocoInProcessRuleTest'`
Expected: FAIL — `PjacocoInProcessRule` does not exist (compile error). (The test IS discovered because it is a JUnit 5 test.)

- [ ] **Step 3: Implement the rule**

```java
package io.pjacoco.testkit.junit4;

import io.pjacoco.testkit.inprocess.InProcessBridge;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

/**
 * JUnit 4 rule for IN-PROCESS per-test coverage: brackets each test method with
 * {@code CoverageControl.activate}/{@code deactivate} (via {@link InProcessBridge}) on the test
 * thread. The explicit alternative to the agent-side zero-touch path (the {@code ParentRunner.runLeaf}
 * weave); use this when {@code junit4Auto=false} or for explicit control. Reports pass/fail/skipped
 * (the agent-side path reports {@code unknown}). Distinct from {@code PjacocoRule} (HTTP/servlet path).
 *
 * <pre>{@code @Rule public final PjacocoInProcessRule pjacoco = new PjacocoInProcessRule();}</pre>
 */
public final class PjacocoInProcessRule extends TestWatcher {

    private volatile String result = "skipped";

    @Override
    protected void starting(Description description) {
        result = "skipped";
        InProcessBridge.activate(testId(description), null);
    }

    @Override
    protected void succeeded(Description description) {
        result = "passed";
    }

    @Override
    protected void failed(Throwable e, Description description) {
        result = "failed";
    }

    @Override
    protected void finished(Description description) {
        InProcessBridge.deactivate(testId(description), result);
    }

    private static String testId(Description description) {
        Class<?> testClass = description.getTestClass();
        String className = testClass != null ? testClass.getName() : description.getClassName();
        return className + "#" + description.getMethodName();
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :testkit-junit4:test --tests 'io.pjacoco.testkit.junit4.PjacocoInProcessRuleTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add testkit-junit4/src/main/java/io/pjacoco/testkit/junit4/PjacocoInProcessRule.java testkit-junit4/src/test/java/io/pjacoco/testkit/junit4/PjacocoInProcessRuleTest.java
git commit -m "feat(testkit-junit4): PjacocoInProcessRule (explicit in-process per-test boundary)"
```

---

## Phase F — Plugin integration

### Task 16: Gradle plugin — `PjacocoArgs` + DSL flags + autodetection injection

**Files:**
- Modify: `gradle-plugin/src/main/java/io/pjacoco/gradle/PjacocoArgs.java`
- Modify: `gradle-plugin/src/main/java/io/pjacoco/gradle/PjacocoGradleExtension.java`
- Modify: `gradle-plugin/src/main/java/io/pjacoco/gradle/PjacocoPlugin.java`
- Test: `gradle-plugin/src/test/java/io/pjacoco/gradle/PjacocoArgsInProcessTest.java`

- [ ] **Step 1: Write the failing `PjacocoArgs` test**

```java
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
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :gradle-plugin:test --tests 'io.pjacoco.gradle.PjacocoArgsInProcessTest'`
Expected: FAIL — the 8-arg `javaagent` overload does not exist (compile error).

- [ ] **Step 3: Extend `PjacocoArgs.javaagent` (add the 8-arg form; keep the 5-arg as a delegating overload)**

This avoids breaking the 3 existing 5-arg calls in `PjacocoArgsTest` (they keep compiling). Replace the existing 5-arg `javaagent(...)` method body with the 8-arg form, then add a thin 5-arg overload above it that delegates with the agent defaults:

```java
    /** Backward-compatible 5-arg form: aggregate on, default aggregate file, junit4Auto on. */
    static String javaagent(String agentJarPath, int port, String destfile,
                            List<String> includes, List<String> excludes) {
        return javaagent(agentJarPath, port, destfile, includes, excludes, true, null, true);
    }

    static String javaagent(String agentJarPath, int port, String destfile,
                            List<String> includes, List<String> excludes,
                            boolean aggregate, String aggregateFile, boolean junit4Auto) {
        StringBuilder opts = new StringBuilder();
        opts.append("destfile=").append(destfile);
        opts.append(",port=").append(port);
        if (includes != null && !includes.isEmpty()) {
            opts.append(",includes=").append(join(includes));
        }
        if (excludes != null && !excludes.isEmpty()) {
            opts.append(",excludes=").append(join(excludes));
        }
        // Aggregate defaults ON in the agent; only append overrides to keep the arg short.
        if (!aggregate) {
            opts.append(",aggregate=false");
        }
        if (aggregateFile != null && !aggregateFile.isEmpty()) {
            opts.append(",aggregateFile=").append(aggregateFile);
        }
        if (!junit4Auto) {
            opts.append(",junit4Auto=false");
        }
        return "-javaagent:" + agentJarPath + "=" + opts;
    }
```

(The existing `PjacocoArgsTest` calls the 5-arg overload and stays green; `PjacocoPlugin`'s `agentJvmArg` lambda is switched to the 8-arg form in Step 6.)

- [ ] **Step 4: Run to verify the `PjacocoArgs` test passes**

Run: `./gradlew :gradle-plugin:test --tests 'io.pjacoco.gradle.PjacocoArgsInProcessTest'`
Expected: PASS.

- [ ] **Step 5: Add the DSL properties to `PjacocoGradleExtension`**

After `getAttachTo()` in `PjacocoGradleExtension.java`, add:

```java
    /** Inject {@code junit.jupiter.extensions.autodetection.enabled=true} so the in-process JUnit 5
     *  extension auto-applies suite-wide (no {@code @ExtendWith}). Default true; set false to opt out. */
    public abstract Property<Boolean> getAutoDetectExtensions();

    /** Write the whole-run aggregate {@code .exec} at shutdown. Default true. */
    public abstract Property<Boolean> getAggregate();

    /** Aggregate file name (or absolute path). Unset → agent default {@code aggregate.exec}. */
    public abstract Property<String> getAggregateFile();

    /** Weave JUnit 4's {@code runLeaf} for zero-touch per-test activation. Default true. */
    public abstract Property<Boolean> getJunit4Auto();
```

- [ ] **Step 6: Wire conventions + the composed arg + autodetection injection in `PjacocoPlugin`**

In `PjacocoPlugin.apply`, after the existing `ext.getDestfile().convention(...)` line add:

```java
        ext.getAutoDetectExtensions().convention(true);
        ext.getAggregate().convention(true);
        ext.getJunit4Auto().convention(true);
```

Change the `agentJvmArg` provider to pass the new options. Replace:

```java
        Provider<String> agentJvmArg = project.provider(() ->
                PjacocoArgs.javaagent(agentJarPath.get(), ext.getPort().get(), destfilePath.get(),
                        ext.getIncludes().getOrElse(java.util.Collections.emptyList()),
                        ext.getExcludes().getOrElse(java.util.Collections.emptyList())));
```

with:

```java
        Provider<String> agentJvmArg = project.provider(() ->
                PjacocoArgs.javaagent(agentJarPath.get(), ext.getPort().get(), destfilePath.get(),
                        ext.getIncludes().getOrElse(java.util.Collections.emptyList()),
                        ext.getExcludes().getOrElse(java.util.Collections.emptyList()),
                        ext.getAggregate().getOrElse(true),
                        ext.getAggregateFile().getOrElse(""),
                        ext.getJunit4Auto().getOrElse(true)));
```

Change the `attachTo` wiring to pass the autodetection flag into `AgentArgs`. Replace:

```java
                        ((JavaForkOptions) task).getJvmArgumentProviders().add(
                                new AgentArgs(agentJvmArg, controlUrlArg));
```

with:

```java
                        ((JavaForkOptions) task).getJvmArgumentProviders().add(
                                new AgentArgs(agentJvmArg, controlUrlArg, ext.getAutoDetectExtensions()));
```

Replace the `AgentArgs` static class with the autodetection-aware version:

```java
    /** Lazily yields the agent + control-url (+ optional JUnit 5 autodetection) JVM args at execution time. */
    static final class AgentArgs implements CommandLineArgumentProvider {
        private final Provider<String> agentJvmArg;
        private final Provider<String> controlUrlArg;
        private final Provider<Boolean> autoDetectExtensions;

        AgentArgs(Provider<String> agentJvmArg, Provider<String> controlUrlArg,
                  Provider<Boolean> autoDetectExtensions) {
            this.agentJvmArg = agentJvmArg;
            this.controlUrlArg = controlUrlArg;
            this.autoDetectExtensions = autoDetectExtensions;
        }

        @Override
        public Iterable<String> asArguments() {
            java.util.List<String> args = new java.util.ArrayList<>();
            args.add(agentJvmArg.get());
            args.add(controlUrlArg.get());
            if (Boolean.TRUE.equals(autoDetectExtensions.getOrElse(true))) {
                args.add("-Djunit.jupiter.extensions.autodetection.enabled=true");
            }
            return args;
        }
    }
```

- [ ] **Step 7: Run the full gradle-plugin unit suite (excluding the still-red functional tests)**

Run: `./gradlew :gradle-plugin:test --tests 'io.pjacoco.gradle.PjacocoArgsInProcessTest' --tests 'io.pjacoco.gradle.PjacocoArgsTest'`
Expected: PASS (the existing `PjacocoArgsTest`'s three 5-arg calls compile unchanged against the delegating overload from Step 3; the new `PjacocoArgsInProcessTest` exercises the 8-arg form).

- [ ] **Step 8: Commit**

```bash
git add gradle-plugin/src/main/java/io/pjacoco/gradle/ gradle-plugin/src/test/java/io/pjacoco/gradle/PjacocoArgsInProcessTest.java
git commit -m "feat(gradle-plugin): autoDetectExtensions/aggregate/aggregateFile/junit4Auto DSL + arg wiring"
```

### Task 17: Maven plugin — aggregate / aggregateFile / junit4Auto params

**Files:**
- Modify: `maven-plugin/src/main/java/io/pjacoco/maven/PrepareAgentMojo.java`

- [ ] **Step 1: Add the parameters**

After the `excludes` `@Parameter` field in `PrepareAgentMojo.java`, add:

```java
    /** Write the whole-run aggregate {@code .exec} at shutdown. Default true. */
    @Parameter(property = "pjacoco.aggregate", defaultValue = "true")
    boolean aggregate;

    /** Aggregate file name (or absolute path). Unset → agent default {@code aggregate.exec}. */
    @Parameter(property = "pjacoco.aggregateFile")
    String aggregateFile;

    /** Weave JUnit 4's {@code runLeaf} for zero-touch per-test activation. Default true. */
    @Parameter(property = "pjacoco.junit4Auto", defaultValue = "true")
    boolean junit4Auto;
```

- [ ] **Step 2: Append them in `options()`**

In `PrepareAgentMojo.options()`, before `return opts.toString();`, add:

```java
        if (!aggregate) {
            opts.append(",aggregate=false");
        }
        if (aggregateFile != null && !aggregateFile.isEmpty()) {
            opts.append(",aggregateFile=").append(aggregateFile);
        }
        if (!junit4Auto) {
            opts.append(",junit4Auto=false");
        }
```

- [ ] **Step 3: Verify compile / Maven build**

Run: `cd maven-plugin && mvn -q -o compile ; cd ..` (or `mvn -q compile` if offline cache is cold)
Expected: BUILD SUCCESS. If the maven-plugin has a unit test for `options()`, update it to expect the unchanged default output (defaults append nothing).

- [ ] **Step 4: Commit**

```bash
git add maven-plugin/src/main/java/io/pjacoco/maven/PrepareAgentMojo.java
git commit -m "feat(maven-plugin): aggregate/aggregateFile/junit4Auto params (parity with gradle)"
```

> **Maven JUnit 5 auto-registration note (docs only, no mojo change):** Maven users enable suite-wide auto-registration via `src/test/resources/junit-platform.properties` (`junit.jupiter.extensions.autodetection.enabled=true`) or surefire `<systemPropertyVariables>`. The mojo deliberately does not inject this (it controls `argLine`, not the JUnit platform config). README documents it (Task 19).

---

## Phase G — Drive acceptance tests green + sample

### Task 18: Publish to mavenLocal and run the in-process functional tests green

The functional tests (Tasks 3–4) serve the freshly built jars via flatDir using the system properties wired in Task 2. No mavenLocal publish is needed for them — they use `--withPluginClasspath` + flatDir. Just run them against the now-complete implementation.

- [ ] **Step 1: Build everything**

Run: `./gradlew assemble`
Expected: BUILD SUCCESSFUL (agent shadowJar + all testkit jars build).

- [ ] **Step 2: Run AC-IP4 (build guard) green**

Run: `./gradlew :agent:test --tests 'io.pjacoco.agent.ShadedJarContainsApiTest'`
Expected: PASS (CoverageControl now in the shaded jar, un-relocated).

- [ ] **Step 3: Run AC-IP1/IP5/IP6 green**

Run: `./gradlew :gradle-plugin:test --tests 'io.pjacoco.gradle.PjacocoInProcessFunctionalTest'`
Expected: PASS. If `aggregate.exec` is missing, confirm Task 10(d) added the dump; if sidecars are missing, confirm the services file is in the junit5 jar and `autoDetectExtensions` injects the system property.

- [ ] **Step 4: Run AC-IP2 green (both consumer builds)**

Run: `./gradlew :gradle-plugin:test --tests 'io.pjacoco.gradle.PjacocoJUnit4InProcessFunctionalTest'`
Expected: PASS (both `zeroTouchAgentPath_parameterized_andTimeout` and `junit4AutoFalse_disablesAgentPath_butRuleStillWorks`). If the `@Parameterized` sidecar file names differ from `run[0]`/`run[1]`, read the `build/pjacoco` listing from the failing test's output and adjust the expected names (the invariant is two distinct `.exec`).

- [ ] **Step 5: Commit any fixups**

```bash
git add -A
git commit -m "test: drive AC-IP1/IP2/IP4/IP5/IP6 green"
```

### Task 19: Add the pure-unit sample (`unitTest` source set in gradle-sample)

**Files:**
- Modify: `samples/gradle-sample/build.gradle.kts`
- Create: `samples/gradle-sample/src/unitTest/java/com/sample/AlphaUnitTest.java`
- Create: `samples/gradle-sample/src/unitTest/java/com/sample/BetaUnitTest.java`

- [ ] **Step 1: Add a `unitTest` source set + task that demonstrates the in-process path**

In `samples/gradle-sample/build.gradle.kts`, after the existing `pjacoco { ... }` block add:

```kotlin
// Pure-unit in-process per-test coverage demo (no servlet/HTTP): the SUT is called directly on the
// test thread; the in-process extension (auto-registered) brackets each test. Distinct output dir.
sourceSets {
    create("unitTest") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}
val unitTestImplementation by configurations.getting { extendsFrom(configurations.testImplementation.get()) }
val unitTestRuntimeOnly by configurations.getting { extendsFrom(configurations.testRuntimeOnly.get()) }

val unitTest = tasks.register<Test>("unitTest") {
    description = "Pure-unit in-process per-test coverage demo"
    group = "verification"
    testClassesDirs = sourceSets["unitTest"].output.classesDirs
    classpath = sourceSets["unitTest"].runtimeClasspath
    useJUnitPlatform()
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
}
```

Add `unitTest` to the plugin's `attachTo` list — change:

```kotlin
    attachTo.set(listOf("test"))
```

to:

```kotlin
    attachTo.set(listOf("test", "unitTest"))
```

- [ ] **Step 2: Add two pure-unit tests (no `@ExtendWith` — auto-registered)**

`samples/gradle-sample/src/unitTest/java/com/sample/AlphaUnitTest.java`:

```java
package com.sample;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Pure unit test (no servlet): calls Alpha directly on the test thread. Auto-registered in-process
 *  extension brackets it, so it gets its own AlphaUnitTest#hitsAlpha.exec. */
class AlphaUnitTest {
    @Test
    void hitsAlpha() {
        assertEquals(1, new Alpha().hit(5));
    }
}
```

`samples/gradle-sample/src/unitTest/java/com/sample/BetaUnitTest.java`:

```java
package com.sample;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BetaUnitTest {
    @Test
    void hitsBeta() {
        assertEquals("x", new Beta().hit(true));
    }
}
```

- [ ] **Step 3: Run the sample's in-process demo (requires the plugin + testkits in mavenLocal)**

Run:
```bash
./gradlew publishToMavenLocal
cd samples/gradle-sample && ./gradlew unitTest --stacktrace ; cd ../..
```
Expected: BUILD SUCCESSFUL; `samples/gradle-sample/build/pjacoco/com.sample.AlphaUnitTest#hitsAlpha.exec` and `...BetaUnitTest#hitsBeta.exec` exist, plus `aggregate.exec`.

> If the sample pins a published version (`pjacocoVersion = "1.1.0"`) that predates these features, run with the locally published `-SNAPSHOT`/current version, or temporarily set the sample's `agentVersion`/dependency versions to the current project version for the demo.

- [ ] **Step 4: Commit**

```bash
git add samples/gradle-sample/build.gradle.kts samples/gradle-sample/src/unitTest
git commit -m "docs(samples): pure-unit in-process per-test coverage demo (unitTest source set)"
```

---

## Phase H — Docs, regression, PR gates

### Task 20: Update README + spec DoD docs

**Files:**
- Modify: `README.md` (and `README` Korean/English variants if both exist — check `ls README*`)
- The spec `docs/superpowers/specs/2026-06-16-in-process-activation-design.md` is already current.

- [ ] **Step 1: Add the in-process + aggregate + zero-touch documentation**

Apply the `writing-documentation` skill (lead with the recommended path, plain verbs, no self-evaluation, no bare AC-IDs). Add to the README, in the existing structure:

1. **Scope:** note in-process per-test coverage is now supported for synchronous in-JVM tests (pure unit, MockMvc, in-JVM integration) — the SUT runs on the test thread, no servlet boundary needed.
2. **Testkit usage:** document `@ExtendWith(PjacocoInProcessExtension.class)` (JUnit 5) and `@Rule PjacocoInProcessRule` (JUnit 4), and that with the `io.pjacoco.gradle` plugin + the `pjacoco-testkit-junit5` dependency the JUnit 5 extension auto-applies suite-wide (no annotation), and JUnit 4 is zero-touch via the agent (no `@Rule`). Mention `autoDetectExtensions=false` / `junit4Auto=false` opt-outs.
3. **Whole-run aggregate:** document that a single `aggregate.exec` (vanilla-JaCoCo format) is written by default at JVM shutdown alongside the per-test files; rename with `aggregateFile`, disable with `aggregate=false` (Gradle `pjacoco { aggregate.set(false) }` / Maven `<aggregate>false</aggregate>`). Note one aggregate per SUT JVM (merge sharded runs with `jacococli merge`) and that a hard kill skips it.
4. **Maven auto-registration:** note the `junit-platform.properties` approach.
5. **Limitations:** async/thread-pool offload is not attributed; `@Test(timeout)`/`@Rule Timeout` run on a new thread (silently empty under JUnit 4 zero-touch); parameterized/repeated JUnit 5 tests share one `testId` (last invocation kept); mixed in-process + servlet on one task → use separate tasks or the opt-outs.

- [ ] **Step 2: Verify links/commands are accurate (no dead steps)**

Read back the edited sections; confirm option names match the code (`aggregate`, `aggregateFile`, `junit4Auto`, `autoDetectExtensions`) and the testkit class names match.

- [ ] **Step 3: Commit**

```bash
git add README*.md
git commit -m "docs: README in-process per-test coverage, whole-run aggregate (default on), JUnit 4/5 zero-touch"
```

### Task 21: Full regression

- [ ] **Step 1: Run the full multi-module suite**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. This runs every module's `test`, the agent `integrationTest`, and the gradle-plugin functional tests. Confirm the existing servlet e2e suites (`SpecAcceptanceE2E`, `SpecAcceptanceJakartaE2E`, `CondyE2E`) still pass — they now also emit an `aggregate.exec` in their output dirs (harmless; no test asserts an exact file count).

- [ ] **Step 2: Run the real-agent e2e tasks explicitly (they are not in `check`)**

Run: `./gradlew :agent:e2eTest :agent:e2eCondyTest :agent:e2eJakartaTest`
Expected: BUILD SUCCESSFUL — the aggregate dump in the shutdown hook does not disturb the servlet path. (If any e2e asserts the exact contents of its output dir, adjust to ignore `aggregate.exec`.)

- [ ] **Step 3: Maven sample (if Docker/maven available)**

Run: `cd samples/maven-sample && mvn -q test ; cd ../..`
Expected: BUILD SUCCESS, or state explicitly that it was skipped (no maven/offline).

- [ ] **Step 4: Commit any regression fixups**

```bash
git add -A
git commit -m "test: full regression green for in-process activation"
```

### Task 22: PR gates — review, then open the PR

Per the project gates: regression green (Task 21) and docs updated (Task 20) first, then review the final diff.

- [ ] **Step 1: Generate the diff for review**

Run: `git fetch origin && git diff origin/main...HEAD > /tmp/in-process.diff && git diff --stat origin/main...HEAD`

- [ ] **Step 2: Spec-compliance review (FIRST)**

Dispatch a spec-compliance reviewer (the `subagent-driven-development` spec-reviewer approach, or `pr-review-toolkit:code-reviewer` with a spec-compliance brief). Give it the spec (`docs/superpowers/specs/2026-06-16-in-process-activation-design.md`, §3–§9 + AC-IP1–IP6) and the diff. It must confirm: CoverageControl contract (§4), empty-store guard in deactivate only (§7b), FQN testId, aggregate default ON + path resolution (§7a), JUnit4 runLeaf weave + result=unknown + timeout limitation (§7c), services-file single entry (§7b), all six acceptance tests present. Triage every finding per `receiving-code-review` (fix or one-line rejection).

- [ ] **Step 3: Code-quality review (SECOND)**

Dispatch `pr-review-toolkit:code-reviewer` over the diff (correctness, concurrency, resource handling, silent failures, test gaps). Pay attention to: the Bootstrap shutdown-hook capturing `runtimeData`/`endpointRef`/`outDir` and running unconditionally, the `InProcessBridge` classloader resolution + once-only warning, the empty-store guard in `CoverageControl.deactivate`, and the agent-side `runLeaf` vs explicit `@Rule` interaction (the `stop-missing` warning when both are active on one test — the spec says don't combine them). Triage every finding.

- [ ] **Step 4: Re-run any tests touched by review fixes**

Run the specific module tests affected, then `./gradlew check` once more if non-trivial fixes landed.

- [ ] **Step 5: Open the PR**

Use the `finishing-a-development-branch` skill (rebase merge only — this repo disables squash/merge-commit). PR description summarizes the change (in-process activation, whole-run aggregate default-on, JUnit 4/5 zero-touch) AND the doc updates. Definition of done: AC-IP1–IP6 green + full regression green + README updated.

---

## Self-Review (run against the spec)

**Spec coverage:**
- §3 architecture (CoverageControl, InProcessBridge, extension/rule, JUnit4InboundActivator) → Tasks 6, 11, 12, 13, 15. ✓
- §4 CoverageControl contract (bindRegistry/isReady/activate/deactivate, null-safety, empty-store guard) → Task 6. ✓
- §5 InProcessBridge (system→context→Class.forName, one-time warn, control-url suppression) + FQN testId + extension/rule → Tasks 12, 13, 15. ✓
- §6 threading (per-thread ThreadLocal, unchanged) → no code change; verified by AC-IP1 parallel run (Task 3). ✓
- §7 plugin (no agent change for the in-process attach) + port note → Task 16. ✓
- §7a aggregate default ON (option, path resolution, AggregateWriter, shutdown-hook ordering, RuntimeData retain) → Tasks 7, 8, 9, 10. ✓
- §7b JUnit 5 auto-registration (services file single entry, autoDetectExtensions, mixed-mode) → Tasks 13, 14, 16. ✓
- §7c JUnit 4 zero-touch (runLeaf weave, result=unknown, timeout limitation, junit4Auto opt-out) → Tasks 10, 11. ✓
- §8 AC-IP1–IP6 → Tasks 3 (IP1: class-identity via ExecutionDataReader + IP5: aggregate superset & `aggregate=false` negative + IP6), 4 (IP2: zero-touch `unknown`, `@Rule` `passed`, `junit4Auto=false` negative, `@Parameterized` distinct `.exec`, `@Test(timeout)` empty), 6 (IP3), 1 (IP4). ✓
- §9 DoD (docs, regression) → Tasks 20, 21. ✓

**Placeholder scan:** No TBD/TODO; every code step shows the code; every test step shows the assertion. The two residual judgment calls each have an explicit fallback: the `@Parameterized` exact sidecar name (read the dir listing and adjust; invariant = two distinct `.exec`), and the `aggregate.exec` parent-dir creation (guarded in `AggregateWriter.resolve`). ✓

**Type consistency:** `CoverageControl.{bindRegistry,isReady,activate,deactivate}`, `TestStoreRegistry.{peek,discard}`, `AggregateWriter.{write(3-arg),resolve}`, `AgentOptions.{aggregate,aggregateFile,junit4Auto}`, `PjacocoArgs.javaagent(5-arg delegating + 8-arg)`, `AgentArgs(3-arg ctor)`, `RunLeafAdvice.{activate,deactivate}` — names used consistently across tasks and tests. testId is FQN (`getName()` / `Description.getClassName()`) everywhere. ✓

---

## Review log (three-model, 2026-06-17)

Reviewed by Claude Sonnet (`design-doc-reviewer`), Gemini 3.5 Flash (High), and OpenAI GPT-5.5 (High), all repo-grounded. All findings incorporated (each was located and verified against the repo; none rejected):

- **AggregateWriter test fixture** — nested `AggTarget` compiles to `AggregateWriterTest$AggTarget.class`, so `readBytes(NAME)` would NPE. Made `AggTarget` a top-level class. (Sonnet I1, Gemini I2, GPT I2)
- **JUnit 4 rule test** — `testkit-junit4` has no Vintage engine, so a JUnit-4-annotated test under `useJUnitPlatform()` would not be discovered (false green). Rewrote it JUnit-5-style driving `rule.apply(base, desc).evaluate()`, matching the existing `PjacocoRuleTest`. (Sonnet I2, GPT I1)
- **PjacocoArgs signature change** — 3 existing 5-arg calls in `PjacocoArgsTest` would break compilation. Kept a delegating 5-arg overload alongside the new 8-arg form. (Sonnet I6, GPT I3)
- **AC-IP2 split** — combining `@Rule` + zero-touch is spec-prohibited and produces a `stop-missing` warning (Sonnet I7 corrected the original "overwrite" analysis). Split AC-IP2 into two consumer builds and added the missing negatives: `junit4Auto=false` disables the agent path, `@Parameterized` distinct `.exec`, `@Test(timeout)` empty. (Sonnet I5/I7, Gemini I3, GPT I4/I6)
- **Bootstrap shutdown hook** — was registered inside the control-endpoint `try`, so a port-bind failure would skip the default-on aggregate dump. Register the hook unconditionally with a nullable endpoint. (Sonnet I9, Gemini I4, GPT I7)
- **AC-IP5 strengthened** — assert the aggregate is a probe-level superset of each per-test `.exec` and add the `aggregate=false` negative build. (Sonnet I4, GPT I5)
- **AC-IP1 strengthened** — assert *which* class each `.exec` holds (mutual exclusion) via `ExecutionDataReader`, not just `classCount=1`. (Sonnet I8)
- **Task 10↔11 forward dependency** — removed by moving the JUnit 4 activator install into Task 11. (Sonnet I3)
- **InProcessBridge** — set `resolved` in a `finally`; made the one-time warning an `AtomicBoolean.compareAndSet`. (Gemini I1, GPT I8)
- **AggregateWriter** — dropped the unused `stoppedAtMillis` parameter. (Gemini I5)
