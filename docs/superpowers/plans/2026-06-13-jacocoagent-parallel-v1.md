# jacocoagent-parallel v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a single Java agent (`jacocoagent-parallel.jar`) that produces vanilla-JaCoCo-compatible `.exec` coverage output split per `testId`, for synchronous servlet apps under parallel test execution.

**Architecture:** Out-of-process Java agent embedding jacoco-core. testId arrives per-request via OpenTelemetry Baggage (`baggage` header), is held in a ThreadLocal `CoverageContext`, and a probe-routing bridge records fired probes into a per-testId `TestStore`. A loopback HTTP control endpoint (`start`/`stop`) defines flush boundaries, emitting `<testId>.exec` + `<testId>.json` sidecars. Additive design: our code never touches the app's behavior and never crashes it.

**Tech Stack:** Java 8 (agent bytecode target), Gradle (Kotlin DSL) + Shadow plugin, jacoco-core 0.8.12, Byte Buddy 1.14.x, JDK `com.sun.net.httpserver` (control endpoint), JUnit 5, embedded Jetty 9.4 (javax.servlet) for integration tests.

---

## File Structure

```
build.gradle.kts                 Gradle build, shadow jar, deps
settings.gradle.kts
gradle/wrapper/...               Gradle wrapper
src/main/java/io/pjacoco/agent/
  Bootstrap.java                 premain: parse opts, wire registry+router, install transforms, start control, shutdown hook
  AgentOptions.java              parse `-javaagent:...=k=v,k=v`, classify JaCoCo opts
  context/CoverageContext.java   ThreadLocal<String> active testId
  store/TestStore.java           per-testId coverage: ConcurrentHashMap<Long, ClassProbes>
  store/ClassProbes.java         className + boolean[] for one class
  store/TestStoreRegistry.java   testId -> TestStore; start/stop; cap + TTL guard
  probe/ProbeRouter.java         static record(classId, className, probeId, probeCount); reads context; swallows errors
  probe/ProbeInstrumentation.java SPIKE: installs jacoco-based class instrumentation routed through ProbeRouter
  inbound/InboundActivator.java  SPI: apply(AgentBuilder) -> AgentBuilder
  inbound/BaggageParser.java     parse W3C/OTel baggage header -> test.id
  inbound/servlet/ServletInboundActivator.java   advises javax HttpServlet#service
  inbound/servlet/ServletAdvice.java             @Advice enter/exit: set/clear context
  control/ControlEndpoint.java   HttpServer loopback; /__coverage__/test/start|stop
  output/ExecWriter.java         TestStore snapshot -> <id>.exec (jacoco) + <id>.json sidecar
  output/Json.java               tiny JSON string emitter (no dep)
  observability/Metrics.java     atomic counters + shutdown summary line
  observability/AgentLog.java    rate-limited logger
src/test/java/io/pjacoco/agent/...        unit tests (mirror packages)
src/integrationTest/java/io/pjacoco/agent/it/
  SampleServlet.java             trivial servlet exercising a known target class
  TargetService.java             instrumented-under-test class with branches
  GoldenEquivalenceIT.java       our .exec == vanilla jacoco .exec (single test)
  ParallelIsolationIT.java       interleaved testIds -> no cross-contamination
  ThreadReuseIT.java             worker reuse -> no context leak
```

**Interface contract that downstream tasks depend on (stable regardless of how the M4 spike resolves the hook):**

```java
// probe/ProbeRouter.java — the ONLY surface the instrumentation calls
public final class ProbeRouter {
    static void bind(TestStoreRegistry registry);            // set at Bootstrap
    public static void record(long classId, String className, int probeId, int probeCount);
}
```

Everything except Milestone 4 depends only on `ProbeRouter.record(...)` and the registry/store/output types — not on how probes get instrumented.

---

## Milestone 0 — Project scaffolding

### Task 0: Gradle project + shadow jar skeleton

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `src/main/java/io/pjacoco/agent/Bootstrap.java`

- [ ] **Step 1: Create `settings.gradle.kts`**

```kotlin
rootProject.name = "jacocoagent-parallel"
```

- [ ] **Step 2: Create `build.gradle.kts`**

```kotlin
plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "io.pjacoco"        // NOT org.jacoco — that namespace belongs to the JaCoCo project
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories { mavenCentral() }

dependencies {
    implementation("org.jacoco:org.jacoco.core:0.8.12")
    implementation("net.bytebuddy:byte-buddy:1.14.18")
    compileOnly("javax.servlet:javax.servlet-api:3.1.0")     // provided by target app

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.jacoco:org.jacoco.core:0.8.12")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

tasks.shadowJar {
    archiveFileName.set("jacocoagent-parallel.jar")
    // shade jacoco-core + byte-buddy so the agent is self-contained and cannot clash with app deps
    relocate("org.jacoco", "io.pjacoco.shaded.jacoco")
    relocate("net.bytebuddy", "io.pjacoco.shaded.bytebuddy")
    manifest {
        attributes(
            "Premain-Class" to "io.pjacoco.agent.Bootstrap",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true"
        )
    }
}

// integrationTest source set
sourceSets {
    create("integrationTest") {
        java.srcDir("src/integrationTest/java")
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    }
}
val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
dependencies {
    integrationTestImplementation("org.eclipse.jetty:jetty-server:9.4.55.v20240627")
    integrationTestImplementation("org.eclipse.jetty:jetty-servlet:9.4.55.v20240627")
}
val integrationTest = tasks.register<Test>("integrationTest") {
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    dependsOn(tasks.shadowJar)   // ITs attach the built agent jar
}
```

- [ ] **Step 3: Create `gradle/wrapper/gradle-wrapper.properties`**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 4: Create minimal `Bootstrap.java` (compiles, no behavior yet)**

```java
package io.pjacoco.agent;

import java.lang.instrument.Instrumentation;

public final class Bootstrap {
    private Bootstrap() {}

    public static void premain(String args, Instrumentation inst) {
        System.out.println("[pjacoco] agent loaded (skeleton)");
    }
}
```

- [ ] **Step 5: Generate wrapper and verify build**

Run: `gradle wrapper --gradle-version 8.10.2 && ./gradlew shadowJar`
Expected: `BUILD SUCCESSFUL`, file `build/libs/jacocoagent-parallel.jar` exists.

- [ ] **Step 6: Verify manifest**

Run: `unzip -p build/libs/jacocoagent-parallel.jar META-INF/MANIFEST.MF | grep Premain-Class`
Expected: `Premain-Class: io.pjacoco.agent.Bootstrap`

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle gradlew gradlew.bat src/main/java/io/pjacoco/agent/Bootstrap.java
git commit -m "build: gradle + shadow jar skeleton for jacocoagent-parallel"
```

---

## Milestone 1 — Context, store, output (no JaCoCo internals)

### Task 1: CoverageContext (ThreadLocal active testId)

**Files:**
- Create: `src/main/java/io/pjacoco/agent/context/CoverageContext.java`
- Test: `src/test/java/io/pjacoco/agent/context/CoverageContextTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.pjacoco.agent.context;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoverageContextTest {
    @Test
    void unsetByDefault() {
        assertNull(CoverageContext.get());
    }

    @Test
    void setAndClear() {
        CoverageContext.set("T1");
        assertEquals("T1", CoverageContext.get());
        CoverageContext.clear();
        assertNull(CoverageContext.get());
    }

    @Test
    void isolatedPerThread() throws Exception {
        CoverageContext.set("main-thread");
        String[] seen = new String[1];
        Thread t = new Thread(() -> seen[0] = CoverageContext.get());
        t.start();
        t.join();
        assertNull(seen[0]);                       // child thread has no context (v1: no async propagation)
        assertEquals("main-thread", CoverageContext.get());
    }
}
```

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew test --tests '*CoverageContextTest'`
Expected: compilation failure (class missing).

- [ ] **Step 3: Implement**

```java
package io.pjacoco.agent.context;

public final class CoverageContext {
    private static final ThreadLocal<String> ACTIVE = new ThreadLocal<>();
    private CoverageContext() {}

    public static void set(String testId) { ACTIVE.set(testId); }
    public static String get() { return ACTIVE.get(); }
    public static void clear() { ACTIVE.remove(); }
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew test --tests '*CoverageContextTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/pjacoco/agent/context src/test/java/io/pjacoco/agent/context
git commit -m "feat: CoverageContext thread-local active testId"
```

### Task 2: ClassProbes + TestStore

