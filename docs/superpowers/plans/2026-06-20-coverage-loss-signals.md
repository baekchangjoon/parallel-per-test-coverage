# Coverage-Loss Signals (phase 1, pjacoco) Implementation Plan

> **개정 (2026-06-20, 필드 피드백 P2-4):** 아래 본문의 **`conservative` 귀속**(`n>1 → markConservative`,
> `attributionConservative()`, sidecar `attribution:"conservative"`)은 **개정 전 기록**이며 더 이상
> 유효하지 않다. 개정 후: 모호한(동시 active ≥2) 드롭은 per-test 무표기 + 전역 `Metrics.ambiguousDrops`
> 로만 집계(정확히 1개 active일 때만 exact 귀속), 옵트인 임계 `incompleteAttributionThreshold`
> (CLS-REQ-009) 추가, `markConservative`/`attributionConservative`는 제거됨. 권위 정의는
> `docs/superpowers/requirements/2026-06-20-coverage-loss-signals-requirements.md`(CLS-REQ-005 개정 /
> CLS-REQ-009 신규)다.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** in-process per-test 수집에서 워커/비-테스트 스레드로 새는 커버리지 손실을 런타임 카운터·1회성 WARN·per-test sidecar 플래그로 가시화한다(손실을 *고치는* 게 아니라 *보이게* 한다).

**Architecture:** 손실 발생 지점(`CoverageBridge.recordCoverage`의 `store==null`, `ServletAdvice.activate`의 `key==null`)에 신호를 심는다. 드롭은 `DropAttributor`가 현재 active in-process store 집합에 직접 귀속(1=exact / >1=conservative / 0=unattributed)하고, per-test 카운트는 `TestStore`에 누적돼 단일 `flush()` funnel로 sidecar에 직렬화된다. 빈-store 폐기 결정은 `TestStoreRegistry`의 synchronized 메서드에서 락 안 재read로 race-safe하게 한다.

**Tech Stack:** Java 17, JaCoCo core 0.8.12, ByteBuddy(advice 위빙), JUnit 5, Mockito. Gradle 멀티모듈(`:agent`). 테스트: `agent/src/test`(unit) · `agent/src/integrationTest`(ByteBuddy/직접호출 IT, `@Tag("e2e")` 제외) · `e2eTest`/`e2eJakartaTest`/`e2eCondyTest`(실 `-javaagent`).

## Global Constraints

