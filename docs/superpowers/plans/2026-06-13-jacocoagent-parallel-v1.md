# jacocoagent-parallel v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> ## ⚠️ v2 — MECHANISM VALIDATED BY SPIKE (read first)
> The M4 instrumentation hook is **no longer a research spike** — it has been validated end-to-end
> in `spike/` (two passing tests: vanilla byte-equivalence + parallel isolation). The validated
> mechanism, frozen interface, and integration-test pattern below **supersede the original prose**
> wherever they differ. Key corrections baked into the tasks:
> - **Frozen interface (corrected):** `CoverageBridge.recordCoverage(Class<?> clazz, long classId, int probeId)` (hot path) + `CoverageBridge.setTotalProbeCount(String className, int count)` (instrument time). The old 4-arg `ProbeRouter.record(classId, className, probeId, probeCount)` is **wrong** (probeCount is unknown at probe-firing time) and removed.
> - **Mechanism:** embed jacoco-core; a `ClassFileTransformer` instruments app classes via jacoco's `Instrumenter`; ByteBuddy weaves **body-only** advice into jacoco's `ProbeInserter.insertProbe`/`visitMaxs` and `ClassInstrumenter.visitTotalProbeCount`. The validated source is in `spike/src/main/java/io/pjacoco/spike/` — port it.
> - **Integration tests:** instrument into a fresh `MemoryClassLoader` (load-time, no schema-change problem). **Do NOT** apply jacoco instrumentation to already-loaded classes via `RedefinitionStrategy.RETRANSFORMATION` — JaCoCo adds a `$jacocoData` field and retransformation forbids adding fields (`UnsupportedOperationException: …change the schema`). The real-agent E2E asserts via `.exec` files only (never touches agent statics across the shaded/unshaded classloader split).
> - `CoverageContext` holds the resolved **`TestStore` reference** (not a testId string); strict-mode rejection/counting happens once at request activation, not per probe.

**Goal:** Build a single Java agent (`jacocoagent-parallel.jar`) that produces vanilla-JaCoCo-compatible `.exec` coverage output split per `testId`, for synchronous servlet apps under parallel test execution.

**Architecture:** Out-of-process Java agent embedding jacoco-core. App classes are instrumented by jacoco's `Instrumenter` inside our `ClassFileTransformer`; ByteBuddy body-only advice on jacoco's `ProbeInserter` makes each instrumented probe *additively* call `CoverageBridge.recordCoverage(...)`. testId arrives per-request via OpenTelemetry Baggage (`baggage` header) and is resolved to a `TestStore` held in a ThreadLocal `CoverageContext`. A loopback HTTP control endpoint (`start`/`stop`) defines flush boundaries, emitting `<testId>.exec` + `<testId>.json` sidecars. Additive design: our code never touches the app's behavior and never crashes it. **Validated in `spike/`.**

**Tech Stack:** Java 8 (agent bytecode target), Gradle (Kotlin DSL) + Shadow plugin, jacoco-core 0.8.12 (+ its transitive ASM, used by the advice), Byte Buddy 1.14.x + byte-buddy-agent, JDK `com.sun.net.httpserver` (control endpoint), JUnit 5, embedded Jetty 9.4 (javax.servlet) for integration tests. (Note: Gradle 9.x requires JDK 17+ to *run*; agent bytecode still targets 8 via toolchain.)

---

## File Structure

```
build.gradle.kts                 Gradle build, shadow jar, deps
settings.gradle.kts
gradle/wrapper/...               Gradle wrapper
src/main/java/io/pjacoco/agent/
  Bootstrap.java                 premain: parse opts, wire registry+router, install transforms, start control, shutdown hook
  AgentOptions.java              parse `-javaagent:...=k=v,k=v`, classify JaCoCo opts
  context/CoverageContext.java   ThreadLocal<TestStore> active store (resolved at request activation)
  store/TestStore.java           per-testId coverage: ConcurrentHashMap<Long, ClassProbes>
  store/ClassProbes.java         className + boolean[] for one class
  store/TestStoreRegistry.java   testId -> TestStore; start/stop; cap guard (time-based TTL: phase 2)
  probe/CoverageBridge.java      static recordCoverage(Class,classId,probeId) + setTotalProbeCount(name,count); hot path; swallows errors
  probe/ProbeInstrumentation.java jacoco Instrumenter ClassFileTransformer + ByteBuddy advice on jacoco internals (ported from spike/)
  probe/InsertProbeAdvice.java   body-only advice: emit additive recordCoverage call (ported from spike/)
  probe/VisitMaxsAdvice.java     body-only advice: maxStack += 2 (ported from spike/)
  probe/VisitTotalProbeCountAdvice.java  capture probe count per class (ported from spike/)
  inbound/InboundActivator.java  SPI: apply(AgentBuilder) -> AgentBuilder
  inbound/BaggageParser.java     parse W3C/OTel baggage header -> test.id
  inbound/servlet/ServletInboundActivator.java   advises javax HttpServlet#service (single choke point)
  inbound/servlet/ServletAdvice.java             @Advice enter/exit: resolve store + set/clear context
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

**Interface contract that downstream tasks depend on (frozen, validated in `spike/`):**

```java
// probe/CoverageBridge.java — the ONLY surface the instrumented bytecode calls
public final class CoverageBridge {
    public static void setTotalProbeCount(String className, int count); // instrument time (per class)
    public static void recordCoverage(Class<?> clazz, long classId, int probeId); // hot path (per probe hit)
    public static void clear();                                          // request exit
    // request activation resolves a TestStore and binds it to CoverageContext (set once per request)
}
```

`recordCoverage` reads `CoverageContext.get()` (a `TestStore` or null) and, if non-null, calls
`store.record(classId, className, probeId, totalProbeCount)` — looking up the count captured by
`setTotalProbeCount`. The descriptor emitted by the advice is exactly `(Ljava/lang/Class;JI)V`, so
the signature is **not** negotiable. Everything except Milestone 4 depends only on this surface and
the registry/store/output types — not on how probes get instrumented.

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

### Task 1: CoverageContext (ThreadLocal active TestStore)

**Files:**
- Create: `src/main/java/io/pjacoco/agent/context/CoverageContext.java`
- Test: `src/test/java/io/pjacoco/agent/context/CoverageContextTest.java`

> **Ordering:** holds a `TestStore` (defined in Task 2). Implement Task 2 first, or the two together.
> The store is resolved once per request by the inbound activator (Task 10), so the hot path never
> hits the registry.

- [ ] **Step 1: Write failing test**

```java
package io.pjacoco.agent.context;