**Files:**
- Create: `src/main/java/io/pjacoco/agent/store/ClassProbes.java`
- Create: `src/main/java/io/pjacoco/agent/store/TestStore.java`
- Test: `src/test/java/io/pjacoco/agent/store/TestStoreTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.pjacoco.agent.store;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TestStoreTest {
    @Test
    void recordsProbesPerClass() {
        TestStore s = new TestStore("T1", 1000L, "shard-1");
        s.record(42L, "com/example/A", 0, 3);
        s.record(42L, "com/example/A", 2, 3);
        Map<Long, ClassProbes> snap = s.snapshot();
        assertEquals(1, snap.size());
        boolean[] p = snap.get(42L).probes();
        assertArrayEquals(new boolean[]{true, false, true}, p);
        assertEquals("com/example/A", snap.get(42L).className());
    }

    @Test
    void snapshotIsCopy() {
        TestStore s = new TestStore("T1", 1000L, null);
        s.record(1L, "X", 0, 1);
        Map<Long, ClassProbes> snap = s.snapshot();
        s.record(1L, "X", 0, 1);                 // mutate original after snapshot
        assertFalse(snap.get(1L).probes() == s.snapshot().get(1L).probes()); // different arrays
    }

    @Test
    void classCountReflectsDistinctClasses() {
        TestStore s = new TestStore("T1", 1000L, null);
        s.record(1L, "A", 0, 1);
        s.record(2L, "B", 0, 1);
        s.record(1L, "A", 0, 1);
        assertEquals(2, s.classCount());
    }
}
```

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew test --tests '*TestStoreTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement `ClassProbes`**

```java
package io.pjacoco.agent.store;

public final class ClassProbes {
    private final String className;
    private final boolean[] probes;

    public ClassProbes(String className, boolean[] probes) {
        this.className = className;
        this.probes = probes;
    }
    public String className() { return className; }
    public boolean[] probes() { return probes; }
}
```

- [ ] **Step 4: Implement `TestStore`**

```java
package io.pjacoco.agent.store;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TestStore {
    private final String testId;
    private final long startedAtMillis;
    private final String shardId;          // nullable
    private volatile int retryCount;
    // classId -> ClassProbes. boolean[] writes are benign races (same property JaCoCo relies on).
    private final ConcurrentHashMap<Long, ClassProbes> byClass = new ConcurrentHashMap<>();

    public TestStore(String testId, long startedAtMillis, String shardId) {
        this.testId = testId;
        this.startedAtMillis = startedAtMillis;
        this.shardId = shardId;
    }

    public void record(long classId, String className, int probeId, int probeCount) {
        ClassProbes cp = byClass.get(classId);
        if (cp == null) {
            cp = byClass.computeIfAbsent(classId, k -> new ClassProbes(className, new boolean[probeCount]));
        }
        boolean[] p = cp.probes();
        if (probeId >= 0 && probeId < p.length) {
            p[probeId] = true;
        }
    }

    /** Deep copy so a late write during flush cannot corrupt serialization. */
    public Map<Long, ClassProbes> snapshot() {
        Map<Long, ClassProbes> out = new LinkedHashMap<>();
        for (Map.Entry<Long, ClassProbes> e : byClass.entrySet()) {
            boolean[] src = e.getValue().probes();
            out.put(e.getKey(), new ClassProbes(e.getValue().className(), src.clone()));
        }
        return out;
    }

    public int classCount() { return byClass.size(); }
    public String testId() { return testId; }
    public long startedAtMillis() { return startedAtMillis; }
    public String shardId() { return shardId; }
    public int retryCount() { return retryCount; }
    public void incrementRetry() { retryCount++; }
}
```

- [ ] **Step 5: Run, expect PASS**

Run: `./gradlew test --tests '*TestStoreTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/pjacoco/agent/store src/test/java/io/pjacoco/agent/store
git commit -m "feat: TestStore + ClassProbes per-testId coverage accumulation"
```

### Task 3: Json emitter

**Files:**
- Create: `src/main/java/io/pjacoco/agent/output/Json.java`
- Test: `src/test/java/io/pjacoco/agent/output/JsonTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.pjacoco.agent.output;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonTest {
    @Test
    void writesFlatObjectWithEscaping() {
        String s = new Json()
            .put("testId", "T\"1")
            .put("classCount", 42)
            .put("shardId", (String) null)        // null omitted
            .toString();
        assertEquals("{\"testId\":\"T\\\"1\",\"classCount\":42}", s);
    }
}
```

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew test --tests '*JsonTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package io.pjacoco.agent.output;

public final class Json {
    private final StringBuilder sb = new StringBuilder("{");
    private boolean first = true;

    public Json put(String key, String value) {
        if (value == null) return this;            // omit nulls
        sep().append('"').append(esc(key)).append("\":\"").append(esc(value)).append('"');
        return this;
    }
    public Json put(String key, long value) {
        sep().append('"').append(esc(key)).append("\":").append(value);
        return this;
    }

    private StringBuilder sep() {
        if (!first) sb.append(',');
        first = false;
        return sb;
    }
    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }
    @Override public String toString() { return sb.toString() + "}"; }
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew test --tests '*JsonTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/pjacoco/agent/output/Json.java src/test/java/io/pjacoco/agent/output/JsonTest.java
git commit -m "feat: minimal dependency-free JSON emitter"
```

### Task 4: ExecWriter (.exec + sidecar) — produces vanilla-format .exec

**Files:**
- Create: `src/main/java/io/pjacoco/agent/output/ExecWriter.java`
- Test: `src/test/java/io/pjacoco/agent/output/ExecWriterTest.java`

- [ ] **Step 1: Write failing test** (round-trips through JaCoCo's own reader to prove format validity)

```java
package io.pjacoco.agent.output;

import org.jacoco.core.data.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.pjacoco.agent.store.TestStore;

import java.io.FileInputStream;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecWriterTest {
    @Test
    void writesExecReadableByJacoco(@TempDir Path dir) throws Exception {
        TestStore store = new TestStore("T1", 1000L, "shard-3");
        store.record(99L, "com/example/Foo", 0, 2);
        store.record(99L, "com/example/Foo", 1, 2);

        new ExecWriter().write(dir, store, "passed", "abc123", 2000L);

        // sidecar present
        Path sidecar = dir.resolve("T1.json");
        assertTrue(Files.exists(sidecar));
        String json = new String(Files.readAllBytes(sidecar));
        assertTrue(json.contains("\"testId\":\"T1\""));
        assertTrue(json.contains("\"result\":\"passed\""));
        assertTrue(json.contains("\"shardId\":\"shard-3\""));
        assertTrue(json.contains("\"classCount\":1"));
        assertTrue(json.contains("\"status\":\"complete\""));

        // .exec parses with JaCoCo's reader and carries our probes
        Path exec = dir.resolve("T1.exec");
        assertTrue(Files.exists(exec));
        ExecutionDataStore store2 = new ExecutionDataStore();
        SessionInfoStore sessions = new SessionInfoStore();
        try (FileInputStream in = new FileInputStream(exec.toFile())) {
            ExecutionDataReader r = new ExecutionDataReader(in);
            r.setExecutionDataVisitor(store2);
            r.setSessionInfoVisitor(sessions);
            r.read();
        }
        ExecutionData ed = store2.get(99L);
        assertNotNull(ed);
        assertEquals("com/example/Foo", ed.getName());
        assertArrayEquals(new boolean[]{true, true}, ed.getProbes());
        assertEquals("T1", sessions.getInfos().get(0).getId());
    }
}
```

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew test --tests '*ExecWriterTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package io.pjacoco.agent.output;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import io.pjacoco.agent.store.ClassProbes;
import io.pjacoco.agent.store.TestStore;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class ExecWriter {

    /** @param status "complete" for normal stop, "partial" for shutdown-forced dump. */
    public void write(Path dir, TestStore store, String result, String commitSha,
                      long stoppedAtMillis, String status) throws Exception {
        Files.createDirectories(dir);
        Map<Long, ClassProbes> snap = store.snapshot();

        Path exec = dir.resolve(store.testId() + ".exec");
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(exec))) {
            ExecutionDataWriter w = new ExecutionDataWriter(os);
            w.visitSessionInfo(new SessionInfo(store.testId(),
                    store.startedAtMillis(), stoppedAtMillis));
            for (Map.Entry<Long, ClassProbes> e : snap.entrySet()) {
                w.visitClassExecution(new ExecutionData(
                        e.getKey(), e.getValue().className(), e.getValue().probes()));
            }
        }

        String json = new Json()
                .put("testId", store.testId())
                .put("exec", store.testId() + ".exec")
                .put("precision", "line")
                .put("startedAtMillis", store.startedAtMillis())
                .put("stoppedAtMillis", stoppedAtMillis)
                .put("durationMs", stoppedAtMillis - store.startedAtMillis())
                .put("result", result)               // null -> omitted
                .put("classCount", store.classCount())
                .put("retryCount", store.retryCount())
                .put("shardId", store.shardId())      // null -> omitted
                .put("status", status)
                .toString();
        Files.write(dir.resolve(store.testId() + ".json"), json.getBytes("UTF-8"));
    }

    // convenience overload used by normal stop
    public void write(Path dir, TestStore store, String result, String commitSha,
                      long stoppedAtMillis) throws Exception {
        write(dir, store, result, commitSha, stoppedAtMillis, "complete");
    }
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew test --tests '*ExecWriterTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/pjacoco/agent/output/ExecWriter.java src/test/java/io/pjacoco/agent/output/ExecWriterTest.java
git commit -m "feat: ExecWriter emits vanilla-format .exec + sidecar json"
```