- 출처: design `docs/superpowers/specs/2026-06-20-coverage-loss-signals-design.md`, 요구사항 `docs/superpowers/requirements/2026-06-20-coverage-loss-signals-requirements.md`.
- REQ-ID 네임스페이스: `CLS-REQ-00X` (기존 repo의 `REQ-00x`와 충돌 회피). 수용 테스트는 `@DisplayName("CLS-REQ-00X: …")`.
- 앱 비파괴: `recordCoverage`/`ServletAdvice`는 어떤 경우에도 애플리케이션 스레드에 예외를 던지지 않는다(기존 try/swallow 규약 유지).
- 후방호환: `TestStore` 3-인자 생성자 불변, `ExecWriter`/`Json` 기존 호출부 무변, sidecar 신규 필드는 `droppedProbes>0`일 때만 emit.
- summary 신규 키는 full-name(`missingTestIdInbound`/`droppedNoContext`/`unattributedDrops`); 기존 필드 rename 금지.
- 모든 수집 invocation 명령에 `--no-build-cache` 불필요(여기선 agent 모듈 단위 테스트라 무관).
- 커밋 메시지 말미:
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01LXH8qJRxzMv4Jhewi4tyks
  ```

---

### Task 1: Metrics 손실 카운터 + summary 노출

**REQ-IDs:** CLS-REQ-007

**Files:**
- Modify: `agent/src/main/java/io/pjacoco/agent/observability/Metrics.java`
- Test: `agent/src/test/java/io/pjacoco/agent/observability/MetricsTest.java`

**Interfaces:**
- Produces: `Metrics.missingTestIdInbound`, `Metrics.droppedNoContext`, `Metrics.unattributedDrops` (모두 `public final AtomicLong`); `summary()`에 세 토큰 포함.

- [ ] **Step 1: 실패 테스트 작성** — `MetricsTest.java`에 추가:

```java
    // CLS-REQ-007: summary exposes the three loss counters with their values
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("CLS-REQ-007: summary includes loss counters with values")
    void summary_includesLossCounters() {
        Metrics m = new Metrics();
        m.missingTestIdInbound.incrementAndGet();
        m.droppedNoContext.incrementAndGet();
        m.droppedNoContext.incrementAndGet();
        m.unattributedDrops.incrementAndGet();
        String s = m.summary();
        assertTrue(s.contains("missingTestIdInbound=1"), s);
        assertTrue(s.contains("droppedNoContext=2"), s);
        assertTrue(s.contains("unattributedDrops=1"), s);
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :agent:test --tests 'io.pjacoco.agent.observability.MetricsTest' -i`
Expected: 컴파일 실패(필드 없음) 또는 FAIL.

- [ ] **Step 3: 구현** — `Metrics.java`의 필드 블록에 추가(기존 `unmappedTraceIds` 아래):

```java
    /** Incremented when an inbound HTTP request reaches the instrumented server with no resolvable
     *  test.id (no tracer scope, no baggage) and no active CoverageContext, during a collection window. */
    public final AtomicLong missingTestIdInbound = new AtomicLong();
    /** Incremented every time a probe fires on a thread with no active CoverageContext (coverage dropped). */
    public final AtomicLong droppedNoContext = new AtomicLong();
    /** Subset of droppedNoContext where no in-process test store was active → not attributable to any test. */
    public final AtomicLong unattributedDrops = new AtomicLong();
```

`summary()`의 return 문자열 끝(`+ " unmapped=" + unmappedTraceIds.get();` 직전의 `;`를 바꿔) 이어붙인다:

```java
                + " unmapped=" + unmappedTraceIds.get()
                + " missingTestIdInbound=" + missingTestIdInbound.get()
                + " droppedNoContext=" + droppedNoContext.get()
                + " unattributedDrops=" + unattributedDrops.get();
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :agent:test --tests 'io.pjacoco.agent.observability.MetricsTest'`
Expected: PASS (기존 `summaryIncludesNewCounters` 포함 전부 green).

- [ ] **Step 5: 커밋**

```bash
git add agent/src/main/java/io/pjacoco/agent/observability/Metrics.java agent/src/test/java/io/pjacoco/agent/observability/MetricsTest.java
git commit -m "feat(metrics): add loss counters (missingTestIdInbound/droppedNoContext/unattributedDrops) [CLS-REQ-007]"
```

---

### Task 2: TestStore 드롭 누적 필드

**REQ-IDs:** CLS-REQ-004, CLS-REQ-005 (기반)

**Files:**
- Modify: `agent/src/main/java/io/pjacoco/agent/store/TestStore.java`
- Test: `agent/src/test/java/io/pjacoco/agent/store/TestStoreDropTest.java` (Create)

**Interfaces:**
- Produces: `TestStore.recordDrop()`, `TestStore.markConservative()`, `long TestStore.droppedProbes()`, `boolean TestStore.attributionConservative()`. 3-인자 생성자 불변.

- [ ] **Step 1: 실패 테스트 작성** — `TestStoreDropTest.java` 생성:

```java
package io.pjacoco.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TestStoreDropTest {
    @Test
    void dropFields_defaultZeroAndExact() {
        TestStore s = new TestStore("T1", 1L, null);
        assertEquals(0L, s.droppedProbes());
        assertFalse(s.attributionConservative());
    }

    @Test
    void recordDrop_increments_andMarkConservative() {
        TestStore s = new TestStore("T1", 1L, null);
        s.recordDrop();
        s.recordDrop();
        s.markConservative();
        assertEquals(2L, s.droppedProbes());
        assertTrue(s.attributionConservative());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :agent:test --tests 'io.pjacoco.agent.store.TestStoreDropTest' -i`
Expected: 컴파일 실패(메서드 없음).

- [ ] **Step 3: 구현** — `TestStore.java`에 추가:

import 블록에:
```java
import java.util.concurrent.atomic.AtomicLong;
```
필드(`byClass` 아래):
```java
    private final AtomicLong droppedProbes = new AtomicLong();   // probes lost on no-context threads, attributed here
    private volatile boolean attributionConservative;            // true if any drop was attributed under concurrency
```
메서드(`incrementRetry()` 아래):
```java
    /** Called by DropAttributor when a no-context probe is attributed to this active store. */
    public void recordDrop() { droppedProbes.incrementAndGet(); }
    public void markConservative() { attributionConservative = true; }
    public long droppedProbes() { return droppedProbes.get(); }
    public boolean attributionConservative() { return attributionConservative; }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :agent:test --tests 'io.pjacoco.agent.store.TestStoreDropTest'`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add agent/src/main/java/io/pjacoco/agent/store/TestStore.java agent/src/test/java/io/pjacoco/agent/store/TestStoreDropTest.java
git commit -m "feat(store): TestStore drop accumulation fields (droppedProbes/attributionConservative) [CLS-REQ-004/005]"
```

---

### Task 3: Json boolean + ExecWriter sidecar 조건부 필드

**REQ-IDs:** CLS-REQ-008 (sidecar 후방호환)

**Files:**
- Modify: `agent/src/main/java/io/pjacoco/agent/output/Json.java`
- Modify: `agent/src/main/java/io/pjacoco/agent/output/ExecWriter.java:37-49`
- Test: `agent/src/test/java/io/pjacoco/agent/output/ExecWriterTest.java`

**Interfaces:**
- Consumes: `TestStore.droppedProbes()`, `TestStore.attributionConservative()` (Task 2).
- Produces: `Json.put(String,boolean)`; sidecar JSON이 `droppedProbes>0`일 때만 `incompleteAttribution`/`droppedProbes`/`attribution` 포함.

- [ ] **Step 1: 실패 테스트 작성** — `ExecWriterTest.java`에 추가:

```java
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("CLS-REQ-008: no drop -> attribution fields omitted")
    void noDrop_omitsAttributionFields(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        TestStore store = new TestStore("T_ND", 1000L, null);
        store.record(1L, "com/example/Foo", 0, 1);
        new ExecWriter().write(dir, store, "passed", null, 2000L);
        String json = new String(Files.readAllBytes(dir.resolve("T_ND.json")), "UTF-8");
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("incompleteAttribution"), json);
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("droppedProbes"), json);
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("\"attribution\""), json);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("CLS-REQ-008: drop>0 -> attribution fields emitted")
    void withDrop_emitsAttributionFields(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        TestStore store = new TestStore("T_D", 1000L, null);
        store.recordDrop();
        store.recordDrop();
        new ExecWriter().write(dir, store, "passed", null, 2000L);
        String json = new String(Files.readAllBytes(dir.resolve("T_D.json")), "UTF-8");
        assertTrue(json.contains("\"incompleteAttribution\":true"), json);
        assertTrue(json.contains("\"droppedProbes\":2"), json);
        assertTrue(json.contains("\"attribution\":\"exact\""), json);
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :agent:test --tests 'io.pjacoco.agent.output.ExecWriterTest' -i`
Expected: FAIL(`withDrop_emitsAttributionFields` — 필드 미출력) / 컴파일은 통과.

- [ ] **Step 3: 구현 — Json.put(boolean)** — `Json.java`의 `put(String,long)` 아래 추가:

```java
    public Json put(String key, boolean value) {
        sep().append('"').append(esc(key)).append("\":").append(value);
        return this;
    }
```

- [ ] **Step 4: 구현 — ExecWriter 조건부 emit** — `ExecWriter.write(...)`의 sidecar 빌드부를 교체. 기존:

```java
        String json = new Json()
                .put("testId", store.testId())
                ...
                .put("status", status)
                .toString();
```
를 다음으로:
```java
        Json j = new Json()
                .put("testId", store.testId())
                .put("exec", store.testId() + ".exec")
                .put("precision", "line")
                .put("startedAtMillis", store.startedAtMillis())
                .put("stoppedAtMillis", stoppedAtMillis)
                .put("durationMs", stoppedAtMillis - store.startedAtMillis())
                .put("result", result)                 // null -> omitted
                .put("classCount", store.classCount())
                .put("retryCount", store.retryCount())
                .put("shardId", store.shardId())        // null -> omitted
                .put("status", status);
        if (store.droppedProbes() > 0) {                // CLS-REQ-008: additive, only when loss attributed
            j.put("incompleteAttribution", true)
             .put("droppedProbes", store.droppedProbes())
             .put("attribution", store.attributionConservative() ? "conservative" : "exact");
        }
        String json = j.toString();
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :agent:test --tests 'io.pjacoco.agent.output.ExecWriterTest'`
Expected: PASS (기존 `writesExecReadableByJacocoAndSidecar` 포함 green — 그 케이스는 drop=0이라 신규 필드 없음).

- [ ] **Step 6: 커밋**

```bash
git add agent/src/main/java/io/pjacoco/agent/output/Json.java agent/src/main/java/io/pjacoco/agent/output/ExecWriter.java agent/src/test/java/io/pjacoco/agent/output/ExecWriterTest.java
git commit -m "feat(output): emit conditional attribution fields in sidecar; Json boolean support [CLS-REQ-008]"
```

---

### Task 4: DropAttributor + 드롭 카운트/귀속 (CoverageBridge·Registry·Bootstrap)

**REQ-IDs:** CLS-REQ-002, CLS-REQ-003

**Files:**
- Create: `agent/src/main/java/io/pjacoco/agent/probe/DropAttributor.java`
- Modify: `agent/src/main/java/io/pjacoco/agent/probe/CoverageBridge.java`
- Modify: `agent/src/main/java/io/pjacoco/agent/store/TestStoreRegistry.java`
- Modify: `agent/src/main/java/io/pjacoco/agent/Bootstrap.java` (premain, `CoverageBridge.bindMetrics(metrics);` 직후)
- Test: `agent/src/integrationTest/java/io/pjacoco/agent/it/OrphanProbeCounterIT.java` (Create), `agent/src/integrationTest/java/io/pjacoco/agent/it/CrossThreadDropIT.java` (Create)

**Interfaces:**
- Consumes: `Metrics.droppedNoContext`/`unattributedDrops` (Task 1), `TestStore.recordDrop()`/`markConservative()` (Task 2).
- Produces: `TestStoreRegistry.activeSnapshot()`→`Collection<TestStore>`, `TestStoreRegistry.hasActive()`→`boolean`; `CoverageBridge.bindAttributor(DropAttributor)`; `DropAttributor(TestStoreRegistry, Metrics)` + `attribute()`.

- [ ] **Step 1: 실패 테스트 작성 — OrphanProbeCounterIT** (CLS-REQ-002):

```java
package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.probe.CoverageBridge;
import io.pjacoco.agent.probe.DropAttributor;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrphanProbeCounterIT {
    @AfterEach
    void reset() {
        CoverageContext.clear();
        CoverageBridge.bindMetrics(null);
        CoverageBridge.bindAttributor(null);
    }

    private static TestStoreRegistry reg(Path dir, Metrics m) {
        final AtomicLong clock = new AtomicLong(1L);
        return new TestStoreRegistry(dir, new ExecWriter(), m, new AgentLog(),
                false, 100, new LongSupplier() { public long getAsLong() { return clock.get(); } });
    }

    @Test
    @DisplayName("CLS-REQ-002: no-context drop increments droppedNoContext; unattributed when no active store")
    void noContextThread_incrementsDroppedNoContext(@TempDir Path dir) {
        Metrics m = new Metrics();
        TestStoreRegistry registry = reg(dir, m);
        CoverageBridge.bindMetrics(m);
        CoverageBridge.bindAttributor(new DropAttributor(registry, m));
        CoverageContext.clear();

        // no active store -> droppedNoContext + unattributedDrops
        CoverageBridge.recordCoverage(String.class, 1L, 0);
        assertEquals(1L, m.droppedNoContext.get());
        assertEquals(1L, m.unattributedDrops.get());

        // active store present, but this thread has no context -> attributed to the store, not unattributed
        registry.start("T1", null, null);
        CoverageBridge.recordCoverage(String.class, 1L, 0);
        assertEquals(2L, m.droppedNoContext.get());
        assertEquals(1L, m.unattributedDrops.get());
        assertEquals(1L, registry.peek("T1").droppedProbes());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :agent:integrationTest --tests 'io.pjacoco.agent.it.OrphanProbeCounterIT' -i`
Expected: 컴파일 실패(`bindAttributor`/`DropAttributor`/`droppedProbes` 없음).

- [ ] **Step 3: 구현 — TestStoreRegistry 스냅샷/hasActive** — import에 추가:
```java
import java.util.ArrayList;
import java.util.Collection;
```
메서드 추가(클래스 내 아무 위치, 예: `peek` 아래):
```java
    /** Snapshot of currently-active stores (safe to iterate after return). Used by DropAttributor. */
    public Collection<TestStore> activeSnapshot() { return new ArrayList<TestStore>(stores.values()); }
    /** True if any test store is currently active (collection window open). */
    public boolean hasActive() { return !stores.isEmpty(); }
```

- [ ] **Step 4: 구현 — DropAttributor** — `DropAttributor.java` 생성:

```java
package io.pjacoco.agent.probe;

import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.util.Collection;

/**
 * Attributes a no-context probe drop to the currently-active in-process test store(s).
 * size 1 -> exact (that store); size >1 -> conservative (every concurrent store); size 0 -> unattributed
 * (metric only, no per-test sidecar). Invoked only on the already-broken store==null path, so its cost
 * is never paid by a correct collection.
 */
public final class DropAttributor {
    private final TestStoreRegistry registry;
    private final Metrics metrics;

    public DropAttributor(TestStoreRegistry registry, Metrics metrics) {
        this.registry = registry;
        this.metrics = metrics;
    }

    public void attribute() {
        Collection<TestStore> active = registry.activeSnapshot();
        int n = active.size();
        if (n == 0) {
            metrics.unattributedDrops.incrementAndGet();
            return;
        }
        boolean conservative = n > 1;
        for (TestStore s : active) {
            s.recordDrop();
            if (conservative) s.markConservative();
        }
    }
}
```

- [ ] **Step 5: 구현 — CoverageBridge** — `attributor` static + bind + drop 분기. import에 `import io.pjacoco.agent.probe.DropAttributor;`는 동일 패키지이므로 불요. 변경:

`metrics` 필드 아래에:
```java
    private static volatile DropAttributor attributor;
    public static void bindAttributor(DropAttributor a) { attributor = a; }
```
`recordCoverage`의 `if (store == null) return;`를 교체:
```java
            TestStore store = CoverageContext.get();
            if (store == null) {
                Metrics m = metrics;
                if (m != null) m.droppedNoContext.incrementAndGet();
                DropAttributor a = attributor;
                if (a != null) a.attribute();
                return;                                  // untagged thread: drop, but now counted + attributed
            }
```

- [ ] **Step 6: 구현 — Bootstrap 바인딩** — `Bootstrap.java`의 `CoverageBridge.bindMetrics(metrics);` **직후, `ProbeInstrumentation.install(...)` 전**에 추가:

```java
        CoverageBridge.bindAttributor(new io.pjacoco.agent.probe.DropAttributor(registry, metrics));
```

- [ ] **Step 7: 통과 확인 (CLS-REQ-002)**

Run: `./gradlew :agent:integrationTest --tests 'io.pjacoco.agent.it.OrphanProbeCounterIT'`
Expected: PASS.

- [ ] **Step 8: 실패 테스트 작성 — CrossThreadDropIT** (CLS-REQ-003):

```java
package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.probe.CoverageBridge;
import io.pjacoco.agent.probe.DropAttributor;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrossThreadDropIT {
    @AfterEach
    void reset() {
        CoverageContext.clear();
        CoverageBridge.bindMetrics(null);
        CoverageBridge.bindAttributor(null);
    }

    @Test
    @DisplayName("CLS-REQ-003: probe on a delegated worker thread (no servlet) is caught by droppedNoContext")
    void asyncWorker_incrementsDroppedNoContext(@TempDir Path dir) throws Exception {
        Metrics m = new Metrics();
        final AtomicLong clock = new AtomicLong(1L);
        TestStoreRegistry registry = new TestStoreRegistry(dir, new ExecWriter(), m, new AgentLog(),
                false, 100, new LongSupplier() { public long getAsLong() { return clock.get(); } });
        CoverageBridge.bindMetrics(m);
        CoverageBridge.bindAttributor(new DropAttributor(registry, m));
        registry.start("T1", null, null);

        // simulate @Async/Executor: work runs on a child thread that never had a CoverageContext set
        Thread worker = new Thread(() -> {
            CoverageContext.clear();                  // worker has no context
            CoverageBridge.recordCoverage(String.class, 7L, 0);
        });
        worker.start();
        worker.join();

        assertEquals(1L, m.droppedNoContext.get());
        assertTrue(registry.peek("T1").droppedProbes() >= 1);
    }
}
```

- [ ] **Step 9: 통과 확인 (CLS-REQ-003)**

Run: `./gradlew :agent:integrationTest --tests 'io.pjacoco.agent.it.CrossThreadDropIT'`
Expected: PASS.

- [ ] **Step 10: 커밋**

```bash
git add agent/src/main/java/io/pjacoco/agent/probe/DropAttributor.java agent/src/main/java/io/pjacoco/agent/probe/CoverageBridge.java agent/src/main/java/io/pjacoco/agent/store/TestStoreRegistry.java agent/src/main/java/io/pjacoco/agent/Bootstrap.java agent/src/integrationTest/java/io/pjacoco/agent/it/OrphanProbeCounterIT.java agent/src/integrationTest/java/io/pjacoco/agent/it/CrossThreadDropIT.java
git commit -m "feat(probe): count + attribute no-context probe drops via DropAttributor [CLS-REQ-002/003]"
```

---

### Task 5: race-safe 빈-store 가드 (stopUnlessEmpty) — per-test 플래그 직렬

**REQ-IDs:** CLS-REQ-004

**Files:**
- Modify: `agent/src/main/java/io/pjacoco/agent/store/TestStoreRegistry.java`
- Modify: `agent/src/main/java/io/pjacoco/agent/api/CoverageControl.java:53-66`
- Test: `agent/src/integrationTest/java/io/pjacoco/agent/it/IncompleteAttributionSerialIT.java` (Create)

**Interfaces:**
- Consumes: `TestStore.droppedProbes()` (Task 2), `ExecWriter` 조건부 sidecar (Task 3), `CoverageControl.bindRegistry` (기존).
- Produces: `TestStoreRegistry.stopUnlessEmpty(String testId, String result)` (synchronized).

- [ ] **Step 1: 실패 테스트 작성 — IncompleteAttributionSerialIT** (CLS-REQ-004):

```java
package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjacoco.agent.api.CoverageControl;
import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncompleteAttributionSerialIT {
    private static TestStoreRegistry reg(Path dir) {
        final AtomicLong clock = new AtomicLong(1L);
        return new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(),
                false, 100, new LongSupplier() { public long getAsLong() { return clock.get(); } });
    }

    @Test
    @DisplayName("CLS-REQ-004: worker-only test (classCount=0, droppedProbes>0) is flagged exact, not discarded")
    void workerOnlyTest_flaggedExact_notDiscarded(@TempDir Path dir) throws Exception {
        TestStoreRegistry registry = reg(dir);
        CoverageControl.bindRegistry(registry);
        registry.start("T1", null, null);
        registry.peek("T1").recordDrop();             // worker dropped; test thread recorded nothing

        CoverageControl.deactivate("T1", "passed");

        Path j = dir.resolve("T1.json");
        assertTrue(Files.exists(j), "sidecar must be written for a drop-only test");
        String json = new String(Files.readAllBytes(j), "UTF-8");
        assertTrue(json.contains("\"incompleteAttribution\":true"), json);
        assertTrue(json.contains("\"attribution\":\"exact\""), json);
    }

    @Test
    @DisplayName("CLS-REQ-004: truly empty store (no class, no drop) is discarded")
    void trulyEmpty_discarded(@TempDir Path dir) {
        TestStoreRegistry registry = reg(dir);
        CoverageControl.bindRegistry(registry);
        registry.start("T2", null, null);
        CoverageControl.deactivate("T2", "passed");
        assertFalse(Files.exists(dir.resolve("T2.json")), "truly empty store must not write a sidecar");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :agent:integrationTest --tests 'io.pjacoco.agent.it.IncompleteAttributionSerialIT' -i`
Expected: FAIL — 현재 `deactivate`가 classCount==0(드롭 있어도) store를 discard → sidecar 없음.

- [ ] **Step 3: 구현 — TestStoreRegistry.stopUnlessEmpty** — `stop(...)` 아래 추가:

```java
    /** Race-safe stop: under the registry lock, remove then re-read droppedProbes; discard only a
     *  truly empty store (no class probes AND no attributed drops), else flush so the loss is visible. */
    public synchronized void stopUnlessEmpty(String testId, String result) {
        TestStore s = stores.remove(testId);
        if (s == null) {
            log.warn("stop-missing", "stopUnlessEmpty for unknown testId=" + testId);
            return;
        }
        if (s.classCount() == 0 && s.droppedProbes() == 0) {
            return;                                  // truly empty -> discard (no garbage file)
        }
        flush(s, result, "complete");
        metrics.testsCompleted.incrementAndGet();
    }
```

- [ ] **Step 4: 구현 — CoverageControl.deactivate** — 본문을 교체:

```java
    /** Clear the thread context and flush via the registry (race-safe empty-store guard lives there). */
    public static void deactivate(String testId, String result) {
        try {
            CoverageContext.clear();
            TestStoreRegistry reg = registry;
            if (reg == null || testId == null) {
                return;
            }
            reg.stopUnlessEmpty(testId, result);
        } catch (Throwable ignored) {
            // never disturb the test
        }
    }
```
(기존 `peek`+`classCount()==0`+`discard`/`stop` 분기 제거.)

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :agent:integrationTest --tests 'io.pjacoco.agent.it.IncompleteAttributionSerialIT'`
Expected: PASS (두 케이스 모두).

- [ ] **Step 6: 기존 in-process 단위 회귀 확인** — deactivate 경로 변경이 기존 테스트킷 동작을 깨지 않는지:

Run: `./gradlew :agent:test :testkit-junit5:test`
Expected: PASS.

- [ ] **Step 7: 커밋**

```bash
git add agent/src/main/java/io/pjacoco/agent/store/TestStoreRegistry.java agent/src/main/java/io/pjacoco/agent/api/CoverageControl.java agent/src/integrationTest/java/io/pjacoco/agent/it/IncompleteAttributionSerialIT.java
git commit -m "feat(store): race-safe stopUnlessEmpty so drop-only tests are flagged not discarded [CLS-REQ-004]"
```

---

### Task 6: ServletAdvice 무-test.id WARN (active-store 게이트, 1회성)

**REQ-IDs:** CLS-REQ-001

**Files:**
- Modify: `agent/src/main/java/io/pjacoco/agent/inbound/servlet/ServletAdvice.java`
- Modify: `agent/src/main/java/io/pjacoco/agent/inbound/servlet/ServletInboundActivator.java:24-27`
- Test: `agent/src/integrationTest/java/io/pjacoco/agent/it/MissingTestIdInboundIT.java` (Create)

**Interfaces:**
- Consumes: `Metrics.missingTestIdInbound` (Task 1), `TestStoreRegistry.hasActive()` (Task 4), `ServletAdvice.registry`/`metrics` (기존 statics).
- Produces: `ServletAdvice.log` (public static volatile AgentLog), `ServletAdvice.resetWarnGuardForTest()` (test-only).

- [ ] **Step 1: 실패 테스트 작성 — MissingTestIdInboundIT** (CLS-REQ-001), `TracerAbsentFallbackIT` 패턴 + System.err 캡처:

```java
package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.inbound.servlet.ServletAdvice;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MissingTestIdInboundIT {
    @AfterEach
    void reset() {
        CoverageContext.clear();
        ServletAdvice.registry = null;
        ServletAdvice.metrics = null;
        ServletAdvice.log = null;
        ServletAdvice.resetWarnGuardForTest();
    }

    private static TestStoreRegistry reg(Path dir, Metrics m) {
        final AtomicLong clock = new AtomicLong(1L);
        return new TestStoreRegistry(dir, new ExecWriter(), m, new AgentLog(),
                false, 100, new LongSupplier() { public long getAsLong() { return clock.get(); } });
    }

    @Test
    @DisplayName("CLS-REQ-001: no-baggage request during a collection window counts each time, warns once")
    void missingId_increments_and_warnsOnce(@TempDir Path dir) throws Exception {
        Metrics m = new Metrics();
        TestStoreRegistry registry = reg(dir, m);
        registry.start("T1", null, null);            // active store -> collection window open
        ServletAdvice.registry = registry;
        ServletAdvice.metrics = m;
        ServletAdvice.log = new AgentLog();

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("baggage")).thenReturn(null);

        PrintStream origErr = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, "UTF-8"));
        try {
            CoverageContext.clear();
            ServletAdvice.activate(req);
            ServletAdvice.activate(req);
        } finally {
            System.setErr(origErr);
        }
        assertEquals(2L, m.missingTestIdInbound.get());          // counter every time
        String err = buf.toString("UTF-8");
        int occurrences = err.split("no test.id", -1).length - 1;
        assertEquals(1, occurrences, "WARN must be logged exactly once: " + err);
    }

    @Test
    @DisplayName("CLS-REQ-001: request with valid baggage produces no missing-id signal")
    void withBaggage_noSignal(@TempDir Path dir) {
        Metrics m = new Metrics();
        TestStoreRegistry registry = reg(dir, m);
        registry.start("T_BAG", null, null);
        ServletAdvice.registry = registry;
        ServletAdvice.metrics = m;
        ServletAdvice.log = new AgentLog();

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("baggage")).thenReturn("test.id=T_BAG");
        CoverageContext.clear();
        ServletAdvice.activate(req);
        assertEquals(0L, m.missingTestIdInbound.get());
    }

    @Test
    @DisplayName("CLS-REQ-001: no active store (startup) -> no missing-id signal")
    void noActiveStore_noSignal(@TempDir Path dir) {
        Metrics m = new Metrics();
        TestStoreRegistry registry = reg(dir, m);       // no start() -> no active store
        ServletAdvice.registry = registry;
        ServletAdvice.metrics = m;
        ServletAdvice.log = new AgentLog();

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("baggage")).thenReturn(null);
        CoverageContext.clear();
        ServletAdvice.activate(req);
        assertEquals(0L, m.missingTestIdInbound.get());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :agent:integrationTest --tests 'io.pjacoco.agent.it.MissingTestIdInboundIT' -i`
Expected: 컴파일 실패(`ServletAdvice.log`/`resetWarnGuardForTest` 없음).

- [ ] **Step 3: 구현 — ServletAdvice** — import 추가:
```java
import io.pjacoco.agent.observability.AgentLog;
import java.util.concurrent.atomic.AtomicBoolean;
```
static 추가(`metrics` 아래):
```java
    /** Bound once by ServletInboundActivator; used for the once-per-JVM missing-test.id WARN. */
    public static volatile AgentLog log;
    private static final AtomicBoolean MISSING_ID_WARNED = new AtomicBoolean(false);
    /** Test-only: reset the once-per-JVM warn guard between integration tests. */
    public static void resetWarnGuardForTest() { MISSING_ID_WARNED.set(false); }