import org.junit.jupiter.api.Test;
import io.pjacoco.agent.store.TestStore;
import static org.junit.jupiter.api.Assertions.*;

class CoverageContextTest {
    @Test
    void unsetByDefault() {
        assertNull(CoverageContext.get());
    }

    @Test
    void setAndClear() {
        TestStore s = new TestStore("T1", 1L, null);
        CoverageContext.set(s);
        assertSame(s, CoverageContext.get());
        CoverageContext.clear();
        assertNull(CoverageContext.get());
    }

    @Test
    void isolatedPerThread() throws Exception {
        TestStore s = new TestStore("main-thread", 1L, null);
        CoverageContext.set(s);
        TestStore[] seen = new TestStore[1];
        Thread t = new Thread(() -> seen[0] = CoverageContext.get());
        t.start();
        t.join();
        assertNull(seen[0]);                       // child thread has no context (v1: no async propagation)
        assertSame(s, CoverageContext.get());
    }
}
```

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew test --tests '*CoverageContextTest'`
Expected: compilation failure (class missing).

- [ ] **Step 3: Implement**

```java
package io.pjacoco.agent.context;

import io.pjacoco.agent.store.TestStore;

public final class CoverageContext {
    private static final ThreadLocal<TestStore> ACTIVE = new ThreadLocal<>();
    private CoverageContext() {}

    public static void set(TestStore store) { ACTIVE.set(store); }
    public static TestStore get() { return ACTIVE.get(); }
    public static void clear() { ACTIVE.remove(); }
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew test --tests '*CoverageContextTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/pjacoco/agent/context src/test/java/io/pjacoco/agent/context
git commit -m "feat: CoverageContext thread-local active TestStore"
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

### Task 7: CoverageBridge (the stable instrumentation surface)

**Files:**
- Create: `src/main/java/io/pjacoco/agent/probe/CoverageBridge.java`
- Test: `src/test/java/io/pjacoco/agent/probe/CoverageBridgeTest.java`

> Validated equivalent: `spike/src/main/java/io/pjacoco/spike/CoverageBridge.java`. The bridge does
> NOT touch the registry — `CoverageContext` already holds the resolved `TestStore` (set by the
> inbound activator at request activation, Task 10). Strict-mode rejection is the activator's job.

- [ ] **Step 1: Write failing test**

```java
package io.pjacoco.agent.probe;

import org.junit.jupiter.api.*;
import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.store.TestStore;

import static org.junit.jupiter.api.Assertions.*;

class CoverageBridgeTest {
    @AfterEach void clear() { CoverageContext.clear(); }

    @Test
    void recordsIntoActiveStoreSizedByProbeCount() {
        TestStore store = new TestStore("T1", 1L, null);
        CoverageBridge.setTotalProbeCount("java/lang/String", 3);
        CoverageContext.set(store);

        CoverageBridge.recordCoverage(String.class, 42L, 1);

        assertEquals(1, store.classCount());
        assertArrayEquals(new boolean[]{false, true, false}, store.snapshot().get(42L).probes());
    }

    @Test
    void noContextIsNoOp() {
        CoverageBridge.recordCoverage(String.class, 42L, 1); // no active store -> must not throw
    }

    @Test
    void neverThrowsOnBadInput() {
        CoverageContext.set(new TestStore("T1", 1L, null));
        // unknown class -> fallback count = probeId+1; out-of-range handled in TestStore
        assertDoesNotThrow(() -> CoverageBridge.recordCoverage(String.class, 7L, 4));
    }
}
```

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew test --tests '*CoverageBridgeTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package io.pjacoco.agent.probe;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.store.TestStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The surface instrumented bytecode calls. Signature is fixed by the emitted descriptor
 *  {@code (Ljava/lang/Class;JI)V}; do not change it. Ported/validated in spike/. */
public final class CoverageBridge {
    private static final Map<String, Integer> PROBE_COUNTS = new ConcurrentHashMap<String, Integer>();
    private static volatile Metrics metrics;
    private CoverageBridge() {}

    public static void bindMetrics(Metrics m) { metrics = m; }

    /** Instrument time: authoritative probe count per class (VM/slash name). */
    public static void setTotalProbeCount(String className, int count) {
        PROBE_COUNTS.put(className, count);
    }

    /** Hot path (per probe hit). MUST be cheap and MUST NEVER throw into application code. */
    public static void recordCoverage(Class<?> clazz, long classId, int probeId) {
        try {
            TestStore store = CoverageContext.get();
            if (store == null) return;                       // untagged / unregistered (resolved at activation)
            String name = clazz.getName().replace('.', '/');
            Integer count = PROBE_COUNTS.get(name);
            store.record(classId, name, probeId, count != null ? count.intValue() : probeId + 1);
        } catch (Throwable t) {
            Metrics m = metrics;
            if (m != null) m.swallowedExceptions.incrementAndGet();
            // swallow: coverage loss is acceptable, an app crash is not
        }
    }

    public static void clear() { CoverageContext.clear(); }
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew test --tests '*CoverageBridgeTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/pjacoco/agent/probe/CoverageBridge.java src/test/java/io/pjacoco/agent/probe/CoverageBridgeTest.java
git commit -m "feat: CoverageBridge — stable per-probe instrumentation surface, error-swallowing hot path"
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

The activator advises `javax.servlet.http.HttpServlet#service(ServletRequest, ServletResponse)` (a
**single choke point** — no filter registration needed). On enter it parses the `baggage` header,
**resolves the `TestStore` from the registry once** (strict: reject+count if unregistered; lenient:
lazy-create) and sets `CoverageContext` to that store; on exit it clears. `ServletAdvice.activate`/
`deactivate` are plain static methods so they're unit-testable without weaving.