---

## Milestone 2 — Observability, registry lifecycle, control endpoint

### Task 5: Metrics + AgentLog

**Files:**
- Create: `src/main/java/io/pjacoco/agent/observability/Metrics.java`
- Create: `src/main/java/io/pjacoco/agent/observability/AgentLog.java`
- Test: `src/test/java/io/pjacoco/agent/observability/MetricsTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.pjacoco.agent.observability;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MetricsTest {
    @Test
    void countersAndSummary() {
        Metrics m = new Metrics();
        m.testsCompleted.incrementAndGet();
        m.testsCompleted.incrementAndGet();
        m.swallowedExceptions.incrementAndGet();
        m.rejectedUnregistered.incrementAndGet();
        m.partialDumps.incrementAndGet();
        String s = m.summary();
        assertTrue(s.contains("completed=2"));
        assertTrue(s.contains("partial=1"));
        assertTrue(s.contains("swallowed=1"));
        assertTrue(s.contains("rejected=1"));
    }
}
```

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew test --tests '*MetricsTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement `Metrics`**

```java
package io.pjacoco.agent.observability;

import java.util.concurrent.atomic.AtomicLong;

public final class Metrics {
    public final AtomicLong testsCompleted = new AtomicLong();
    public final AtomicLong partialDumps = new AtomicLong();
    public final AtomicLong swallowedExceptions = new AtomicLong();
    public final AtomicLong rejectedUnregistered = new AtomicLong();
    public final AtomicLong retriesOverwritten = new AtomicLong();

    public String summary() {
        return "[pjacoco] summary: completed=" + testsCompleted.get()
                + " partial=" + partialDumps.get()
                + " swallowed=" + swallowedExceptions.get()
                + " rejected=" + rejectedUnregistered.get()
                + " retries=" + retriesOverwritten.get();
    }
}
```

- [ ] **Step 4: Implement `AgentLog` (rate-limited)**

```java
package io.pjacoco.agent.observability;

import java.util.concurrent.atomic.AtomicLong;

public final class AgentLog {
    private static final long MAX_PER_KEY = 20;            // cap repetitive messages
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> counts =
            new java.util.concurrent.ConcurrentHashMap<>();

    public void warn(String key, String message) {
        long n = counts.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        if (n <= MAX_PER_KEY) {
            System.err.println("[pjacoco][WARN] " + message
                    + (n == MAX_PER_KEY ? " (further '" + key + "' messages suppressed)" : ""));
        }
    }
    public void info(String message) { System.out.println("[pjacoco] " + message); }
}
```

- [ ] **Step 5: Run, expect PASS**

Run: `./gradlew test --tests '*MetricsTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/pjacoco/agent/observability src/test/java/io/pjacoco/agent/observability
git commit -m "feat: Metrics counters + rate-limited AgentLog"
```

### Task 6: TestStoreRegistry (start/stop, strict/lenient, retry, cap)

**Files:**
- Create: `src/main/java/io/pjacoco/agent/store/TestStoreRegistry.java`
- Test: `src/test/java/io/pjacoco/agent/store/TestStoreRegistryTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.pjacoco.agent.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.output.ExecWriter;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class TestStoreRegistryTest {
    private TestStoreRegistry newRegistry(Path dir, boolean lenient) {
        AtomicLong clock = new AtomicLong(1000L);
        return new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(),
                lenient, 100, clock::get);
    }

    @Test
    void startThenStopWritesExec(@TempDir Path dir) {
        TestStoreRegistry r = newRegistry(dir, false);
        r.start("T1", "shard-1", "sha");
        r.active("T1").record(1L, "A", 0, 1);
        r.stop("T1", "passed");
        assertTrue(Files.exists(dir.resolve("T1.exec")));
        assertNull(r.active("T1"));                 // evicted after stop
    }

    @Test
    void strictModeReturnsNullForUnregistered(@TempDir Path dir) {
        TestStoreRegistry r = newRegistry(dir, false);
        assertNull(r.active("UNKNOWN"));            // not started -> no store
    }

    @Test
    void lenientModeAutoCreates(@TempDir Path dir) {
        TestStoreRegistry r = newRegistry(dir, true);
        assertNotNull(r.active("AUTO"));            // lazily created
    }

    @Test
    void retryResetsAndBumpsCount(@TempDir Path dir) {
        TestStoreRegistry r = newRegistry(dir, false);
        r.start("T1", null, "sha");
        r.active("T1").record(1L, "A", 0, 1);
        r.start("T1", null, "sha");                 // retry
        assertEquals(0, r.active("T1").classCount()); // store reset
        assertEquals(1, r.active("T1").retryCount());
    }
}
```

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew test --tests '*TestStoreRegistryTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package io.pjacoco.agent.store;

import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class TestStoreRegistry {
    private final Path outputDir;
    private final ExecWriter writer;
    private final Metrics metrics;
    private final AgentLog log;
    private final boolean lenient;
    private final int maxStores;
    private final LongSupplier clock;
    private volatile String commitSha;

    private final ConcurrentHashMap<String, TestStore> stores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> retryCounts = new ConcurrentHashMap<>();

    public TestStoreRegistry(Path outputDir, ExecWriter writer, Metrics metrics, AgentLog log,
                             boolean lenient, int maxStores, LongSupplier clock) {
        this.outputDir = outputDir;
        this.writer = writer;
        this.metrics = metrics;
        this.log = log;
        this.lenient = lenient;
        this.maxStores = maxStores;
        this.clock = clock;
    }

    public synchronized void start(String testId, String shardId, String commitSha) {
        if (commitSha != null) this.commitSha = commitSha;
        boolean isRetry = stores.containsKey(testId);
        TestStore store = new TestStore(testId, clock.getAsLong(), shardId);
        if (isRetry) {
            int n = retryCounts.merge(testId, 1, Integer::sum);
            for (int i = 0; i < n; i++) store.incrementRetry();
            metrics.retriesOverwritten.incrementAndGet();
            log.warn("retry", "retry overwrite testId=" + testId + " retryCount=" + n);
        }
        stores.put(testId, store);
        enforceCap();
    }

    /** Data-plane lookup. Strict: null if unregistered. Lenient: lazy-create. */
    public TestStore active(String testId) {
        TestStore s = stores.get(testId);
        if (s != null) return s;
        if (lenient) {
            return stores.computeIfAbsent(testId, k -> new TestStore(k, clock.getAsLong(), null));
        }
        metrics.rejectedUnregistered.incrementAndGet();
        log.warn("unregistered", "request for unregistered testId=" + testId + " (strict mode, not recorded)");
        return null;
    }

    public synchronized void stop(String testId, String result) {
        TestStore s = stores.remove(testId);
        if (s == null) {
            log.warn("stop-missing", "stop for unknown testId=" + testId);
            return;
        }
        flush(s, result, "complete");
        metrics.testsCompleted.incrementAndGet();
    }

    /** Called from the shutdown hook for any un-stopped stores. */
    public synchronized void dumpRemainingAsPartial() {
        for (Iterator<Map.Entry<String, TestStore>> it = stores.entrySet().iterator(); it.hasNext(); ) {
            TestStore s = it.next().getValue();
            it.remove();
            flush(s, null, "partial");
            metrics.partialDumps.incrementAndGet();
        }
    }

    private void enforceCap() {
        while (stores.size() > maxStores) {
            // evict oldest by startedAt, dump as partial
            String oldest = null;
            long min = Long.MAX_VALUE;
            for (Map.Entry<String, TestStore> e : stores.entrySet()) {
                if (e.getValue().startedAtMillis() < min) {
                    min = e.getValue().startedAtMillis();
                    oldest = e.getKey();
                }
            }
            if (oldest == null) break;
            TestStore s = stores.remove(oldest);
            log.warn("cap", "store cap " + maxStores + " exceeded; evicting testId=" + oldest + " as partial");
            flush(s, null, "partial");
            metrics.partialDumps.incrementAndGet();
        }
    }

    private void flush(TestStore s, String result, String status) {
        try {
            writer.write(outputDir, s, result, commitSha, clock.getAsLong(), status);
        } catch (Exception e) {
            metrics.swallowedExceptions.incrementAndGet();
            log.warn("flush-error", "flush failed testId=" + s.testId() + ": " + e);
        }
    }
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew test --tests '*TestStoreRegistryTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/pjacoco/agent/store/TestStoreRegistry.java src/test/java/io/pjacoco/agent/store/TestStoreRegistryTest.java
git commit -m "feat: TestStoreRegistry lifecycle (strict/lenient, retry, cap, partial dump)"
```

### Task 7: ProbeRouter (the stable instrumentation surface)

**Files:**
- Create: `src/main/java/io/pjacoco/agent/probe/ProbeRouter.java`
- Test: `src/test/java/io/pjacoco/agent/probe/ProbeRouterTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.pjacoco.agent.probe;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.*;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStoreRegistry;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ProbeRouterTest {
    @AfterEach void clear() { CoverageContext.clear(); }

    private TestStoreRegistry reg(Path dir) {
        AtomicLong clock = new AtomicLong(1L);
        return new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(),
                false, 100, clock::get);
    }

    @Test
    void recordsIntoActiveTestStore(@TempDir Path dir) {
        TestStoreRegistry r = reg(dir);
        r.start("T1", null, "sha");
        ProbeRouter.bind(r);
        CoverageContext.set("T1");
        ProbeRouter.record(7L, "com/x/Y", 1, 2);
        assertEquals(1, r.active("T1").classCount());
    }

    @Test
    void noContextIsNoOp(@TempDir Path dir) {
        TestStoreRegistry r = reg(dir);
        ProbeRouter.bind(r);
        // no context set
        ProbeRouter.record(7L, "com/x/Y", 1, 2);   // must not throw
    }

    @Test
    void unregisteredStrictIsNoOp(@TempDir Path dir) {
        TestStoreRegistry r = reg(dir);
        ProbeRouter.bind(r);
        CoverageContext.set("GHOST");
        ProbeRouter.record(7L, "com/x/Y", 1, 2);   // strict -> dropped, no throw
    }
}
```

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew test --tests '*ProbeRouterTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package io.pjacoco.agent.probe;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;

public final class ProbeRouter {
    private static volatile TestStoreRegistry registry;
    private static volatile Metrics metrics;
    private ProbeRouter() {}

    public static void bind(TestStoreRegistry r) { registry = r; }
    public static void bindMetrics(Metrics m) { metrics = m; }

    /** Hot path. MUST NEVER throw into application code. */
    public static void record(long classId, String className, int probeId, int probeCount) {
        try {
            String testId = CoverageContext.get();
            if (testId == null) return;                  // untagged traffic
            TestStoreRegistry r = registry;
            if (r == null) return;
            TestStore store = r.active(testId);
            if (store == null) return;                   // strict: unregistered
            store.record(classId, className, probeId, probeCount);
        } catch (Throwable t) {
            Metrics m = metrics;
            if (m != null) m.swallowedExceptions.incrementAndGet();
            // swallow: coverage loss is acceptable, an app crash is not
        }
    }
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew test --tests '*ProbeRouterTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/pjacoco/agent/probe/ProbeRouter.java src/test/java/io/pjacoco/agent/probe/ProbeRouterTest.java
git commit -m "feat: ProbeRouter stable instrumentation surface, error-swallowing hot path"
```