```
`activate(...)`의 key 적용 블록을 교체. 기존:
```java
            if (key != null) {
                TestStore store = reg.forCoverageKey(key);
                if (store != null) {
                    CoverageContext.set(store);
                }
            }
```
를:
```java
            if (key != null) {
                TestStore store = reg.forCoverageKey(key);
                if (store != null) {
                    CoverageContext.set(store);
                }
            } else if (CoverageContext.get() == null && reg.hasActive()) {
                // No tracer/baggage test.id, no active context on this thread, but a collection window is
                // open → this request's probes will be dropped. Surface it (CLS-REQ-001).
                Metrics mm = metrics;
                if (mm != null) mm.missingTestIdInbound.incrementAndGet();
                if (MISSING_ID_WARNED.compareAndSet(false, true)) {
                    String msg = "inbound HTTP request had no test.id (no tracer scope, no 'baggage: test.id') "
                            + "and no active in-process context; its probes are not attributed to any test. "
                            + "For black-box HTTP tests (SpringBootTest RANDOM_PORT + TestRestTemplate/RestAssured) "
                            + "use the out-of-process baggage model. (logged once; see shutdown summary for totals)";
                    AgentLog lg = log;
                    if (lg != null) lg.warn("missing-test-id", msg);
                    else System.err.println("[pjacoco][WARN] " + msg);
                }
            }