> **S6 (nesting):** match only the 2-arg public `service(ServletRequest,ServletResponse)`. Frameworks
> typically override `service(HttpServletRequest,HttpServletResponse)` (different descriptor) or
> `doGet`/`doPost`, so this matches a single declaration per request. If a container is found to
> double-invoke, add a depth-counter guard so only the outermost enter sets and the outermost exit
> clears. Do **not** match across the whole hierarchy with `isSubTypeOf(...).and(named("service"))`
> alone, which can advise parent+child and let an inner exit clear the context early.

- [ ] **Step 1: Write failing test** (tests the extracted logic directly)

```java
package io.pjacoco.agent.inbound.servlet;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.*;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;
import javax.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;   // add testImplementation mockito if not present

class ServletAdviceTest {
    @AfterEach void clear() { CoverageContext.clear(); ServletAdvice.registry = null; }

    private TestStoreRegistry reg(Path dir, boolean lenient) {
        AtomicLong clock = new AtomicLong(1L);
        return new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(),
                lenient, 100, clock::get);
    }

    @Test
    void activatesResolvedStoreFromBaggage(@TempDir Path dir) {
        TestStoreRegistry r = reg(dir, false);
        r.start("T1", null, "sha");
        ServletAdvice.registry = r;
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("baggage")).thenReturn("test.id=T1");

        ServletAdvice.activate(req);
        assertSame(r.active("T1"), CoverageContext.get());   // context holds the resolved store
        ServletAdvice.deactivate();
        assertNull(CoverageContext.get());
    }

    @Test
    void strictUnregisteredLeavesContextUnset(@TempDir Path dir) {
        ServletAdvice.registry = reg(dir, false);            // strict, T1 not started
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("baggage")).thenReturn("test.id=GHOST");
        ServletAdvice.activate(req);
        assertNull(CoverageContext.get());
    }

    @Test
    void noHeaderOrNonHttpIgnored(@TempDir Path dir) {
        ServletAdvice.registry = reg(dir, false);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("baggage")).thenReturn(null);
        ServletAdvice.activate(req);
        assertNull(CoverageContext.get());
        ServletAdvice.activate(new Object());                // not an HttpServletRequest
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

import java.lang.instrument.Instrumentation;

/** Strategy for activating CoverageContext from an inbound request, per transport. */
public interface InboundActivator {
    /** Install this activator's instrumentation (its own body-only ByteBuddy advice). */
    void install(Instrumentation inst);
}
```

- [ ] **Step 4: Implement `ServletAdvice` (resolves the store; testable logic + @Advice hooks)**

```java
package io.pjacoco.agent.inbound.servlet;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.inbound.BaggageParser;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;
import net.bytebuddy.asm.Advice;
import javax.servlet.http.HttpServletRequest;

public final class ServletAdvice {
    /** Bound once by ServletInboundActivator; read by the woven advice. */
    public static volatile TestStoreRegistry registry;

    private ServletAdvice() {}

    /** Per request (once): resolve the store and bind it to the context. Never disturbs the app. */
    public static void activate(Object request) {
        try {
            if (request instanceof HttpServletRequest && registry != null) {
                String testId = BaggageParser.testId(((HttpServletRequest) request).getHeader("baggage"));
                if (testId != null) {
                    TestStore store = registry.active(testId); // strict: null+count once; lenient: create
                    if (store != null) CoverageContext.set(store);
                }
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
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.store.TestStoreRegistry;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import java.lang.instrument.Instrumentation;
import static net.bytebuddy.matcher.ElementMatchers.*;

public final class ServletInboundActivator implements InboundActivator {
    public ServletInboundActivator(TestStoreRegistry registry, Metrics metrics, AgentLog log) {
        ServletAdvice.registry = registry;   // bind the static the woven advice reads
    }

    @Override
    public void install(Instrumentation inst) {
        new AgentBuilder.Default()
            .disableClassFormatChanges()                    // body-only advice: no member changes
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .ignore(nameStartsWith("net.bytebuddy."))
            .type(isSubTypeOf(named("javax.servlet.http.HttpServlet")))
            .transform((b, type, cl, module, pd) -> b.visit(
                Advice.to(ServletAdvice.class).on(
                    named("service")                        // single 2-arg public choke point
                        .and(takesArguments(2))
                        .and(takesArgument(0, named("javax.servlet.ServletRequest")))
                        .and(takesArgument(1, named("javax.servlet.ServletResponse"))))))
            .installOn(inst);
    }
}
```

- [ ] **Step 6: Run, expect PASS**

Run: `./gradlew test --tests '*ServletAdviceTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/pjacoco/agent/inbound src/test/java/io/pjacoco/agent/inbound/servlet
git commit -m "feat: InboundActivator SPI + servlet activator (resolves store at single choke point)"
```

---

## Milestone 4 — Probe instrumentation (VALIDATED in `spike/`)

> **No longer a research spike.** The hard part — vanilla-identical per-test probe routing — is
> proven end-to-end in `spike/` (tests `perTestProbesMatchVanillaJacoco` + `parallelContextsAreIsolated`).
> This milestone **ports** that validated code into the agent and adds the standard agent plumbing
> (a `ClassFileTransformer` + a jacoco `IRuntime`). The advice/bridge below is copied verbatim from
> `spike/src/main/java/io/pjacoco/spike/` (only the package changes).
>
> **Coupling surface guarded by the version canary (Task 18):** `ProbeInserter.insertProbe(int)`,
> `ProbeInserter.visitMaxs(int,int)`, the inherited `mv` field + `arrayStrategy` field;
> `ClassFieldProbeArrayStrategy.className`/`classId`; `ClassInstrumenter.visitTotalProbeCount(int)` +
> its `className` field. All in our **embedded, version-pinned** jacoco-core (clean
> `org.jacoco.core.internal.instr.*` package — no relocation/MethodHandle gymnastics needed).