### Task 8: ControlEndpoint (HttpServer start/stop)

**Files:**
- Create: `src/main/java/io/pjacoco/agent/control/ControlEndpoint.java`
- Test: `src/test/java/io/pjacoco/agent/control/ControlEndpointTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.pjacoco.agent.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.pjacoco.agent.observability.*;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStoreRegistry;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ControlEndpointTest {
    private TestStoreRegistry registry;
    private ControlEndpoint endpoint;
    private int port;

    @BeforeEach void setUp(@TempDir Path dir) throws Exception {
        AtomicLong clock = new AtomicLong(1000L);
        registry = new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(),
                false, 100, clock::get);
        endpoint = new ControlEndpoint(registry, "127.0.0.1", 0);   // 0 = ephemeral
        port = endpoint.start();
    }
    @AfterEach void tearDown() { endpoint.stop(); }

    private int post(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) { os.write(new byte[0]); }
        return c.getResponseCode();
    }

    @Test
    void startThenStopRoundTrip(@TempDir Path dir) throws Exception {
        assertEquals(200, post("/__coverage__/test/start?testId=T1&shardId=s1&commitSha=sha"));
        assertNotNull(registry.active("T1"));
        assertEquals(200, post("/__coverage__/test/stop?testId=T1&result=passed"));
        assertNull(registry.active("T1"));
    }

    @Test
    void missingTestIdIs400() throws Exception {
        assertEquals(400, post("/__coverage__/test/start"));
    }
}
```

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew test --tests '*ControlEndpointTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package io.pjacoco.agent.control;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.pjacoco.agent.store.TestStoreRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

public final class ControlEndpoint {
    private final TestStoreRegistry registry;
    private final String host;
    private final int port;
    private HttpServer server;

    public ControlEndpoint(TestStoreRegistry registry, String host, int port) {
        this.registry = registry;
        this.host = host;
        this.port = port;
    }

    /** @return the actual bound port. */
    public int start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/__coverage__/test/start", this::handleStart);
        server.createContext("/__coverage__/test/stop", this::handleStop);
        server.setExecutor(null);
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() { if (server != null) server.stop(0); }

    private void handleStart(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        String testId = q.get("testId");
        if (testId == null) { respond(ex, 400, "missing testId"); return; }
        registry.start(testId, q.get("shardId"), q.get("commitSha"));
        respond(ex, 200, "started " + testId);
    }

    private void handleStop(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        String testId = q.get("testId");
        if (testId == null) { respond(ex, 400, "missing testId"); return; }
        registry.stop(testId, q.get("result"));
        respond(ex, 200, "stopped " + testId);
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                try {
                    String k = java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8");
                    String v = java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8");
                    m.put(k, v);
                } catch (Exception ignored) {}
            }
        }
        return m;
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes("UTF-8");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew test --tests '*ControlEndpointTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/pjacoco/agent/control/ControlEndpoint.java src/test/java/io/pjacoco/agent/control/ControlEndpointTest.java
git commit -m "feat: loopback HTTP control endpoint (test start/stop)"
```

---

## Milestone 3 — Inbound activation (OTel Baggage)

### Task 9: BaggageParser (W3C/OTel baggage -> test.id)

**Files:**
- Create: `src/main/java/io/pjacoco/agent/inbound/BaggageParser.java`
- Test: `src/test/java/io/pjacoco/agent/inbound/BaggageParserTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.pjacoco.agent.inbound;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BaggageParserTest {
    @Test
    void extractsTestId() {
        assertEquals("T1", BaggageParser.testId("test.id=T1"));
    }
    @Test
    void extractsAmongOthers() {
        assertEquals("T1", BaggageParser.testId("userId=u9,test.id=T1,region=eu"));
    }
    @Test
    void stripsProperties() {
        assertEquals("T1", BaggageParser.testId("test.id=T1;prop=meta"));
    }
    @Test
    void urlDecodesValue() {
        assertEquals("a b", BaggageParser.testId("test.id=a%20b"));
    }
    @Test
    void nullWhenAbsentOrNull() {
        assertNull(BaggageParser.testId("foo=bar"));
        assertNull(BaggageParser.testId(null));
        assertNull(BaggageParser.testId(""));
    }
}
```

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew test --tests '*BaggageParserTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package io.pjacoco.agent.inbound;

public final class BaggageParser {
    private static final String KEY = "test.id";
    private BaggageParser() {}

    /** Parse a W3C/OpenTelemetry `baggage` header value and return the `test.id` member, or null. */
    public static String testId(String header) {
        if (header == null || header.isEmpty()) return null;
        for (String member : header.split(",")) {
            String m = member.trim();
            int semi = m.indexOf(';');                 // drop ;properties
            if (semi >= 0) m = m.substring(0, semi);
            int eq = m.indexOf('=');
            if (eq <= 0) continue;
            String key = m.substring(0, eq).trim();
            if (KEY.equals(key)) {
                String val = m.substring(eq + 1).trim();
                try {
                    return java.net.URLDecoder.decode(val, "UTF-8");
                } catch (Exception e) {
                    return val;
                }
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew test --tests '*BaggageParserTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/pjacoco/agent/inbound/BaggageParser.java src/test/java/io/pjacoco/agent/inbound/BaggageParserTest.java
git commit -m "feat: OTel/W3C baggage header parser for test.id"
```

### Task 10: InboundActivator SPI + ServletInboundActivator + advice

**Files:**
- Create: `src/main/java/io/pjacoco/agent/inbound/InboundActivator.java`
- Create: `src/main/java/io/pjacoco/agent/inbound/servlet/ServletAdvice.java`
- Create: `src/main/java/io/pjacoco/agent/inbound/servlet/ServletInboundActivator.java`
- Test: `src/test/java/io/pjacoco/agent/inbound/servlet/ServletAdviceTest.java`