```

- [ ] **Step 4: 구현 — ServletInboundActivator 바인딩** — 생성자에 추가(기존 두 줄 아래):
```java
        ServletAdvice.log      = log;
```

- [ ] **Step 5: 통과 확인 (CLS-REQ-001)**

Run: `./gradlew :agent:integrationTest --tests 'io.pjacoco.agent.it.MissingTestIdInboundIT'`
Expected: PASS (세 케이스 모두).

- [ ] **Step 6: 커밋**

```bash
git add agent/src/main/java/io/pjacoco/agent/inbound/servlet/ServletAdvice.java agent/src/main/java/io/pjacoco/agent/inbound/servlet/ServletInboundActivator.java agent/src/integrationTest/java/io/pjacoco/agent/it/MissingTestIdInboundIT.java
git commit -m "feat(servlet): once-per-JVM WARN + counter for no-test.id inbound during collection [CLS-REQ-001]"
```

---

### Task 7: 병렬 보수 귀속 IT

**REQ-IDs:** CLS-REQ-005

**Files:**
- Test: `agent/src/integrationTest/java/io/pjacoco/agent/it/IncompleteAttributionParallelIT.java` (Create)

**Interfaces:**
- Consumes: `DropAttributor`/`CoverageBridge`/`TestStoreRegistry`/`TestStore` (Task 2,4).

- [ ] **Step 1: 실패/검증 테스트 작성** — 동시 active store ≥2에서 드롭 → 전부 conservative:

```java
package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.probe.CoverageBridge;
import io.pjacoco.agent.probe.DropAttributor;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncompleteAttributionParallelIT {
    @AfterEach
    void reset() {
        CoverageContext.clear();
        CoverageBridge.bindMetrics(null);
        CoverageBridge.bindAttributor(null);
    }

    @Test
    @DisplayName("CLS-REQ-005: drop while >=2 stores active -> all flagged conservative, no loss")
    void concurrentDrops_flaggedConservative_noLoss(@TempDir Path dir) {
        Metrics m = new Metrics();
        final AtomicLong clock = new AtomicLong(1L);
        TestStoreRegistry registry = new TestStoreRegistry(dir, new ExecWriter(), m, new AgentLog(),
                false, 100, new LongSupplier() { public long getAsLong() { return clock.get(); } });
        CoverageBridge.bindMetrics(m);
        CoverageBridge.bindAttributor(new DropAttributor(registry, m));

        registry.start("A", null, null);
        registry.start("B", null, null);              // two concurrently active stores
        CoverageContext.clear();                      // this thread has no context -> drop
        CoverageBridge.recordCoverage(String.class, 1L, 0);

        assertTrue(registry.peek("A").droppedProbes() >= 1 && registry.peek("A").attributionConservative(),
                "A must be flagged conservative");
        assertTrue(registry.peek("B").droppedProbes() >= 1 && registry.peek("B").attributionConservative(),
                "B must be flagged conservative");
    }
}
```

- [ ] **Step 2: 통과 확인**

Run: `./gradlew :agent:integrationTest --tests 'io.pjacoco.agent.it.IncompleteAttributionParallelIT'`
Expected: PASS (Task 4 구현으로 이미 동작 — 이 task는 병렬 계약을 고정하는 수용 테스트).

- [ ] **Step 3: 커밋**

```bash
git add agent/src/integrationTest/java/io/pjacoco/agent/it/IncompleteAttributionParallelIT.java
git commit -m "test(it): conservative attribution for concurrent active stores [CLS-REQ-005]"
```

---

### Task 8: 오탐 0 IT (in-thread / MockMvc surrogate)

**REQ-IDs:** CLS-REQ-006

**Files:**
- Test: `agent/src/integrationTest/java/io/pjacoco/agent/it/NoFalsePositiveInThreadIT.java` (Create)

**Interfaces:**
- Consumes: `CoverageBridge`/`DropAttributor`/`ServletAdvice`/`CoverageContext`/`TestStoreRegistry` (Task 4,6).

- [ ] **Step 1: 검증 테스트 작성** — 컨텍스트가 set된 테스트 스레드는 무신호(직접 호출 + servlet dispatch surrogate):

```java
package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.inbound.servlet.ServletAdvice;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.probe.CoverageBridge;
import io.pjacoco.agent.probe.DropAttributor;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NoFalsePositiveInThreadIT {
    @AfterEach
    void reset() {
        CoverageContext.clear();
        CoverageBridge.bindMetrics(null);
        CoverageBridge.bindAttributor(null);
        ServletAdvice.registry = null;
        ServletAdvice.metrics = null;
        ServletAdvice.log = null;
        ServletAdvice.resetWarnGuardForTest();
    }

    private static TestStoreRegistry reg(Path dir, Metrics m) {
        final AtomicLong clock = new AtomicLong(1L);
        return new TestStoreRegistry(dir, new ExecWriter(), m, new AgentLog(),
                false, 100, new LongSupplier() { public long getAsLong() { return clock.get(); } });
    }

    @Test
    @DisplayName("CLS-REQ-006: production code run on the test thread (context set) emits no drop signal")
    void directCall_noSignal(@TempDir Path dir) {
        Metrics m = new Metrics();
        TestStoreRegistry registry = reg(dir, m);
        CoverageBridge.bindMetrics(m);
        CoverageBridge.bindAttributor(new DropAttributor(registry, m));
        registry.start("T1", null, null);
        CoverageContext.set(registry.peek("T1"));     // in-thread: context present
        CoverageBridge.recordCoverage(String.class, 1L, 0);
        assertEquals(0L, m.droppedNoContext.get());
    }

    @Test
    @DisplayName("CLS-REQ-006: MockMvc-equivalent servlet dispatch on the test thread (context set) emits no missing-id")
    void contextSetServletDispatch_noSignal(@TempDir Path dir) {
        Metrics m = new Metrics();
        TestStoreRegistry registry = reg(dir, m);
        registry.start("T1", null, null);
        ServletAdvice.registry = registry;
        ServletAdvice.metrics = m;
        ServletAdvice.log = new AgentLog();
        TestStore store = registry.peek("T1");
        CoverageContext.set(store);                   // MockMvc dispatch runs on the test thread w/ context

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("baggage")).thenReturn(null);
        ServletAdvice.activate(req);                  // context!=null -> gated out
        assertEquals(0L, m.missingTestIdInbound.get());
    }
}
```

- [ ] **Step 2: 통과 확인**

Run: `./gradlew :agent:integrationTest --tests 'io.pjacoco.agent.it.NoFalsePositiveInThreadIT'`
Expected: PASS.

- [ ] **Step 3: 커밋**

```bash
git add agent/src/integrationTest/java/io/pjacoco/agent/it/NoFalsePositiveInThreadIT.java
git commit -m "test(it): no false-positive signal for in-thread/MockMvc dispatch [CLS-REQ-006]"
```

---

### Task 9: 전체 회귀 (e2e 포함) + 매트릭스 갱신

**REQ-IDs:** CLS-REQ-008

**Files:**
- Modify: `docs/superpowers/requirements/2026-06-20-coverage-loss-signals-requirements.md` (매트릭스 상태)

**Interfaces:** 없음(검증 task).

- [ ] **Step 1: agent unit + integration 전체**

Run: `./gradlew :agent:test :agent:integrationTest :testkit-junit5:test`
Expected: PASS. 신규 IT 6종 + 단위 확장 포함 green.

- [ ] **Step 2: out-of-process baggage e2e 3종 (CI 동등)**

Run: `./gradlew :agent:e2eTest :agent:e2eJakartaTest :agent:e2eCondyTest`
Expected: PASS. ServletAdvice/sidecar 변경이 servlet(javax/jakarta) e2e·condy 경로를 깨지 않음을 확인.
(환경상 일부 e2e가 실행 불가하면, 무엇을 왜 못 돌렸는지 명시하고 가능한 것만 보고한다.)

- [ ] **Step 3: 매트릭스 100% green 갱신** — 요구사항명세의 추적 매트릭스 Status를 실제 통과 테스트와 대조해 🟢로 갱신하고 Coverage 줄을 `8/8 green (100%)`로:

```bash
# 각 REQ의 Status를 🟢 green으로, Coverage 줄을 8/8 green (100%)로 수정
```

- [ ] **Step 4: 커밋**

```bash
git add docs/superpowers/requirements/2026-06-20-coverage-loss-signals-requirements.md
git commit -m "docs(req): coverage-loss-signals 추적 매트릭스 8/8 green [CLS-REQ-001..008]"
```

---

## Self-Review

**1. Spec coverage:** 요구사항명세 CLS-REQ-001~008 전부 task로 매핑됨 — 001→T6, 002→T4, 003→T4, 004→T2+T5, 005→T2+T4+T7, 006→T8, 007→T1, 008→T3+T9. design §4 컴포넌트(①→T6, ②→T4, DropAttributor→T4, 빈-store 가드 race-safe→T5, sidecar→T3, summary→T1, TestStore 필드→T2)·§5 하니스 분리(IT vs e2eTest→T4~T8, T9) 모두 반영. ✅

**2. Placeholder scan:** 모든 코드 step에 실제 코드/명령/기대출력 포함. TODO/TBD 없음. ✅

**3. Type consistency:** `recordDrop()`/`droppedProbes()`/`attributionConservative()`/`markConservative()`(T2) ↔ `DropAttributor`(T4) ↔ `ExecWriter`(T3) ↔ `stopUnlessEmpty`(T5) 시그니처 일치. `bindAttributor(DropAttributor)`/`DropAttributor(TestStoreRegistry, Metrics)`(T4) ↔ Bootstrap 호출(T4 Step6) 일치. `activeSnapshot()`/`hasActive()`(T4) ↔ `ServletAdvice`(T6)/`DropAttributor`(T4) 사용 일치. ✅