### Task 11: Port validated advice + ProbeInstrumentation (hook + transformer + runtime)

**Files:**
- Create: `src/main/java/io/pjacoco/agent/probe/InsertProbeAdvice.java`  (verbatim from spike)
- Create: `src/main/java/io/pjacoco/agent/probe/VisitMaxsAdvice.java`     (verbatim from spike)
- Create: `src/main/java/io/pjacoco/agent/probe/VisitTotalProbeCountAdvice.java` (verbatim from spike)
- Create: `src/main/java/io/pjacoco/agent/probe/ProbeInstrumentation.java` (new: hook install + transformer + runtime)
- Test: `src/integrationTest/java/io/pjacoco/agent/it/TargetService.java`
- Test: `src/integrationTest/java/io/pjacoco/agent/it/ProbeRoutingIT.java`

Add to `build.gradle.kts` dependencies:
```kotlin
    implementation("net.bytebuddy:byte-buddy-agent:1.14.18")   // self-attach for in-process ITs
```
Integration-test JVM needs self-attach: add `jvmArgs("-Djdk.attach.allowAttachSelf=true")` to the
`integrationTest` task (Task 0).

- [ ] **Step 1: Port the three advice classes (verbatim from `spike/`, package `io.pjacoco.agent.probe`)**

```java
// InsertProbeAdvice.java — woven into jacoco's ProbeInserter.insertProbe(int) (instrument time).
package io.pjacoco.agent.probe;

import java.lang.reflect.Field;
import net.bytebuddy.asm.Advice;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public class InsertProbeAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void exit(
            @Advice.FieldValue("mv") MethodVisitor mv,
            @Advice.FieldValue("arrayStrategy") Object arrayStrategy,
            @Advice.Argument(0) int id)
            throws Exception {
        Field classNameField = arrayStrategy.getClass().getDeclaredField("className");
        classNameField.setAccessible(true);
        String className = (String) classNameField.get(arrayStrategy);

        Field classIdField = arrayStrategy.getClass().getDeclaredField("classId");
        classIdField.setAccessible(true);
        long classId = classIdField.getLong(arrayStrategy);

        mv.visitLdcInsn(Type.getType("L" + className + ";"));
        mv.visitLdcInsn(Long.valueOf(classId));
        mv.visitLdcInsn(Integer.valueOf(id));
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "io/pjacoco/agent/probe/CoverageBridge", "recordCoverage",
                "(Ljava/lang/Class;JI)V", false);
    }
}
```
```java
// VisitMaxsAdvice.java — woven into ProbeInserter.visitMaxs(int,int).
package io.pjacoco.agent.probe;

import net.bytebuddy.asm.Advice;

public class VisitMaxsAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter(@Advice.Argument(value = 0, readOnly = false) int maxStack) {
        maxStack = maxStack + 2;   // jacoco already +3; our Class+long+int call needs a touch more
    }
}
```
```java
// VisitTotalProbeCountAdvice.java — woven into ClassInstrumenter.visitTotalProbeCount(int).
package io.pjacoco.agent.probe;

import net.bytebuddy.asm.Advice;

public class VisitTotalProbeCountAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter(@Advice.FieldValue("className") String className, @Advice.Argument(0) int count) {
        CoverageBridge.setTotalProbeCount(className, count);
    }
}
```

> **Note (descriptor):** `InsertProbeAdvice` emits `INVOKESTATIC io/pjacoco/agent/probe/CoverageBridge.recordCoverage (Ljava/lang/Class;JI)V`.
> This is why `CoverageBridge.recordCoverage(Class,long,int)` (Task 7) is immutable.

- [ ] **Step 2: Implement `ProbeInstrumentation`** (the new agent-side plumbing)