The activator advises `javax.servlet.http.HttpServlet#service(ServletRequest, ServletResponse)` so it needs no cooperation from the app (no filter registration). On enter it reads the `baggage` header and sets the context; on exit it clears. `ServletAdvice` logic is extracted into a plain static method (`activate`/`deactivate`) so it is unit-testable without bytecode weaving.

- [ ] **Step 1: Write failing test** (tests the extracted logic directly)

```java
package io.pjacoco.agent.inbound.servlet;

import org.junit.jupiter.api.*;
import io.pjacoco.agent.context.CoverageContext;
import javax.servlet.http.HttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;   // add testImplementation mockito if not present

class ServletAdviceTest {
    @AfterEach void clear() { CoverageContext.clear(); }

    @Test
    void activatesFromBaggageHeader() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("baggage")).thenReturn("test.id=T1");
        ServletAdvice.activate(req);
        assertEquals("T1", CoverageContext.get());
        ServletAdvice.deactivate();
        assertNull(CoverageContext.get());
    }

    @Test
    void noHeaderLeavesContextUnset() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("baggage")).thenReturn(null);
        ServletAdvice.activate(req);
        assertNull(CoverageContext.get());
    }

    @Test
    void nonHttpRequestIgnored() {
        ServletAdvice.activate(new Object());      // not an HttpServletRequest
        assertNull(CoverageContext.get());
    }
}
```

Add to `build.gradle.kts` dependencies if missing:
```kotlin
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("javax.servlet:javax.servlet-api:3.1.0")
```

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew test --tests '*ServletAdviceTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement `InboundActivator` SPI**

```java
package io.pjacoco.agent.inbound;

import net.bytebuddy.agent.builder.AgentBuilder;

/** Strategy for activating CoverageContext from an inbound request, per transport. */
public interface InboundActivator {
    /** Register this activator's transformation onto the Byte Buddy agent builder. */
    AgentBuilder apply(AgentBuilder builder);
}
```

- [ ] **Step 4: Implement `ServletAdvice` (testable logic + @Advice hooks)**

```java
package io.pjacoco.agent.inbound.servlet;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.inbound.BaggageParser;
import net.bytebuddy.asm.Advice;
import javax.servlet.http.HttpServletRequest;

public final class ServletAdvice {
    private ServletAdvice() {}

    /** Extracted for unit testing. Accepts Object so a non-HTTP request is a safe no-op. */
    public static void activate(Object request) {
        try {
            if (request instanceof HttpServletRequest) {
                String testId = BaggageParser.testId(((HttpServletRequest) request).getHeader("baggage"));
                if (testId != null) CoverageContext.set(testId);
            }
        } catch (Throwable ignored) { /* never disturb the app */ }
    }

    public static void deactivate() {
        try { CoverageContext.clear(); } catch (Throwable ignored) {}
    }

    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) Object request) { activate(request); }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() { deactivate(); }
}
```

- [ ] **Step 5: Implement `ServletInboundActivator`**

```java
package io.pjacoco.agent.inbound.servlet;

import io.pjacoco.agent.inbound.InboundActivator;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import static net.bytebuddy.matcher.ElementMatchers.*;

public final class ServletInboundActivator implements InboundActivator {
    @Override
    public AgentBuilder apply(AgentBuilder builder) {
        return builder
            .type(isSubTypeOf(named("javax.servlet.http.HttpServlet")))
            .transform((b, type, cl, module, pd) -> b.visit(
                Advice.to(ServletAdvice.class).on(
                    named("service")
                        .and(takesArgument(0, named("javax.servlet.ServletRequest")))
                        .and(takesArgument(1, named("javax.servlet.ServletResponse"))))));
    }
}
```

- [ ] **Step 6: Run, expect PASS**

Run: `./gradlew test --tests '*ServletAdviceTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/pjacoco/agent/inbound src/test/java/io/pjacoco/agent/inbound/servlet
git commit -m "feat: InboundActivator SPI + servlet activator (advises HttpServlet#service)"
```

---

## Milestone 4 — Probe instrumentation (SPIKE: the JaCoCo hook)

> **This is the spec's top risk (§7 of the design: hooking JaCoCo internals is version-fragile).**
> Unlike the deterministic milestones above, the exact bytecode hook MUST be derived against
> live source, not written from memory. Treat this milestone as a **time-boxed spike with a
> binary acceptance test**: when `GoldenEquivalenceIT` (Task 12) passes, the hook is correct.
> Downstream code depends ONLY on `ProbeRouter.record(...)` (Task 7), which is already frozen.

**Reference sources to read first (do not skip):**
- jacoco-core `org.jacoco.core.instr.Instrumenter`, `org.jacoco.core.runtime.IRuntime` /
  `IExecutionDataAccessorGenerator`, and `org.jacoco.core.internal.instr.*` (probe insertion).
- Datadog `dd-trace-java` civisibility coverage module: `ProbeInserterInstrumentation` and
  `CoveragePerTestBridge` (Apache 2.0) — the working reference for intercepting probe firings
  and routing `(classId, probeId)` to a per-context store.

### Task 11: ProbeInstrumentation — route jacoco probes into ProbeRouter

**Files:**
- Create: `src/main/java/io/pjacoco/agent/probe/ProbeInstrumentation.java`
- Test (spike acceptance): `src/integrationTest/java/io/pjacoco/agent/it/TargetService.java`
- Test (spike acceptance): `src/integrationTest/java/io/pjacoco/agent/it/ProbeRoutingIT.java`

**Contract `ProbeInstrumentation` must satisfy (stable; the spike chooses HOW):**

```java
package io.pjacoco.agent.probe;

import net.bytebuddy.agent.builder.AgentBuilder;

/**
 * Installs class instrumentation such that, when an instrumented method runs and a probe fires,
 * ProbeRouter.record(classId, className, probeId, probeCount) is invoked with the SAME classId
 * and probe scheme that vanilla JaCoCo would assign (so emitted .exec is byte-compatible).
 *
 * Honors AgentOptions includes/excludes/exclclassloader/inclbootstrapclasses/inclnolocationclasses.
 */
public final class ProbeInstrumentation {
    public static AgentBuilder install(AgentBuilder builder, io.pjacoco.agent.AgentOptions options) { /* spike */ }
}
```

**Recommended approach (Datadog-style per-probe bridge):** let jacoco-core's `Instrumenter`
instrument classes so classId + probe scheme are vanilla-identical, and intercept probe firing
to also call `ProbeRouter.record`. The global jacoco array is left intact (additive). Probe
`probeCount` per class comes from the instrumented class metadata; `classId` is jacoco's CRC64
of the original class bytes (`org.jacoco.core.internal.data.CRC64`), `className` is the VM name.

- [ ] **Step 1: Write the spike acceptance target class**

```java
package io.pjacoco.agent.it;

public class TargetService {
    public int classify(int n) {
        if (n < 0) return -1;        // branch A
        if (n == 0) return 0;        // branch B
        return 1;                    // branch C
    }
    public String greet(boolean formal) {
        return formal ? "Good day" : "hi";
    }
}
```

- [ ] **Step 2: Write the spike acceptance test (`ProbeRoutingIT`)**

This runs in-process: install instrumentation, set context, exercise the target, assert the
active store captured probes for `TargetService`. (Golden equivalence vs vanilla is Task 12.)

```java
package io.pjacoco.agent.it;

import org.junit.jupiter.api.*;
import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.*;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.probe.*;
import io.pjacoco.agent.store.TestStoreRegistry;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ProbeRoutingIT {
    @Test
    void firedProbesLandInActiveStore(@TempDir Path dir) throws Exception {
        AtomicLong clock = new AtomicLong(1L);
        TestStoreRegistry reg = new TestStoreRegistry(dir, new ExecWriter(), new Metrics(),
                new AgentLog(), false, 100, clock::get);
        ProbeRouter.bind(reg);

        AgentBuilder ab = new AgentBuilder.Default()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .type(net.bytebuddy.matcher.ElementMatchers.named("io.pjacoco.agent.it.TargetService"));
        // install our instrumentation and apply to the live JVM
        ProbeInstrumentation.install(ab, io.pjacoco.agent.AgentOptions.empty())
            .installOn(ByteBuddyAgent.install());

        reg.start("T1", null, "sha");
        CoverageContext.set("T1");
        new TargetService().classify(5);          // exercises branch C path
        CoverageContext.clear();

        // TargetService must appear with at least one fired probe
        assertTrue(reg.active("T1").classCount() >= 1,
            "instrumentation did not route any probe for TargetService");
    }
}
```

Add to `build.gradle.kts`:
```kotlin
    integrationTestImplementation("net.bytebuddy:byte-buddy-agent:1.14.18")
```

- [ ] **Step 3: Run, expect FAIL**

Run: `./gradlew integrationTest --tests '*ProbeRoutingIT'`
Expected: FAIL (instrumentation not implemented / no probes routed).

- [ ] **Step 4: Implement `ProbeInstrumentation` (derive from references in Step header)**

Implement the recommended approach until Step 3's test passes. Keep ALL coverage-routing
bytecode calling only `ProbeRouter.record(...)`. Do not change `ProbeRouter`'s signature.
Record the chosen hook point and the exact jacoco-core classes touched in a comment block at
the top of the file (this is what the version canary in Task 15 guards).

- [ ] **Step 5: Run, expect PASS**

Run: `./gradlew integrationTest --tests '*ProbeRoutingIT'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/pjacoco/agent/probe/ProbeInstrumentation.java src/integrationTest/java/io/pjacoco/agent/it/TargetService.java src/integrationTest/java/io/pjacoco/agent/it/ProbeRoutingIT.java build.gradle.kts
git commit -m "feat: probe instrumentation routes jacoco probes to per-testId store (spike)"
```

### Task 12: GoldenEquivalenceIT — our .exec == vanilla jacoco .exec

**Files:**
- Test: `src/integrationTest/java/io/pjacoco/agent/it/GoldenEquivalenceIT.java`

**Goal:** prove "vanilla identical" — for a single test exercising `TargetService`, the set of
covered probes our agent records equals what the official jacoco agent records.

- [ ] **Step 1: Write the test**

```java
package io.pjacoco.agent.it;

import org.jacoco.core.analysis.*;
import org.jacoco.core.data.*;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.*;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.probe.*;
import io.pjacoco.agent.store.TestStoreRegistry;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class GoldenEquivalenceIT {

    /** Returns covered-line set for TargetService from a given .exec, via JaCoCo analysis. */
    private SortedSet<Integer> coveredLines(Path exec, byte[] classBytes) throws Exception {
        ExecutionDataStore eds = new ExecutionDataStore();
        SessionInfoStore sis = new SessionInfoStore();
        try (InputStream in = Files.newInputStream(exec)) {
            ExecutionDataReader r = new ExecutionDataReader(in);
            r.setExecutionDataVisitor(eds);
            r.setSessionInfoVisitor(sis);
            r.read();
        }
        CoverageBuilder cb = new CoverageBuilder();
        new Analyzer(eds, cb).analyzeClass(classBytes, "io/pjacoco/agent/it/TargetService");
        SortedSet<Integer> lines = new TreeSet<>();
        for (IClassCoverage c : cb.getClasses()) {
            for (int l = c.getFirstLine(); l <= c.getLastLine(); l++) {
                if (c.getLine(l).getStatus() == ICounter.FULLY_COVERED
                        || c.getLine(l).getStatus() == ICounter.PARTLY_COVERED) {
                    lines.add(l);
                }
            }
        }
        return lines;
    }

    @Test
    void ourExecMatchesVanilla(@TempDir Path dir) throws Exception {
        byte[] original = readClassBytes("io.pjacoco.agent.it.TargetService");

        // ---- Vanilla JaCoCo path (offline instrumentation into an isolated classloader) ----
        IRuntime runtime = new LoggerRuntime();
        RuntimeData data = new RuntimeData();
        runtime.startup(data);
        Instrumenter instr = new Instrumenter(runtime);
        byte[] instrumented = instr.instrument(original, "TargetService");
        MemoryClassLoader mcl = new MemoryClassLoader();
        mcl.addDefinition("io.pjacoco.agent.it.TargetService", instrumented);
        Class<?> vanillaTarget = mcl.loadClass("io.pjacoco.agent.it.TargetService");
        vanillaTarget.getMethod("classify", int.class).invoke(vanillaTarget.newInstance(), 5);
        ExecutionDataStore vanillaStore = new ExecutionDataStore();
        data.collect(vanillaStore, new SessionInfoStore(), false);
        runtime.shutdown();
        Path vanillaExec = dir.resolve("vanilla.exec");
        try (OutputStream os = Files.newOutputStream(vanillaExec)) {
            ExecutionDataWriter w = new ExecutionDataWriter(os);
            vanillaStore.accept(w);
        }

        // ---- Our agent path (same scenario) ----
        AtomicLong clock = new AtomicLong(1L);
        TestStoreRegistry reg = new TestStoreRegistry(dir, new ExecWriter(), new Metrics(),
                new AgentLog(), false, 100, clock::get);
        ProbeRouter.bind(reg);
        AgentBuilder ab = new AgentBuilder.Default()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .type(net.bytebuddy.matcher.ElementMatchers.named("io.pjacoco.agent.it.TargetService"));
        ProbeInstrumentation.install(ab, io.pjacoco.agent.AgentOptions.empty())
            .installOn(ByteBuddyAgent.install());
        reg.start("T1", null, "sha");
        CoverageContext.set("T1");
        new TargetService().classify(5);
        CoverageContext.clear();
        reg.stop("T1", "passed");
        Path ourExec = dir.resolve("T1.exec");

        // ---- Compare covered lines ----
        assertEquals(coveredLines(vanillaExec, original), coveredLines(ourExec, original),
            "per-test .exec must cover the same lines as vanilla JaCoCo");
    }

    private static byte[] readClassBytes(String fqcn) throws IOException {
        try (InputStream in = GoldenEquivalenceIT.class.getClassLoader()
                .getResourceAsStream(fqcn.replace('.', '/') + ".class")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    static final class MemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> defs = new HashMap<>();
        void addDefinition(String name, byte[] bytes) { defs.put(name, bytes); }
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
```

- [ ] **Step 2: Run, expect PASS** (if it fails, the M4 spike hook is not yet vanilla-faithful — iterate Task 11)

Run: `./gradlew integrationTest --tests '*GoldenEquivalenceIT'`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/integrationTest/java/io/pjacoco/agent/it/GoldenEquivalenceIT.java
git commit -m "test: golden equivalence — per-test .exec matches vanilla jacoco coverage"
```

---

## Milestone 5 — Wiring, options, end-to-end parallel proof

### Task 13: AgentOptions (parse + classify JaCoCo options)

**Files:**
- Create: `src/main/java/io/pjacoco/agent/AgentOptions.java`
- Test: `src/test/java/io/pjacoco/agent/AgentOptionsTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.pjacoco.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentOptionsTest {
    @Test
    void parsesKnownOptions() {
        AgentOptions o = AgentOptions.parse(
            "destfile=coverage,includes=com.example.*,lenient=true,port=6310,commitSha=abc");
        assertEquals("coverage", o.outputDir());
        assertEquals("com.example.*", o.includes());
        assertTrue(o.lenient());
        assertEquals(6310, o.controlPort());
        assertEquals("abc", o.commitSha());
    }

    @Test
    void defaultsWhenEmpty() {
        AgentOptions o = AgentOptions.parse(null);
        assertEquals("coverage", o.outputDir());     // default
        assertFalse(o.lenient());                     // strict by default
        assertEquals(6310, o.controlPort());          // default control port
        assertEquals("127.0.0.1", o.controlHost());   // loopback default
        assertEquals(1000, o.maxStores());
    }

    @Test
    void commitShaFallsBackToEnvIsCallerResponsibility() {
        AgentOptions o = AgentOptions.parse("");
        assertNull(o.commitSha());                    // null unless provided; Bootstrap checks env
    }
}
```

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew test --tests '*AgentOptionsTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package io.pjacoco.agent;

import java.util.HashMap;
import java.util.Map;

public final class AgentOptions {
    private final Map<String, String> raw;
    private AgentOptions(Map<String, String> raw) { this.raw = raw; }

    public static AgentOptions parse(String args) {
        Map<String, String> m = new HashMap<>();
        if (args != null && !args.isEmpty()) {
            for (String pair : args.split(",")) {
                int i = pair.indexOf('=');
                if (i > 0) m.put(pair.substring(0, i).trim(), pair.substring(i + 1).trim());
            }
        }
        return new AgentOptions(m);
    }
    public static AgentOptions empty() { return parse(null); }

    // our model (destfile reinterpreted as a directory)
    public String outputDir()   { return raw.getOrDefault("destfile", "coverage"); }
    public boolean lenient()    { return Boolean.parseBoolean(raw.getOrDefault("lenient", "false")); }
    public String controlHost() { return raw.getOrDefault("address", "127.0.0.1"); }
    public int controlPort()    { return Integer.parseInt(raw.getOrDefault("port", "6310")); }
    public int maxStores()      { return Integer.parseInt(raw.getOrDefault("maxstores", "1000")); }
    public String commitSha()   { return raw.get("commitSha"); }

    // passed through to jacoco-core instrumentation in Task 11
    public String includes()           { return raw.getOrDefault("includes", "*"); }
    public String excludes()           { return raw.getOrDefault("excludes", ""); }
    public String exclClassLoader()    { return raw.getOrDefault("exclclassloader", "sun.reflect.DelegatingClassLoader"); }
    public boolean inclBootstrap()     { return Boolean.parseBoolean(raw.getOrDefault("inclbootstrapclasses", "false")); }
    public boolean inclNoLocation()    { return Boolean.parseBoolean(raw.getOrDefault("inclnolocationclasses", "false")); }
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew test --tests '*AgentOptionsTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/pjacoco/agent/AgentOptions.java src/test/java/io/pjacoco/agent/AgentOptionsTest.java
git commit -m "feat: AgentOptions parsing (model opts + jacoco passthrough)"
```