```java
package io.pjacoco.agent.probe;

import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;

import io.pjacoco.agent.AgentOptions;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.jacoco.core.runtime.WildcardMatcher;

public final class ProbeInstrumentation {
    private ProbeInstrumentation() {}

    /**
     * 1) Weaves body-only advice into our embedded jacoco internals (additive recordCoverage).
     * 2) Starts a jacoco IRuntime so instrumented classes' $jacocoInit resolves.
     * 3) Registers a ClassFileTransformer that instruments app classes with jacoco's Instrumenter.
     */
    public static void install(Instrumentation inst, AgentOptions options) throws Exception {
        // (1) body-only advice on jacoco internals — disableClassFormatChanges() is correct here.
        //     CircularityLock.Inactive + suffix matchers (nameEndsWith) so this resolves both the
        //     relocated (io.pjacoco.shaded.jacoco.*) classes in the shaded agent jar AND the
        //     un-relocated (org.jacoco.*) ones in the in-process ITs.
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.CircularityLock.Inactive.INSTANCE)
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(nameEndsWith("jacoco.core.internal.instr.ProbeInserter"))
                .transform((b, t, cl, m, pd) -> b
                        .visit(Advice.to(VisitMaxsAdvice.class).on(named("visitMaxs")))
                        .visit(Advice.to(InsertProbeAdvice.class).on(named("insertProbe"))))
                .type(nameEndsWith("jacoco.core.internal.instr.ClassInstrumenter"))
                .transform((b, t, cl, m, pd) -> b
                        .visit(Advice.to(VisitTotalProbeCountAdvice.class).on(named("visitTotalProbeCount"))))
                .installOn(inst);

        // (2) LoggerRuntime: in-process data channel so instrumented classes' $jacocoInit resolves
        //     (matches the validated spike and the spec's "예: LoggerRuntime"). We don't consume
        //     jacoco's global data — our bridge records per-test.
        IRuntime runtime = new LoggerRuntime();
        runtime.startup(new RuntimeData());

        Instrumenter instrumenter = new Instrumenter(runtime);
        // (3) WARM UP — force ProbeInserter to load + be advised NOW, in a clean context. If it first
        //     loads lazily inside our own transform(), ByteBuddy skips advising it and per-test routing
        //     silently no-ops (docs/research/m4-mechanism/03-spike-code.md §7).
        warmUp(instrumenter);

        // (4) instrument app classes via jacoco — yields vanilla-identical classId + probe scheme.
        inst.addTransformer(new JacocoTransformer(instrumenter, options), false);
    }

    /** Instruments a throwaway WarmupTarget (a class with a branch) so ProbeInserter loads+advised. */
    private static void warmUp(Instrumenter instrumenter) {
        try {
            java.io.InputStream in = ProbeInstrumentation.class.getResourceAsStream(
                    "/io/pjacoco/agent/probe/WarmupTarget.class");
            if (in == null) return;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            instrumenter.instrument(bos.toByteArray(), "io/pjacoco/agent/probe/WarmupTarget");
        } catch (Throwable ignored) { /* best-effort */ }
    }

    /** Instruments matching app classes with jacoco's Instrumenter. Never breaks class loading. */
    static final class JacocoTransformer implements ClassFileTransformer {
        private final Instrumenter instrumenter;
        private final WildcardMatcher includes;
        private final WildcardMatcher excludes;

        JacocoTransformer(Instrumenter instrumenter, AgentOptions options) {
            this.instrumenter = instrumenter;
            this.includes = new WildcardMatcher(options.includes());   // default "*"
            this.excludes = new WildcardMatcher(options.excludes());   // default ""
        }

        @Override
        public byte[] transform(ClassLoader loader, String vmName, Class<?> beingRedefined,
                                ProtectionDomain pd, byte[] buffer) {
            if (vmName == null || beingRedefined != null) return null;   // first load only
            String dotted = vmName.replace('/', '.');
            if (dotted.startsWith("io.pjacoco.") || dotted.startsWith("org.jacoco.")
                    || dotted.startsWith("net.bytebuddy.") || dotted.startsWith("org.objectweb.asm.")) {
                return null;                                             // never instrument self/embedded libs
            }
            if (!includes.matches(dotted) || excludes.matches(dotted)) return null;
            int major = ((buffer[6] & 0xFF) << 8) | (buffer[7] & 0xFF);
            if (major < 49) return null;                                 // Java <1.5: cannot push Type constants
            try {
                return instrumenter.instrument(buffer, vmName);
            } catch (Throwable t) {
                return null;                                             // coverage loss >> breaking the app
            }
        }
    }
}
```

> **What's validated vs new:** the advice + bridge + per-test routing (the hard, version-fragile
> part) is **validated by `spike/`**. The `ClassFileTransformer` + `LoggerRuntime` + warm-up wiring is
> *standard jacoco-agent plumbing* exercised by the Task 15 e2e. Two as-built findings (see
> `docs/research/m4-mechanism/03-spike-code.md §7`): (a) **warm-up is mandatory** — without it
> ProbeInserter loads un-advised inside our transform and routing silently no-ops; (b) the jacoco
> hook uses **suffix matchers** + a throwaway `WarmupTarget` class. `WildcardMatcher` / `LoggerRuntime`
> are public jacoco-core API.

- [ ] **Step 3: Port `TargetService` + write the in-process acceptance IT (`ProbeRoutingIT`)**

`TargetService` (compile the integrationTest source set with `options.release = 8` so jacoco uses
the field strategy, mirroring the spike — add `tasks.named<JavaCompile>("compileIntegrationTestJava") { options.release = 8 }`):

```java
package io.pjacoco.agent.it;

public class TargetService {
    public int classify(int n) {
        if (n < 0) { return -1; }
        if (n == 0) { return 0; }
        return 1;
    }
    public String greet(boolean formal) {
        if (formal) { return "Good day"; }
        return "hi";
    }
}
```

`ProbeRoutingIT` — instruments into a fresh `MemoryClassLoader` (load-time; **no retransformation
of already-loaded classes**, which would fail with `class redefinition … change the schema` because
jacoco adds the `$jacocoData` field). This mirrors the validated spike exactly:

```java
package io.pjacoco.agent.it;

import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.junit.jupiter.api.Test;
import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.probe.CoverageBridge;
import io.pjacoco.agent.probe.ProbeInstrumentation;
import io.pjacoco.agent.store.TestStore;
import net.bytebuddy.agent.ByteBuddyAgent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ProbeRoutingIT {
    static final String NAME = "io.pjacoco.agent.it.TargetService";

    @Test
    void firedProbesLandInActiveStore() throws Exception {
        // install only the body-only advice on jacoco internals (no transformer needed for in-process)
        ProbeInstrumentation.installHookOnly(ByteBuddyAgent.install());

        byte[] original = readBytes(NAME);
        IRuntime runtime = new LoggerRuntime();
        Instrumenter instrumenter = new Instrumenter(runtime);
        byte[] instrumented = instrumenter.instrument(original, NAME);
        runtime.startup(new RuntimeData());

        MemoryClassLoader loader = new MemoryClassLoader();
        loader.add(NAME, instrumented);
        Class<?> target = loader.loadClass(NAME);

        TestStore store = new TestStore("T1", 1L, null);
        CoverageContext.set(store);
        target.getMethod("classify", int.class).invoke(target.getDeclaredConstructor().newInstance(), 5);
        CoverageContext.clear();

        assertTrue(store.classCount() >= 1, "instrumentation did not route any probe for TargetService");
    }

    static byte[] readBytes(String fqcn) throws Exception {
        try (InputStream in = ProbeRoutingIT.class.getResourceAsStream("/" + fqcn.replace('.', '/') + ".class")) {
            assertNotNull(in);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    static final class MemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> defs = new HashMap<>();
        MemoryClassLoader() { super(ProbeRoutingIT.class.getClassLoader()); }
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
```

Add `ProbeInstrumentation.installHookOnly(Instrumentation)` — the advice-install half of `install(...)`
(factor it out of Step 2 so the in-process ITs can use it without the transformer/runtime):