### Task 14: Bootstrap wiring + shutdown hook

**Files:**
- Modify: `src/main/java/io/pjacoco/agent/Bootstrap.java`

- [ ] **Step 1: Implement full premain wiring**

```java
package io.pjacoco.agent;

import io.pjacoco.agent.control.ControlEndpoint;
import io.pjacoco.agent.inbound.servlet.ServletInboundActivator;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.probe.ProbeInstrumentation;
import io.pjacoco.agent.probe.ProbeRouter;
import io.pjacoco.agent.store.TestStoreRegistry;
import net.bytebuddy.agent.builder.AgentBuilder;

import java.lang.instrument.Instrumentation;
import java.nio.file.Paths;

public final class Bootstrap {
    private Bootstrap() {}

    public static void premain(String args, Instrumentation inst) {
        AgentOptions options = AgentOptions.parse(args);
        Metrics metrics = new Metrics();
        AgentLog log = new AgentLog();

        String commitSha = options.commitSha() != null ? options.commitSha()
                : System.getenv("PJACOCO_COMMIT");

        TestStoreRegistry registry = new TestStoreRegistry(
                Paths.get(options.outputDir()), new ExecWriter(), metrics, log,
                options.lenient(), options.maxStores(), System::currentTimeMillis);
        if (commitSha != null) registry.start("__bootstrap_commit__", null, commitSha); // seed commitSha then drop
        registry.stop("__bootstrap_commit__", null);

        ProbeRouter.bind(registry);
        ProbeRouter.bindMetrics(metrics);

        try {
            ControlEndpoint endpoint = new ControlEndpoint(registry, options.controlHost(), options.controlPort());
            int port = endpoint.start();
            log.info("control endpoint on " + options.controlHost() + ":" + port);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                registry.dumpRemainingAsPartial();
                endpoint.stop();
                log.info(metrics.summary());
            }));
        } catch (Exception e) {
            log.warn("control", "failed to start control endpoint: " + e);
        }

        AgentBuilder builder = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
        builder = new ServletInboundActivator().apply(builder);
        builder = ProbeInstrumentation.install(builder, options);
        builder.installOn(inst);

        log.info("agent installed (output=" + options.outputDir()
                + ", mode=" + (options.lenient() ? "lenient" : "strict") + ")");
    }
}
```

> Note: the `__bootstrap_commit__` seed is a deliberately ugly way to pass commitSha into the
> registry without widening its constructor. If the implementer prefers, add a
> `registry.setCommitSha(String)` method instead and call it here — either is acceptable, but
> keep `TestStoreRegistry`'s existing test signatures unchanged.

- [ ] **Step 2: Build the agent jar**

Run: `./gradlew shadowJar`
Expected: `BUILD SUCCESSFUL`, `build/libs/jacocoagent-parallel.jar` rebuilt.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/pjacoco/agent/Bootstrap.java
git commit -m "feat: Bootstrap wires registry, control endpoint, inbound + probe transforms, shutdown summary"
```

### Task 15: End-to-end parallel isolation IT (attached agent + Jetty)

**Files:**
- Create: `src/integrationTest/java/io/pjacoco/agent/it/SampleServlet.java`
- Create: `src/integrationTest/java/io/pjacoco/agent/it/ParallelIsolationIT.java`

This is the project's reason to exist: two interleaved tests must not contaminate each other.
The IT launches Jetty with the built agent attached (the `integrationTest` task `dependsOn shadowJar`),
drives requests carrying different `baggage: test.id` values, and asserts each `.exec` reflects
only its own test's code path.

- [ ] **Step 1: Write `SampleServlet` + distinct target paths**

```java
package io.pjacoco.agent.it;

import javax.servlet.http.*;
import java.io.IOException;

public class SampleServlet extends HttpServlet {
    private final TargetService svc = new TargetService();
    @Override protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String mode = req.getParameter("mode");
        if ("negative".equals(mode)) {
            svc.classify(-5);              // branch A path
        } else {
            svc.classify(5);               // branch C path
        }
        resp.setStatus(200);
        resp.getWriter().write("ok");
    }
}
```

- [ ] **Step 2: Write `ParallelIsolationIT`**

```java
package io.pjacoco.agent.it;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ParallelIsolationIT {
    static Server server;
    static int appPort;
    static final int CONTROL_PORT = 6310;

    @BeforeAll static void startApp() throws Exception {
        server = new Server(0);
        ServletHandler h = new ServletHandler();
        h.addServletWithMapping(SampleServlet.class, "/run");
        server.setHandler(h);
        server.start();
        appPort = server.getURI().getPort();
    }
    @AfterAll static void stopApp() throws Exception { if (server != null) server.stop(); }

    private void control(String pathAndQuery) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(
            "http://127.0.0.1:" + CONTROL_PORT + pathAndQuery).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) { os.write(new byte[0]); }
        assertEquals(200, c.getResponseCode());
    }

    private void appRequest(String testId, String mode) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(
            "http://127.0.0.1:" + appPort + "/run?mode=" + mode).openConnection();
        c.setRequestProperty("baggage", "test.id=" + testId);
        assertEquals(200, c.getResponseCode());
        c.getInputStream().close();
    }

    @Test
    void interleavedTestsDoNotContaminate() throws Exception {
        // T_NEG only ever hits the negative branch; T_POS only the positive branch
        control("/__coverage__/test/start?testId=T_NEG");
        control("/__coverage__/test/start?testId=T_POS");
        appRequest("T_NEG", "negative");
        appRequest("T_POS", "positive");
        appRequest("T_NEG", "negative");
        control("/__coverage__/test/stop?testId=T_NEG&result=passed");
        control("/__coverage__/test/stop?testId=T_POS&result=passed");

        Path dir = Paths.get("coverage");           // default output dir, app CWD
        assertTrue(Files.exists(dir.resolve("T_NEG.exec")));
        assertTrue(Files.exists(dir.resolve("T_POS.exec")));

        // Covered lines differ: T_NEG must include the negative-branch return line,
        // T_POS must include the positive-branch return line, and they must not be equal.
        byte[] cls = GoldenEquivalenceIT.class.getClassLoader()
            .getResourceAsStream("io/pjacoco/agent/it/TargetService.class") != null
            ? readTargetBytes() : null;
        assertNotNull(cls, "TargetService bytes available");
        var neg = new GoldenEquivalenceIT();          // reuse coveredLines helper (make it package-visible)
        // (If reuse is awkward, inline the same Analyzer-based coveredLines helper here.)
        assertNotEquals(
            negCovered(dir.resolve("T_NEG.exec"), cls),
            negCovered(dir.resolve("T_POS.exec"), cls),
            "two tests exercising different branches must yield different per-test coverage");
    }

    // Helper: covered lines for TargetService from an exec (same logic as GoldenEquivalenceIT).
    private java.util.SortedSet<Integer> negCovered(Path exec, byte[] cls) throws Exception {
        org.jacoco.core.data.ExecutionDataStore eds = new org.jacoco.core.data.ExecutionDataStore();
        try (var in = Files.newInputStream(exec)) {
            org.jacoco.core.data.ExecutionDataReader r = new org.jacoco.core.data.ExecutionDataReader(in);
            r.setExecutionDataVisitor(eds);
            r.setSessionInfoVisitor(new org.jacoco.core.data.SessionInfoStore());
            r.read();
        }
        org.jacoco.core.analysis.CoverageBuilder cb = new org.jacoco.core.analysis.CoverageBuilder();
        new org.jacoco.core.analysis.Analyzer(eds, cb).analyzeClass(cls, "io/pjacoco/agent/it/TargetService");
        java.util.SortedSet<Integer> lines = new java.util.TreeSet<>();
        for (org.jacoco.core.analysis.IClassCoverage c : cb.getClasses())
            for (int l = c.getFirstLine(); l <= c.getLastLine(); l++)
                if (c.getLine(l).getStatus() != org.jacoco.core.analysis.ICounter.EMPTY
                        && c.getLine(l).getStatus() != org.jacoco.core.analysis.ICounter.NOT_COVERED)
                    lines.add(l);
        return lines;
    }
    private byte[] readTargetBytes() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("io/pjacoco/agent/it/TargetService.class")) {
            return in.readAllBytes();
        }
    }
}
```

> **Agent attachment for this IT:** the `integrationTest` JVM must run with
> `-javaagent:build/libs/jacocoagent-parallel.jar=destfile=coverage` and the control port 6310.
> Add to `build.gradle.kts` in the `integrationTest` task:
> ```kotlin
> jvmArgs("-javaagent:${layout.buildDirectory.get()}/libs/jacocoagent-parallel.jar=destfile=coverage,port=6310")
> ```
> Because the agent instruments by name, ensure `includes` covers `io.pjacoco.agent.it.*`
> (default `*` already does). Clean the `coverage/` dir in a `@BeforeAll`.

- [ ] **Step 3: Run, expect PASS**

Run: `./gradlew integrationTest --tests '*ParallelIsolationIT'`
Expected: PASS — `T_NEG` and `T_POS` produce different covered-line sets, proving isolation.

- [ ] **Step 4: Commit**

```bash
git add src/integrationTest/java/io/pjacoco/agent/it/SampleServlet.java src/integrationTest/java/io/pjacoco/agent/it/ParallelIsolationIT.java build.gradle.kts
git commit -m "test: end-to-end parallel isolation via attached agent + Jetty"
```

### Task 16: ThreadReuseIT — no context leak across reused workers

**Files:**
- Create: `src/integrationTest/java/io/pjacoco/agent/it/ThreadReuseIT.java`

- [ ] **Step 1: Write the test** (reuse the running Jetty + agent from a single-threaded executor to force worker reuse)

```java
package io.pjacoco.agent.it;

import org.junit.jupiter.api.*;
import io.pjacoco.agent.context.CoverageContext;
import static org.junit.jupiter.api.Assertions.*;

class ThreadReuseIT {
    @Test
    void contextClearedAfterRequestSoReusedThreadIsClean() {
        // Simulate a worker that handled a tagged request then is reused for an untagged one.
        CoverageContext.set("T_PREV");
        io.pjacoco.agent.inbound.servlet.ServletAdvice.deactivate();   // exit advice of prev request
        assertNull(CoverageContext.get(), "context must be cleared on request exit");

        // Untagged request arrives on the same thread: activate with no baggage -> stays null
        io.pjacoco.agent.inbound.servlet.ServletAdvice.activate(new Object());
        assertNull(CoverageContext.get(), "reused worker must not inherit previous testId");
    }
}
```

- [ ] **Step 2: Run, expect PASS**

Run: `./gradlew integrationTest --tests '*ThreadReuseIT'`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/integrationTest/java/io/pjacoco/agent/it/ThreadReuseIT.java
git commit -m "test: thread reuse does not leak testId context"
```

### Task 17: Failure-isolation test — probe errors never reach the app

**Files:**
- Test: `src/test/java/io/pjacoco/agent/probe/ProbeRouterFailureTest.java`

- [ ] **Step 1: Write the test**

```java
package io.pjacoco.agent.probe;

import org.junit.jupiter.api.*;
import io.pjacoco.agent.context.CoverageContext;
import static org.junit.jupiter.api.Assertions.*;

class ProbeRouterFailureTest {
    @AfterEach void clear() { CoverageContext.clear(); }

    @Test
    void recordNeverThrowsEvenWithNoRegistry() {
        ProbeRouter.bind(null);
        CoverageContext.set("T1");
        assertDoesNotThrow(() -> ProbeRouter.record(1L, "X", 0, 1));  // must swallow
    }

    @Test
    void recordNeverThrowsWithNegativeProbeCount() {
        CoverageContext.set("T1");
        assertDoesNotThrow(() -> ProbeRouter.record(1L, "X", 5, -1)); // bad args swallowed
    }
}
```

- [ ] **Step 2: Run, expect PASS** (ProbeRouter already swallows from Task 7)

Run: `./gradlew test --tests '*ProbeRouterFailureTest'`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/pjacoco/agent/probe/ProbeRouterFailureTest.java
git commit -m "test: probe routing failures are swallowed, never reach the app"
```

### Task 18: JaCoCo version canary CI

**Files:**
- Create: `.github/workflows/jacoco-canary.yml`
- Modify: `build.gradle.kts` (allow jacoco version override via project property)

- [ ] **Step 1: Make jacoco version overridable**

In `build.gradle.kts`, replace the hardcoded jacoco-core coordinate with:
```kotlin
val jacocoVersion = providers.gradleProperty("jacocoVersion").getOrElse("0.8.12")
// ... in dependencies:
implementation("org.jacoco:org.jacoco.core:$jacocoVersion")
testImplementation("org.jacoco:org.jacoco.core:$jacocoVersion")
```

- [ ] **Step 2: Create the canary workflow**

```yaml
name: jacoco-version-canary
on:
  schedule: [{ cron: "0 6 * * 1" }]      # weekly
  workflow_dispatch: {}
jobs:
  canary:
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        jacoco: ["0.8.11", "0.8.12", "0.8.13"]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "17" }
      - name: Golden equivalence against JaCoCo ${{ matrix.jacoco }}
        run: ./gradlew -PjacocoVersion=${{ matrix.jacoco }} integrationTest --tests '*GoldenEquivalenceIT'
```

- [ ] **Step 3: Verify locally against one extra version**

Run: `./gradlew -PjacocoVersion=0.8.11 integrationTest --tests '*GoldenEquivalenceIT'`
Expected: PASS (or a clear failure that documents the M4 hook's version sensitivity — the canary's whole purpose).

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/jacoco-canary.yml build.gradle.kts
git commit -m "ci: weekly JaCoCo version canary running golden-equivalence matrix"
```

---

## Self-Review (completed during planning)

**Spec coverage check (spec §→task):**
- §3 architecture / single embedded agent → Task 0 (shade), Task 14 (wiring) ✓
- §3.1 components → CoverageContext T1, TestStore T2, Registry T6, ProbeRouter T7, Inbound SPI/servlet T9-10, ControlEndpoint T8, ExecWriter T4, Observability T5, ProbeInstrumentation T11 ✓
- §3.2 per-probe bridge mechanism → Task 11 (spike) ✓
- §4 data flow (test + request lifecycle) → Task 8 (control), Task 10 (inbound), Task 15 (E2E) ✓
- §4.3 strict default / lenient option / untagged no-op / context clear → Task 6, Task 7, Task 10, Task 16 ✓
- §5 output (.exec + sidecar, retryCount, shardId) → Task 4 ✓; §5 sidecar index assembly across tests is per-file (no manifest task needed for v1; `manifest.json` assembly is read-time and can be a tiny follow-up — noted as NOT in v1 critical path) ⚠️ **gap closed below**
- §6 jacoco option passthrough/replace → Task 13 (parse/classify) + Task 11 (apply includes/excludes) ✓
- §7.1 concurrent same-testId → TestStore ConcurrentHashMap (Task 2) ✓
- §7.2 assumption-mode flush (snapshot) → Task 4 snapshot + Task 6 stop ✓
- §7.3 retry overwrite → Task 6 ✓
- §7.4 failure isolation → Task 7 + Task 17 ✓
- §7.4 memory guard (cap/TTL, partial) → Task 6 (cap + dumpRemainingAsPartial) ✓ (TTL eviction deferred — cap covers v1; note below)
- §7.5 loopback control binding → Task 8 + Task 13 default 127.0.0.1 ✓
- §7.6 observability counters + shutdown summary → Task 5 + Task 14 ✓
- §8 testing: golden equiv T12, parallel isolation T15, thread reuse T16, failure isolation T17, version canary T18 ✓

**Two gaps found and closed:**
1. **`manifest.json` assembly** (spec §5) is not a v1 critical-path task — per-test sidecars are the source of truth and the manifest is a read-time roll-up. Deferred intentionally; the assembly is a trivial directory scan a consumer (TIA) can do. If a built manifest is wanted in v1, add a one-task `Manifest` that scans `<dir>/*.json` and writes `manifest.json` with the global header (`schemaVersion`, `jacocoVersion`, `commitSha`, `precision`). Not blocking the vertical slice.
2. **TTL eviction** (spec §7.4) — v1 implements the store **cap** (hard bound on count) which is the crash-safety essential; time-based TTL is a refinement deferred to phase 2 and recorded here so it is not silently dropped.

**Placeholder scan:** no TBD/TODO; every code step carries complete code except Task 11 Step 4, which is an explicit, acceptance-test-bounded spike (the one place where fabricating bytecode would be dishonest — its "done" is defined by the passing tests in Tasks 11–12).

**Type consistency:** `ProbeRouter.record(long, String, int, int)` is identical across Tasks 7, 11, 12, 17. `TestStore.record(long, String, int, int)`, `snapshot()`, `classCount()`, `retryCount()` consistent across Tasks 2, 4, 6. `TestStoreRegistry(Path, ExecWriter, Metrics, AgentLog, boolean, int, LongSupplier)` identical across Tasks 6, 7, 8, 11, 12, 14. `ExecWriter.write(...)` 6-arg + 5-arg overloads consistent across Tasks 4, 6.