```java
    public static void installHookOnly(Instrumentation inst) {
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.CircularityLock.Inactive.INSTANCE)
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(nameEndsWith("jacoco.core.internal.instr.ProbeInserter"))
                .transform((b, t, cl, m, pd) -> b
                        .visit(Advice.to(VisitMaxsAdvice.class).on(named("visitMaxs")))
                        .visit(Advice.to(InsertProbeAdvice.class).on(named("insertProbe"))))
                .type(nameEndsWith("jacoco.core.internal.instr.ClassInstrumenter"))
                .transform((b, t, cl, m, pd) -> b
                        .visit(Advice.to(VisitTotalProbeCountAdvice.class).on(named("visitTotalProbeCount"))))
                .installOn(inst);
    }
```
(Have `install(...)` call `installHookOnly(inst)` then do the runtime + transformer steps.)

- [ ] **Step 4: Run, expect PASS**

Run: `JAVA_HOME=<jdk17> ./gradlew integrationTest --tests '*ProbeRoutingIT'`
Expected: PASS (mirrors spike `perTestProbesMatchVanillaJacoco`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/pjacoco/agent/probe/ src/integrationTest/java/io/pjacoco/agent/it/TargetService.java src/integrationTest/java/io/pjacoco/agent/it/ProbeRoutingIT.java build.gradle.kts
git commit -m "feat: port validated probe-routing (advice+bridge) + jacoco transformer"
```

### Task 12: GoldenEquivalenceIT — per-test probes byte-identical to vanilla jacoco

**Files:**
- Test: `src/integrationTest/java/io/pjacoco/agent/it/GoldenEquivalenceIT.java`

**Goal:** prove "vanilla identical" — in a single instrument+run, the probe array our bridge records
equals jacoco's own global array byte-for-byte (validated as `perTestProbesMatchVanillaJacoco` in
`spike/`). Port that test, adapting package names.

- [ ] **Step 1: Write the test (port from `spike/src/test/java/io/pjacoco/spike/SpikeMechanismTest.java`)**

```java
package io.pjacoco.agent.it;

import org.jacoco.core.analysis.*;
import org.jacoco.core.data.*;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.*;
import org.junit.jupiter.api.Test;
import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.probe.CoverageBridge;
import io.pjacoco.agent.probe.ProbeInstrumentation;
import io.pjacoco.agent.store.TestStore;
import net.bytebuddy.agent.ByteBuddyAgent;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GoldenEquivalenceIT {
    static final String NAME = "io.pjacoco.agent.it.TargetService";
    static final String VM_NAME = "io/pjacoco/agent/it/TargetService";

    @Test
    void perTestProbesMatchVanillaJacoco() throws Exception {
        ProbeInstrumentation.installHookOnly(ByteBuddyAgent.install());

        byte[] original = ProbeRoutingIT.readBytes(NAME);
        IRuntime runtime = new LoggerRuntime();
        Instrumenter instrumenter = new Instrumenter(runtime);
        byte[] instrumented = instrumenter.instrument(original, NAME);
        RuntimeData data = new RuntimeData();
        runtime.startup(data);

        ProbeRoutingIT.MemoryClassLoader loader = new ProbeRoutingIT.MemoryClassLoader();
        loader.add(NAME, instrumented);
        Class<?> target = loader.loadClass(NAME);

        TestStore store = new TestStore("T1", 1L, null);
        CoverageContext.set(store);
        target.getMethod("classify", int.class).invoke(target.getDeclaredConstructor().newInstance(), 5);
        CoverageContext.clear();

        ExecutionDataStore vanilla = new ExecutionDataStore();
        data.collect(vanilla, new SessionInfoStore(), false);
        runtime.shutdown();

        assertEquals(1, vanilla.getContents().size());
        ExecutionData vanillaEd = vanilla.getContents().iterator().next();
        long classId = vanillaEd.getId();
        boolean[] vanillaProbes = vanillaEd.getProbes();
        boolean[] ourProbes = store.snapshot().get(classId).probes();

        // KEYSTONE: byte-identical to vanilla jacoco.
        assertArrayEquals(vanillaProbes, ourProbes);

        // bonus: covered lines agree through jacoco's Analyzer
        ExecutionDataStore ourStore = new ExecutionDataStore();
        ourStore.put(new ExecutionData(classId, VM_NAME, ourProbes));
        assertEquals(coveredLines(original, vanilla), coveredLines(original, ourStore));
    }

    private static SortedSet<Integer> coveredLines(byte[] original, ExecutionDataStore store) throws Exception {
        CoverageBuilder cb = new CoverageBuilder();
        new Analyzer(store, cb).analyzeClass(original, NAME);
        SortedSet<Integer> lines = new TreeSet<>();
        for (IClassCoverage c : cb.getClasses())
            for (int l = c.getFirstLine(); l <= c.getLastLine(); l++) {
                int s = c.getLine(l).getStatus();
                if (s == ICounter.FULLY_COVERED || s == ICounter.PARTLY_COVERED) lines.add(l);
            }
        return lines;
    }
}
```

- [ ] **Step 2: Run, expect PASS**

Run: `JAVA_HOME=<jdk17> ./gradlew integrationTest --tests '*GoldenEquivalenceIT'`
Expected: PASS (matches spike: `assertArrayEquals(vanillaProbes, ourProbes)`).

- [ ] **Step 3: Commit**

```bash
git add src/integrationTest/java/io/pjacoco/agent/it/GoldenEquivalenceIT.java
git commit -m "test: golden equivalence — per-test probes byte-identical to vanilla jacoco"
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
import io.pjacoco.agent.output.Json;
import io.pjacoco.agent.probe.CoverageBridge;
import io.pjacoco.agent.probe.ProbeInstrumentation;
import io.pjacoco.agent.store.TestStoreRegistry;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Bootstrap {
    private Bootstrap() {}

    public static void premain(String args, Instrumentation inst) throws Exception {
        AgentOptions options = AgentOptions.parse(args);
        Metrics metrics = new Metrics();
        AgentLog log = new AgentLog();

        String commitSha = options.commitSha() != null ? options.commitSha()
                : System.getenv("PJACOCO_COMMIT");

        Path outDir = Paths.get(options.outputDir());
        TestStoreRegistry registry = new TestStoreRegistry(
                outDir, new ExecWriter(), metrics, log,
                options.lenient(), options.maxStores(), System::currentTimeMillis);

        // Global meta header, written ONCE at startup — persists commitSha with no per-stop contention
        // (replaces the old __bootstrap_commit__ hack, which created a junk T=__bootstrap_commit__.exec).
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

        // Probe instrumentation: jacoco-internal body-only advice + jacoco Instrumenter transformer + runtime.
        ProbeInstrumentation.install(inst, options);

        // Inbound activation: resolve TestStore from the registry per request, set/clear CoverageContext.
        new ServletInboundActivator(registry, metrics, log).install(inst);

        log.info("agent installed (output=" + options.outputDir()
                + ", mode=" + (options.lenient() ? "lenient" : "strict") + ")");
    }
}
```

> **Corrections baked in (S1/S4):** (a) no `__bootstrap_commit__` — commitSha is persisted by the
> manifest header above; (b) `ProbeInstrumentation.install(inst, options)` owns its own AgentBuilder
> (body-only advice with `disableClassFormatChanges()`) **and** the jacoco `Instrumenter`
> ClassFileTransformer — Bootstrap no longer builds a shared AgentBuilder, so the earlier
> contradiction (member-adding probe instrumentation under `disableClassFormatChanges()`) is gone;
> (c) the inbound activator installs its own body-only advice and is given the registry so it can
> resolve the store at activation.

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

> **C2 — run this in its OWN task with the real `-javaagent`, separate from the in-process ITs.**
> The mechanism ITs (Task 11/12) do manual in-process instrumentation; this E2E uses the real
> premain agent. They MUST NOT share a JVM, or `TargetService` gets instrumented twice. Define a
> dedicated `e2eTest` task that (a) `dependsOn(shadowJar)`, (b) attaches the agent, (c) does NOT add
> `-Djdk.attach.allowAttachSelf` and never calls `ByteBuddyAgent.install()`. This IT asserts **only
> via the `.exec` files on disk** — it never touches agent statics (which live in the shaded jar's
> classloader, distinct from the test classpath copies).

```java
package io.pjacoco.agent.it;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;
import org.junit.jupiter.api.*;
import org.jacoco.core.analysis.*;
import org.jacoco.core.data.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.SortedSet;
import java.util.TreeSet;
import static org.junit.jupiter.api.Assertions.*;

class ParallelIsolationIT {
    static Server server;
    static int appPort;
    static final int CONTROL_PORT = 6310;
    static final Path COVERAGE = Paths.get("coverage");

    @BeforeAll static void startApp() throws Exception {
        if (Files.isDirectory(COVERAGE)) {           // clean prior run
            try (java.util.stream.Stream<Path> s = Files.list(COVERAGE)) {
                for (Path p : (Iterable<Path>) s::iterator) Files.deleteIfExists(p);
            }
        }
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
        control("/__coverage__/test/start?testId=T_NEG");
        control("/__coverage__/test/start?testId=T_POS");
        appRequest("T_NEG", "negative");
        appRequest("T_POS", "positive");
        appRequest("T_NEG", "negative");
        control("/__coverage__/test/stop?testId=T_NEG&result=passed");
        control("/__coverage__/test/stop?testId=T_POS&result=passed");

        assertTrue(Files.exists(COVERAGE.resolve("T_NEG.exec")));
        assertTrue(Files.exists(COVERAGE.resolve("T_POS.exec")));

        SortedSet<Integer> neg = coveredLines(COVERAGE.resolve("T_NEG.exec"));
        SortedSet<Integer> pos = coveredLines(COVERAGE.resolve("T_POS.exec"));
        assertNotEquals(neg, pos,
            "two tests exercising different branches must yield different per-test coverage");
    }

    private static SortedSet<Integer> coveredLines(Path exec) throws Exception {
        byte[] cls = readResource("/io/pjacoco/agent/it/TargetService.class");
        ExecutionDataStore eds = new ExecutionDataStore();
        try (InputStream in = Files.newInputStream(exec)) {
            ExecutionDataReader r = new ExecutionDataReader(in);
            r.setExecutionDataVisitor(eds);
            r.setSessionInfoVisitor(new SessionInfoStore());
            r.read();
        }
        CoverageBuilder cb = new CoverageBuilder();
        new Analyzer(eds, cb).analyzeClass(cls, "io/pjacoco/agent/it/TargetService");
        SortedSet<Integer> lines = new TreeSet<Integer>();
        for (IClassCoverage c : cb.getClasses())
            for (int l = c.getFirstLine(); l <= c.getLastLine(); l++) {
                int s = c.getLine(l).getStatus();
                if (s == ICounter.FULLY_COVERED || s == ICounter.PARTLY_COVERED) lines.add(l);
            }
        return lines;
    }

    private static byte[] readResource(String res) throws Exception {
        try (InputStream in = ParallelIsolationIT.class.getResourceAsStream(res)) {
            assertNotNull(in, res);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
```

> **`e2eTest` task (add to `build.gradle.kts`, separate from `integrationTest`):**
> ```kotlin
> val e2eTest = tasks.register<Test>("e2eTest") {
>     testClassesDirs = sourceSets["integrationTest"].output.classesDirs
>     classpath = sourceSets["integrationTest"].runtimeClasspath
>     useJUnitPlatform { includeTags("e2e") }            // tag ParallelIsolationIT @Tag("e2e")
>     dependsOn(tasks.shadowJar)
>     jvmArgs("-javaagent:${layout.buildDirectory.get()}/libs/jacocoagent-parallel.jar=destfile=coverage,port=6310")
> }
> ```
> Tag `ParallelIsolationIT` with `@Tag("e2e")` and have the in-process `integrationTest` task
> `useJUnitPlatform { excludeTags("e2e") }`. The agent instruments by name; default `includes=*`
> covers `io.pjacoco.agent.it.*`.

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
import io.pjacoco.agent.inbound.servlet.ServletAdvice;
import io.pjacoco.agent.store.TestStore;
import static org.junit.jupiter.api.Assertions.*;

class ThreadReuseIT {
    @Test
    void contextClearedAfterRequestSoReusedThreadIsClean() {
        // Simulate a worker that handled a tagged request then is reused for an untagged one.
        CoverageContext.set(new TestStore("T_PREV", 1L, null));
        ServletAdvice.deactivate();                                    // exit advice of prev request
        assertNull(CoverageContext.get(), "context must be cleared on request exit");

        // Untagged request arrives on the same thread: activate with no baggage -> stays null
        ServletAdvice.activate(new Object());                          // not an HttpServletRequest
        assertNull(CoverageContext.get(), "reused worker must not inherit previous test store");
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
- Test: `src/test/java/io/pjacoco/agent/probe/CoverageBridgeFailureTest.java`

- [ ] **Step 1: Write the test**

```java
package io.pjacoco.agent.probe;

import org.junit.jupiter.api.*;
import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.store.TestStore;
import static org.junit.jupiter.api.Assertions.*;

class CoverageBridgeFailureTest {
    @AfterEach void clear() { CoverageContext.clear(); }

    @Test
    void recordNeverThrowsWithoutContext() {
        // no active store
        assertDoesNotThrow(() -> CoverageBridge.recordCoverage(String.class, 1L, 0));  // must swallow
    }

    @Test
    void recordNeverThrowsOnOutOfRangeProbe() {
        CoverageBridge.setTotalProbeCount("java/lang/String", 2);
        CoverageContext.set(new TestStore("T1", 1L, null));
        assertDoesNotThrow(() -> CoverageBridge.recordCoverage(String.class, 1L, 99)); // out of range -> ignored
    }
}
```

- [ ] **Step 2: Run, expect PASS** (CoverageBridge already swallows from Task 7)

Run: `./gradlew test --tests '*CoverageBridgeFailureTest'`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/pjacoco/agent/probe/CoverageBridgeFailureTest.java
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
- §3 architecture / single embedded agent → Task 0 (shade), Task 11 (instrument+hook), Task 14 (wiring) ✓
- §3.1 components → CoverageContext T1, TestStore T2, Registry T6, CoverageBridge T7, Inbound SPI/servlet T9-10, ControlEndpoint T8, ExecWriter T4, Observability T5, ProbeInstrumentation+advice T11 ✓
- §3.2 instrument-time hook mechanism → Task 11 (ported from validated `spike/`) ✓
- §4 data flow (test + request lifecycle) → Task 8 (control), Task 10 (inbound resolves store), Task 15 (E2E) ✓
- §4.3 strict default / lenient option / untagged no-op / context clear / activation-time reject → Task 6, Task 10 (activate), Task 16 ✓
- §5 output (.exec + sidecar, retryCount, shardId) → Task 4 ✓; global `manifest.json` header written once at premain (Task 14) carries commitSha/jacocoVersion/precision; full index = header + sidecar scan (read-time) ✓
- §6 jacoco option passthrough/replace → Task 13 (parse/classify) + Task 11 (`WildcardMatcher` includes/excludes in the transformer) ✓
- §7.1 concurrent same-testId → TestStore ConcurrentHashMap (Task 2); **validated** by spike parallel-isolation ✓
- §7.2 assumption-mode flush (snapshot) → Task 4 snapshot + Task 6 stop ✓
- §7.3 retry overwrite → Task 6 ✓
- §7.4 failure isolation → Task 7 (CoverageBridge swallows) + Task 17 ✓
- §7.4 memory guard (cap/partial) → Task 6 (cap + dumpRemainingAsPartial) ✓ (time-based TTL deferred to phase 2; cap covers v1)
- §7.5 loopback control binding → Task 8 + Task 13 default 127.0.0.1 ✓
- §7.6 observability counters + shutdown summary → Task 5 + Task 14 ✓
- §8 testing: golden equiv T12, parallel isolation T15 (e2e task), thread reuse T16, failure isolation T17, version canary T18 ✓

**Resolved in v2 (vs first draft):**
1. **Mechanism validated** — the per-test routing is proven in `spike/`; Task 11 ports it. Frozen interface is `CoverageBridge.recordCoverage(Class,long,int)` + `setTotalProbeCount(String,int)`.
2. **IT harness (C2)** — in-process ITs instrument into a fresh `MemoryClassLoader` (load-time); the E2E runs in a **separate `e2eTest` task** with the real `-javaagent` and asserts via files. No retransformation of loaded classes; no double instrumentation.
3. **commitSha (S5)** — persisted by the premain `manifest.json` header (no `__bootstrap_commit__`).
4. **Hot-path accounting (S2)** — strict reject/count at activation (Task 10), not per probe.
5. **`manifest.json` assembly** — header at premain + per-test sidecars; full roll-up is a read-time scan (a consumer like TIA does it). Not on the v1 critical path.
6. **TTL eviction** (spec §7.4) — v1 implements the store **cap**; time-based TTL deferred to phase 2 (recorded so it is not silently dropped).

**Placeholder scan:** no TBD/TODO; every code step carries complete code. Task 11's advice/bridge is **verbatim from the passing `spike/`**; the only non-validated piece is the standard agent plumbing (`ClassFileTransformer` + `LoggerRuntime` + warm-up), exercised by the Task 15 e2e.

**Type consistency:** `CoverageBridge.recordCoverage(Class, long, int)` + `setTotalProbeCount(String, int)` identical across Tasks 7, 11, 12, 17. `TestStore.record(long, String, int, int)`, `snapshot()`, `classCount()`, `retryCount()` consistent across Tasks 2, 4, 6, 7. `CoverageContext` holds `TestStore` across Tasks 1, 7, 10, 16. `TestStoreRegistry(Path, ExecWriter, Metrics, AgentLog, boolean, int, LongSupplier)` identical across Tasks 6, 8, 10, 14; `.active(testId)` used at activation (Task 10), stop (Task 6), control (Task 8). `ExecWriter.write(...)` 6-arg + 5-arg overloads consistent across Tasks 4, 6.
