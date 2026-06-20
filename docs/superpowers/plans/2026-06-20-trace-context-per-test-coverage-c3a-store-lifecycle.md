# Trace Context per-test Coverage — C3a Implementation Plan (trace-store 생명주기)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **출처:** design spec `docs/superpowers/specs/2026-06-19-trace-context-per-test-coverage-design.md`(§6.4 생명주기), 요구사항명세 `docs/superpowers/requirements/2026-06-19-trace-context-per-test-coverage-requirements.md`.
> **분해:** C3는 **C3a(trace-store 생명주기, 단일 서비스·in-process 검증)** 와 **C3b(분산 수집/병합, Docker E2E·GA-2 게이트)** 로 나눈다. 이 plan은 **C3a만** 다룬다 — JVM 종료에 의존하지 않고 traceId-키 store를 flush/evict하는 생명주기. C3a는 C3b의 **선결조건**(상시 가동 서비스가 per-traceId `.exec`를 산출할 수 있어야 중앙 수집이 가능). C3b는 C3a green 이후 별도 plan(`-c3b-distributed.md`).

**Goal:** 상시 가동(트레이서 모드) 서비스에서 traceId-키 store를 JVM 종료 없이 **idle reaper로 주기적 flush+evict**하고, flush 이후 늦은 쓰기를 **grace 기간 동안 보존**하며, 높은 traceId 카디널리티에서 **idle store를 in-flight보다 먼저 evict**한다(불가피한 in-flight evict는 관측).

**Architecture:** 핫패스·store 구조의 핵심은 무변경. `TestStore`에 **clock-free `writes` 카운터**(record()에서 plain `long++`, 트레이서 조회·ThreadLocal read 추가 없음 → REQ-001 letter 유지)와 reaper가 갱신하는 `lastActivityMillis`만 추가한다. 신규 `TraceStoreReaper`(주입 clock + `tick()`)가 idle store를 flush+grace+evict하고, Bootstrap이 트레이서 모드에서만 데몬 스레드로 주기 호출한다. `TestStoreRegistry.enforceCap()`을 oldest-start → **idle-우선**(in-flight 보호) eviction으로 교체한다.

**Tech Stack:** Java 8, 기존 `TestStoreRegistry`/`TestStore`/`ExecWriter`, `java.util.concurrent`(데몬 스레드/`ScheduledExecutorService`), JUnit5(agent 단위/통합), Gradle.

## Global Constraints

- 핫패스 `CoverageBridge.recordCoverage`는 `CoverageContext`(ThreadLocal) **1-read → store.record()** 유지. C3a가 record()에 추가하는 것은 **clock·트레이서·동기화 없는 `writes++`(plain long, benign race)** 하나뿐 — probe당 트레이서/시계 조회 0 (REQ-001, design §3-1). **clock(System.currentTimeMillis)은 reaper에만**, 절대 record() 안에 넣지 않는다.
- 트레이서 **런타임 하드 의존 0**, Java 8. reaper/생명주기 코드는 순수 JDK.
- best-effort: reaper/flush/evict 경로는 `catch (Throwable)` swallow, SUT로 throw 금지 (REQ-003). reaper 데몬 스레드는 예외로 죽지 않는다(루프 내 swallow).
- 생명주기 신규 동작은 **트레이서 모드 전용**(`traceKeyAutoCreate=true`). 비-트레이서 모드(servlet/junit testId, stop()로 flush)는 현행 그대로 — reaper 미가동, enforceCap 동작은 idle-우선이되 testId 모드에서도 안전(아래 REQ-018 비고).
- flush는 기존 `ExecWriter`(per-store `<key>.exec`/`.json`)를 재사용 — 출력 포맷·스키마 무변경.

---

## 범위(REQ 매핑)와 완료 정의

| REQ-ID | 우선순위 | 내용 | 산출 Task |
|--------|----------|------|-----------|
| REQ-016 | Must | trace-store flush 생명주기(idle reaper, JVM 종료 불필요) | T2, T3, T6 |
| REQ-017 | Should | late-write grace period(flush 이후 늦은 쓰기 비유실) | T4 |
| REQ-018 | Should | 높은 카디널리티에서 in-flight trace eviction 방지(idle-우선) | T5 |
| REQ-019 | Should(부분) | `evictedInFlightTraces` 카운터(C3 잔여) | T1, T5 |

**C3a 완료 정의(DoD):** REQ-016(Must) 🟢, REQ-017·018(Should) 🟢, REQ-019의 `evictedInFlightTraces` 추가로 **REQ-019 🟢 완성**(C1 scopeHookInjectionFailures+fallbackActivations, C2 unmappedTraceIds, C3a evictedInFlightTraces → 전 카운터 완료). 각 green REQ가 실제 통과 테스트와 대응(테스트명 대조). 전체 regression(unit+integration+기존 E2E) green. 영향 문서 갱신.

**더블 루프:** 바깥 루프(수용) = REQ-016 `TraceStoreLifecycleIT`(T6). 안쪽 루프 = T1~T5 단위/통합 TDD. C3a E2E는 **in-process 통합이 최고 실현가능 레벨**(상시 가동 서비스의 reaper 동작은 주입 clock+수동 tick으로 결정론적 검증; Docker 다중 서비스는 C3b). 요구사항명세 REQ-016 Level "integration/E2E"를 이 근거로 "integration(주입 clock 결정론)"으로 매트릭스에 명시한다.

---

## 핵심 설계 결정 (3-벤더 리뷰 반영)

1. **idle 추적의 핫패스 비용 = `writes++` 하나.** REQ-016/017/018은 모두 "이 store가 아직 쓰이는가"를 알아야 한다. 후보: (a) scope-enter timestamp(핫패스 0이지만 순수-probe 늦은 쓰기 미감지 → REQ-017 취약), (b) record()에서 `System.currentTimeMillis()`(probe당 syscall — **금지**, REQ-001 위반), (c) record()에서 **clock 없는 `long writes++`** + reaper가 시간 부여. **(c) 채택**.
2. **JMM 안전성 (Critical 해소).** `writes`는 volatile 아님 → JMM상 reaper가 probe 스레드의 증가를 stale하게 읽어 활성 trace를 idle로 오판할 수 있다. **유실을 evict 설계로 차단한다**(volatile로 만들면 probe당 메모리 배리어 = REQ-001 위배이므로 불채택): **reaper는 evict 직전 항상 store를 재-flush한다(flush-on-evict).** `ExecWriter.snapshot()`이 *현재* probe 배열을 deep-copy하므로 reaper가 `writes` 변화를 놓쳤어도 evict 시점 flush가 실제 커버리지를 포착한다. 따라서 `writes` staleness는 flush **타이밍**(최대 1 reaper 간격 지연)만 흔들 뿐 커버리지 **유실은 불가능**하다. probe 배열 자체의 JMM 지연으로 직전 순간 probe만 놓치는 것은 inherent best-effort 한계(REQ-003). volatile은 "변화 감지" 신뢰도를 높이나 핫패스 비용이 커서 불채택.
3. **clock 단일 소유 = reaper.** reaper만 `now`를 안다. 각 pass에서 `store.writes()`가 직전 관측치와 다르면 `store.lastActivityMillis(now)`(활동), 같으면 `now - lastActivityMillis() >= idleFlushMillis`면 idle 판정(**inclusive `>=` 경계로 통일**). 새 store **첫 관측**은 idle 판정하지 않고 `lastActivityMillis(now)`로 활동 처리(생성이 idle 임계 이전이어도 첫 pass에서 조기 flush되지 않음). `lastActivityMillis`는 reaper가 쓰고 enforceCap이 읽는다(생성 시 = startedAtMillis).
4. **enforceCap = idle-우선.** min-`lastActivityMillis`(가장 오래 무활동) store를 evict; 동점이면 oldest `startedAtMillis`, 그래도 동점이면 key 사전순(**결정론적 tie-break**). 선택 store가 최근 활동(`now - lastActivityMillis < inFlightGuardMillis`)이면 in-flight로 보고 `evictedInFlightTraces++`. **비-트레이서(testId) 모드**: reaper 미가동 → `lastActivityMillis`가 startedAtMillis에 고정 → idle-우선 == oldest-start, **무회귀**(matrix 비고에 명시). `inFlightGuardMillis=0`(기본 생성자)이면 in-flight 카운팅 비활성 = 현행과 동등.
5. **grace = flush, 늦은쓰기 재flush, evict 시 재flush.** reaper가 idle store를 flush하되 즉시 evict하지 않고 `flushedAt=now`. 다음 pass에서 writes가 늘면(늦은 쓰기) **재-flush**(grace 리셋). grace 경과+무활동이면 **다시 flush한 뒤 evict**(flush-on-evict, 결정 2). (design §6.4 "flush 후 grace 유지, 늦은 쓰기 재flush".)

> **C3a scope 경계(명시):** flush 트리거는 **idle reaper가 기본 경로**다. 기존 control API flush(`POST /__coverage__/test/stop`, traceId를 키로 stop)는 **유지**(즉시 flush 수단으로 계속 동작 — `OtelWeaveE2E`가 이미 사용). design §6.4의 **(b) 루트 scope-close 시점 flush는 C3a 범위 외**(reaper+grace로 REQ-016/017 핵심 수용; scope-close 즉시 flush는 필요 시 후속 옵션). enforceCap의 동기 flush는 **기존 동작**(C3a는 victim 선택만 idle-우선으로 교체)으로, 높은 동시성에서 동기 I/O 지연은 `maxstores`를 peak 동시성+reaper 간격을 흡수하도록 설정해 완화(문서 안내).

---

## File Structure

**신규 (agent 모듈):**
- `agent/src/main/java/io/pjacoco/agent/store/TraceStoreReaper.java` — 주입 clock + `void tick()`. registry의 tracer-mode store를 순회하며 idle flush+grace+evict. 데몬 스케줄링은 Bootstrap.

**수정 (agent 모듈):**
- `agent/src/main/java/io/pjacoco/agent/store/TestStore.java` — `writes`(plain long, record()에서 ++), `writes()` getter; `volatile long lastActivityMillis`(init=startedAtMillis), getter/setter.
- `agent/src/main/java/io/pjacoco/agent/store/TestStoreRegistry.java` — reaper 협조 접근자 `snapshotStores()`(키→store 맵 스냅샷), `flushStore(key)`(flush 후 유지), `evictWithoutFlush(key)`(flush 없이 제거); `enforceCap()`을 idle-우선으로 교체; **9-arg 생성자**(신규 `inFlightGuardMillis`) 추가하고 기존 8-arg가 그것에 위임(`inFlightGuardMillis=0`).
- `agent/src/main/java/io/pjacoco/agent/AgentOptions.java` — `traceReaperIntervalMillis`(기본 10000), `traceIdleFlushMillis`(기본 30000), `traceLateWriteGraceMillis`(기본 10000), `inFlightGuardMillis`(기본 = traceIdleFlushMillis).
- `agent/src/main/java/io/pjacoco/agent/Bootstrap.java` — 트레이서 모드면 `TraceStoreReaper`를 데몬 `ScheduledExecutorService`로 주기 tick; 종료 hook에서 reaper shutdown.
- `agent/src/main/java/io/pjacoco/agent/observability/Metrics.java` — `evictedInFlightTraces` 카운터 + summary.

**테스트:**
- `agent/src/test/java/io/pjacoco/agent/store/TestStoreActivityTest.java`(writes 카운터)
- `agent/src/test/java/io/pjacoco/agent/observability/MetricsTest.java`(기존 — evictedInFlightTraces 케이스 추가)
- `agent/src/test/java/io/pjacoco/agent/store/TraceStoreReaperTest.java`(idle flush + grace)
- `agent/src/test/java/io/pjacoco/agent/store/TestStoreRegistryEvictionTest.java`(idle-우선 eviction)
- `agent/src/integrationTest/java/io/pjacoco/agent/it/TraceStoreLifecycleIT.java`(REQ-016 수용)

**§10 미해결 질문 해소(이 plan에서 확정):** CDC 드레인 타임아웃은 C3b. **idle-reaper 간격(10s)·idle 임계(30s)·grace(10s) 기본값을 본 plan에서 확정**(전부 옵션 오버라이드 가능). 입력 검증·주입 clock으로 결정론적 테스트.

---

## Task 1: Metrics.evictedInFlightTraces 카운터

**REQ-IDs:** REQ-019(잔여)

**Files:**
- Modify: `agent/src/main/java/io/pjacoco/agent/observability/Metrics.java`
- Test: `agent/src/test/java/io/pjacoco/agent/observability/MetricsTest.java`

**Interfaces:**
- Produces: `public final AtomicLong Metrics.evictedInFlightTraces` — enforceCap이 in-flight store를 불가피하게 evict할 때 증가(T5 사용). `summary()`에 `evictedInFlight=` 포함.

- [ ] **Step 1: 실패 테스트 추가** — 기존 `MetricsTest`에:

```java
    @Test
    void evictedInFlightTracesStartsAtZeroAndCounts() {
        Metrics m = new Metrics();
        org.junit.jupiter.api.Assertions.assertEquals(0L, m.evictedInFlightTraces.get());
        m.evictedInFlightTraces.incrementAndGet();
        org.junit.jupiter.api.Assertions.assertTrue(m.summary().contains("evictedInFlight=1"));
    }
```

- [ ] **Step 2: 실패 확인** — `./gradlew :agent:test --tests '*MetricsTest*'` → FAIL.
- [ ] **Step 3: 구현** — `Metrics`에 필드 추가 + `summary()` 끝에 토큰 이어붙이기:

```java
    /** Incremented when the store cap forces eviction of a still-active (in-flight) trace store. */
    public final AtomicLong evictedInFlightTraces = new AtomicLong();
```
```java
                + " evictedInFlight=" + evictedInFlightTraces.get();
```

- [ ] **Step 4: 통과 확인** — PASS.
- [ ] **Step 5: 커밋** — `git commit -m "feat(agent): add evictedInFlightTraces counter (REQ-019)"`

---

## Task 2: TestStore 활동 추적 (clock-free writes + lastActivityMillis)

**REQ-IDs:** REQ-016, REQ-018 (기반)

**Files:**
- Modify: `agent/src/main/java/io/pjacoco/agent/store/TestStore.java`
- Test: `agent/src/test/java/io/pjacoco/agent/store/TestStoreActivityTest.java`

**Interfaces:**
- Produces:
  - `record(...)` 끝에 `writes++`(plain long, clock 없음).
  - `long writes()` — 누적 write 수(monotonic, benign race; "변화 감지"용).
  - `volatile long lastActivityMillis` + `long lastActivityMillis()` + `void lastActivityMillis(long)` — reaper가 set, enforceCap이 get. 생성자에서 `= startedAtMillis`.

- [ ] **Step 1: 실패 테스트 작성**

```java
package io.pjacoco.agent.store;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TestStoreActivityTest {
    @Test
    void writesIncrementOnRecord() {
        TestStore s = new TestStore("k", 1000L, null);
        assertEquals(0L, s.writes());
        s.record(7L, "com/x/A", 0, 2);
        s.record(7L, "com/x/A", 1, 2);
        assertEquals(2L, s.writes(), "each record() bumps the activity counter");
    }
    @Test
    void lastActivityDefaultsToStartAndIsSettable() {
        TestStore s = new TestStore("k", 1000L, null);
        assertEquals(1000L, s.lastActivityMillis(), "defaults to startedAtMillis");
        s.lastActivityMillis(5000L);
        assertEquals(5000L, s.lastActivityMillis());
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :agent:test --tests '*TestStoreActivityTest*'` → FAIL(메서드 없음).
- [ ] **Step 3: 구현** — `TestStore`에:

```java
    private long writes;                                  // plain long: clock-free hot-path activity signal
    private volatile long lastActivityMillis;             // reaper-written, enforceCap-read; init = startedAtMillis
```
생성자 끝에 `this.lastActivityMillis = startedAtMillis;`. `record(...)` 메서드 본문 **끝**(probe 기록 후)에 `writes++;` 한 줄 추가(기존 로직 무변경). 접근자:

```java
    public long writes() { return writes; }
    public long lastActivityMillis() { return lastActivityMillis; }
    public void lastActivityMillis(long millis) { this.lastActivityMillis = millis; }
```

> **REQ-001 주의:** `writes++`는 clock·트레이서·ThreadLocal read·동기화·메모리 배리어를 추가하지 않는다(non-volatile plain long; probe 배열 쓰기와 동일 부류의 benign race). **JMM:** reaper 스레드가 이 증가를 즉시 못 볼 수 있으나, 결과는 idle 감지가 최대 1 reaper 간격 지연되거나 false-idle flush가 발생하는 것뿐이며, **evict 시 항상 재-flush(flush-on-evict, 핵심 설계 #2)** 로 커버리지 유실은 차단된다. volatile로 바꾸면 probe당 배리어 = REQ-001 위배라 불채택(의도적 trade-off). **REQ-001 가드의 한계:** 기존 `HotPathInvariantTest`는 "`recordCoverage`가 `CoverageContext.get()` 1회·`record()` 1회"만 단언한다 — record() 본문에 clock/tracer 추가가 없음은 이 테스트가 아니라 본 제약+리뷰로 보장한다(변경 후 `HotPathInvariantTest` 재실행해 호출 횟수 무회귀 확인).

- [ ] **Step 4: 통과 확인** — `./gradlew :agent:test --tests '*TestStoreActivityTest*' --tests '*HotPathInvariantTest*'` → PASS.
- [ ] **Step 5: 커밋** — `git commit -m "feat(agent): clock-free TestStore activity tracking (writes + lastActivityMillis) (REQ-016)"`

---

## Task 3: TraceStoreReaper (idle flush, 주입 clock)

**REQ-IDs:** REQ-016

**Files:**
- Create: `agent/src/main/java/io/pjacoco/agent/store/TraceStoreReaper.java`
- Modify: `agent/src/main/java/io/pjacoco/agent/store/TestStoreRegistry.java`(reaper 협조 접근자)
- Test: `agent/src/test/java/io/pjacoco/agent/store/TraceStoreReaperTest.java`

**Interfaces:**
- Consumes: `TestStoreRegistry`, `LongSupplier clock`, idle/grace 임계. (**Metrics 미사용 → 생성자에서 제외**; reaper flush는 registry의 기존 flush 경로가 metric 처리.)
- Produces (TestStoreRegistry, 패키지-가시):
  - `synchronized Map<String,TestStore> snapshotStores()` — 현재 store 맵 스냅샷(키=registry 키; reaper가 키로 flushStore/evictWithoutFlush 호출 — 결합을 List 대신 Map 키로 명시).
  - `synchronized void flushStore(String key)` — `TestStore s = stores.get(key); if (s==null) return;`(cap-race no-op) 후 `flush(s, null, "idle")`로 flush하되 **map에 유지**(grace).
  - `synchronized void evictWithoutFlush(String key)` — flush 없이 `stores.remove(key)`.
- Produces (TraceStoreReaper): `TraceStoreReaper(TestStoreRegistry reg, LongSupplier clock, long idleFlushMillis, long graceMillis)` + `void tick()` — 1회 reaper pass(테스트가 결정론적 호출).

**reaper `tick()` 알고리즘(reaper-local 상태 `Map<String,long[]{lastWrites, flushedAtOrMinus1}>`):**
1. `snap = reg.snapshotStores(); now = clock`. **prune:** `local.keySet().retainAll(snap.keySet())`(registry에서 사라진 키의 stale 상태 제거 — 메모리 누수 방지).
2. 각 `(key, store)`에 대해 `w = store.writes()`:
   - **첫 관측**(local에 key 없음): `local[key]={w, -1}`; `store.lastActivityMillis(now)`(활동 처리, 조기 flush 방지); 이번 pass 종료.
   - `w != lastWrites`(활동/늦은 쓰기): `store.lastActivityMillis(now)`; `lastWrites=w`; flushedAt!=-1이면 **즉시 재-flush**(`flushStore(key)`, 늦은 쓰기 포착) 후 flushedAt=-1(grace 리셋).
   - `w == lastWrites` 이고 `now - store.lastActivityMillis() >= idleFlushMillis`:
     - flushedAt==-1: `flushStore(key)`; flushedAt=now (flush 후 유지=grace).
     - flushedAt!=-1 이고 `now - flushedAt >= graceMillis`: **`flushStore(key)`(evict 직전 최종 flush=flush-on-evict) → `evictWithoutFlush(key)`**; local에서 key 제거.
3. 전체 `try/catch(Throwable)` swallow(best-effort, REQ-003) — reaper 데몬은 어떤 store의 오류로도 죽지 않는다.

- [ ] **Step 1: 실패 테스트 작성** — 주입 clock으로 결정론적:

```java
package io.pjacoco.agent.store;

import static org.junit.jupiter.api.Assertions.*;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceStoreReaperTest {

    private static TestStoreRegistry reg(Path dir, AtomicLong clock) {
        return new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(),
                false, 1000, clock::get, /*traceKeyAutoCreate*/ true);
    }

    @Test
    void idleStoreFlushedWithoutJvmExit(@TempDir Path dir) throws Exception {     // REQ-016
        AtomicLong clock = new AtomicLong(1000);
        TestStoreRegistry reg = reg(dir, clock);
        TestStore s = reg.forCoverageKey("T");
        s.record(7L, "com/x/A", 0, 2);                  // some coverage
        TraceStoreReaper reaper = new TraceStoreReaper(reg, clock::get,
                /*idleFlushMillis*/ 30000, /*graceMillis*/ 10000);

        clock.set(1000);  reaper.tick();                // first observation -> active, lastActivity=1000
        assertFalse(Files.exists(dir.resolve("T.exec")), "not idle yet");
        clock.set(1000 + 30000); reaper.tick();         // idle >= threshold -> flush (kept for grace)
        assertTrue(Files.exists(dir.resolve("T.exec")), "idle store flushed without JVM exit");
        assertNotNull(reg.peek("T"), "kept during grace");
    }

    @Test
    void idleStoreEvictedAfterGrace(@TempDir Path dir) throws Exception {
        AtomicLong clock = new AtomicLong(0);
        TestStoreRegistry reg = reg(dir, clock);
        TestStore s = reg.forCoverageKey("T");
        s.record(7L, "com/x/A", 0, 2);
        TraceStoreReaper reaper = new TraceStoreReaper(reg, clock::get, 30000, 10000);
        clock.set(0);     reaper.tick();                 // first observation -> active
        clock.set(30000); reaper.tick();                 // idle -> flush, start grace
        clock.set(30000 + 10000); reaper.tick();         // grace elapsed, no new writes -> reflush + evict
        assertNull(reg.peek("T"), "evicted after grace");
        assertTrue(Files.exists(dir.resolve("T.exec")), "flushed artifact remains on disk");
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :agent:test --tests '*TraceStoreReaperTest*'` → FAIL.
- [ ] **Step 3: 구현 registry 협조 접근자** — `TestStoreRegistry`에 `synchronized java.util.Map<String,TestStore> snapshotStores()`(`new LinkedHashMap<>(stores)` 복사 반환), `synchronized void flushStore(String key)`(`TestStore s = stores.get(key); if (s==null) return; flush(s, null, "idle");` — **map 유지**), `synchronized void evictWithoutFlush(String key)`(`stores.remove(key)`). 기존 private `flush(...)`/`ExecWriter` 재사용(status 문자열 `"idle"` 신규 — ExecWriter는 status를 그대로 JSON에 기록하므로 임의 문자열 허용).
- [ ] **Step 4: 구현 `TraceStoreReaper`** — 위 알고리즘(prune → 첫 관측 → 활동/재flush → idle flush/grace/flush-on-evict). reaper-local 상태 `HashMap<String,long[]>`. 모든 store 처리를 개별 `try/catch(Throwable)` swallow(한 store 오류가 다른 store/데몬을 죽이지 않음, REQ-003).
- [ ] **Step 5: 통과 확인** — `./gradlew :agent:test --tests '*TraceStoreReaperTest*'` → PASS.
- [ ] **Step 6: 커밋** — `git commit -m "feat(agent): TraceStoreReaper idle flush without JVM exit (REQ-016)"`

---

## Task 4: late-write grace (flush 이후 늦은 쓰기 비유실)

**REQ-IDs:** REQ-017

**Files:**
- Test: `agent/src/test/java/io/pjacoco/agent/store/TraceStoreReaperTest.java`(케이스 추가)
- (필요 시) Modify: `TraceStoreReaper.java`

**Interfaces:**
- Consumes/Produces: Task 3의 reaper. 늦은 쓰기(writes 증가)가 grace 중 도착하면 lastActivity 갱신 + flushedAt 리셋 → **재-flush**(최신 커버리지 포함), evict 보류.

- [ ] **Step 1: 실패 테스트 작성**

```java
    @Test
    void lateWriteWithinGraceIsReflushedNotLost(@TempDir Path dir) throws Exception {  // REQ-017
        AtomicLong clock = new AtomicLong(0);
        TestStoreRegistry reg = reg(dir, clock);
        TestStore s = reg.forCoverageKey("T");
        s.record(7L, "com/x/A", 0, 2);                   // probe 0
        TraceStoreReaper reaper = new TraceStoreReaper(reg, clock::get, 30000, 10000);
        clock.set(0);     reaper.tick();                 // first observation -> active
        clock.set(30000); reaper.tick();                 // flush #1 (probe 0 only), grace begins

        s.record(7L, "com/x/A", 1, 2);                   // LATE write: probe 1, after flush, within grace
        clock.set(35000); reaper.tick();                 // sees writes changed -> reflush (probe 0+1), reset grace

        // the on-disk .exec must contain the late probe (reflushed), not just probe 0
        org.jacoco.core.tools.ExecFileLoader l = new org.jacoco.core.tools.ExecFileLoader();
        l.load(dir.resolve("T.exec").toFile());
        boolean[] probes = l.getExecutionDataStore().get(7L).getProbes();
        assertTrue(probes[0] && probes[1], "late write must be reflushed, not lost");
    }
```

- [ ] **Step 2: 실패 확인** — Task 3 reaper가 재-flush를 안 하면 FAIL(probe[1] 누락).
- [ ] **Step 3: 구현 보강** — reaper의 step 2(활동 재개 감지)에서 flushedAt!=-1이면(이미 flush됨) 즉시 재-flush 후 flushedAt=-1(grace 리셋). 늦은 쓰기가 최종 산출물에 반영.
- [ ] **Step 4: 통과 확인** — PASS.
- [ ] **Step 5: 커밋** — `git commit -m "feat(agent): late-write grace reflush in reaper (REQ-017)"`

---

## Task 5: idle-우선 eviction (in-flight 보호 + 관측)

**REQ-IDs:** REQ-018, REQ-019

**Files:**
- Modify: `agent/src/main/java/io/pjacoco/agent/store/TestStoreRegistry.java`(`enforceCap` 교체)
- Test: `agent/src/test/java/io/pjacoco/agent/store/TestStoreRegistryEvictionTest.java`

**Interfaces:**
- Consumes: `TestStore.lastActivityMillis()`(T2), `Metrics.evictedInFlightTraces`(T1), `clock`, `inFlightGuardMillis`.
- Produces: `enforceCap()`이 cap 초과 시 **가장 오래 무활동(min lastActivityMillis)** store를 evict(partial flush). 선택 store가 `now - lastActivityMillis < inFlightGuardMillis`(최근 활동=in-flight)면 `evictedInFlightTraces++`.

> **registry 생성자 변경(호환):** `enforceCap`은 기존 `clock` 필드(이미 보유)로 `now`를 얻고, 신규 `inFlightGuardMillis` 필드만 추가한다. **9-arg 생성자**(8-arg + `long inFlightGuardMillis`)를 추가하고 **기존 8-arg 생성자가 그것에 `inFlightGuardMillis=0`으로 위임**한다. 따라서 기존 15+ 호출처(`TestStoreRegistryTest`, `TraceScopeBridgeTest`, `ControlEndpointTest`, `CoverageControlTest`, `TestStoreRegistryDiscardTest`, `TraceConsumeFailureIT`, `BraveScopeWeaveIT`, `TracerAbsentFallbackIT`, `UnmappedTraceReportIT`, `TraceMapEndpointIT` 등)는 **소스 변경 없이 컴파일**된다 — `inFlightGuardMillis=0`이면 in-flight 카운팅 비활성(현행과 동등). **오직 `Bootstrap.premain`만**(T6) 9-arg로 `options.inFlightGuardMillis()`를 전달하도록 갱신한다. testId(비-트레이서) 모드는 reaper 미가동 → lastActivityMillis가 startedAtMillis 고정 → idle-우선 == oldest-start, 무회귀.

- [ ] **Step 1: 실패 테스트 작성**

```java
package io.pjacoco.agent.store;

import static org.junit.jupiter.api.Assertions.*;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestStoreRegistryEvictionTest {

    @Test
    void idleEvictedBeforeInFlight(@TempDir Path dir) {                          // REQ-018
        AtomicLong clock = new AtomicLong(0);
        Metrics metrics = new Metrics();
        // cap=2, inFlightGuard=5000
        TestStoreRegistry reg = new TestStoreRegistry(dir, new ExecWriter(), metrics, new AgentLog(),
                false, 2, clock::get, true, /*inFlightGuardMillis*/ 5000);
        TestStore a = reg.forCoverageKey("A"); a.lastActivityMillis(0);          // idle (old)
        TestStore b = reg.forCoverageKey("B"); b.lastActivityMillis(100000);     // in-flight (recent)
        clock.set(100000);
        reg.forCoverageKey("C");                                                 // cap exceeded -> evict
        assertNull(reg.peek("A"), "idle A evicted first");
        assertNotNull(reg.peek("B"), "in-flight B protected");
        assertEquals(0L, metrics.evictedInFlightTraces.get(), "no in-flight eviction needed");
    }

    @Test
    void unavoidableInFlightEvictionIsCounted(@TempDir Path dir) {               // REQ-018 + REQ-019
        AtomicLong clock = new AtomicLong(100000);
        Metrics metrics = new Metrics();
        TestStoreRegistry reg = new TestStoreRegistry(dir, new ExecWriter(), metrics, new AgentLog(),
                false, 1, clock::get, true, 5000);
        TestStore a = reg.forCoverageKey("A"); a.lastActivityMillis(99000);      // recent (in-flight)
        reg.forCoverageKey("B");                                                 // cap=1 -> must evict an in-flight
        assertEquals(1, reg.peek("B") != null ? 1 : 0);
        assertTrue(metrics.evictedInFlightTraces.get() >= 1, "forced in-flight eviction observed");
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :agent:test --tests '*TestStoreRegistryEvictionTest*'` → FAIL(현 enforceCap은 startedAtMillis 기준 + 카운터 없음).
- [ ] **Step 3: 구현** — registry 생성자에 `inFlightGuardMillis` 추가(오버로드로 기존 시그니처 호환 유지: 신규 인자 없는 생성자는 `inFlightGuardMillis=0`=비활성/현행과 동등). `enforceCap()`을 min-`lastActivityMillis` 선택으로 교체:

```java
    private void enforceCap() {
        while (stores.size() > maxStores) {
            String victim = null; long oldestActivity = Long.MAX_VALUE; long victimStart = Long.MAX_VALUE;
            for (Map.Entry<String, TestStore> e : stores.entrySet()) {
                long la = e.getValue().lastActivityMillis();
                long st = e.getValue().startedAtMillis();
                // idle-first; deterministic tie-break: older lastActivity, then older start, then key
                if (la < oldestActivity
                        || (la == oldestActivity && st < victimStart)
                        || (la == oldestActivity && st == victimStart && (victim == null || e.getKey().compareTo(victim) < 0))) {
                    oldestActivity = la; victimStart = st; victim = e.getKey();
                }
            }
            if (victim == null) break;
            TestStore s = stores.remove(victim);
            if (inFlightGuardMillis > 0 && clock.getAsLong() - oldestActivity < inFlightGuardMillis) {
                metrics.evictedInFlightTraces.incrementAndGet();
                log.warn("cap", "store cap " + maxStores + " forced in-flight eviction key=" + victim);
            } else {
                log.warn("cap", "store cap " + maxStores + " exceeded; evicting idle key=" + victim + " as partial");
            }
            flush(s, null, "partial");
            metrics.partialDumps.incrementAndGet();
        }
    }
```

- [ ] **Step 4: 통과 확인** — `./gradlew :agent:test --tests '*TestStoreRegistryEvictionTest*'` + 기존 `TestStoreRegistry*Test` 회귀 green(특히 `dumpRemainingAsPartial`/`start` 경로).
- [ ] **Step 5: 커밋** — `git commit -m "feat(agent): idle-first cap eviction protecting in-flight traces (REQ-018, REQ-019)"`

---

## Task 6: Bootstrap reaper 배선 + REQ-016 수용 IT

**REQ-IDs:** REQ-016

**Files:**
- Modify: `agent/src/main/java/io/pjacoco/agent/AgentOptions.java`(4개 옵션)
- Modify: `agent/src/main/java/io/pjacoco/agent/Bootstrap.java`(reaper 데몬 스케줄 + 종료 shutdown)
- Test: `agent/src/integrationTest/java/io/pjacoco/agent/it/TraceStoreLifecycleIT.java`

**Interfaces:**
- Consumes: `TraceStoreReaper`(T3), 옵션. Bootstrap은 `traceKeyAutoCreate`일 때만 `ScheduledExecutorService`(데몬 스레드 팩토리)로 `reaper::tick`을 `traceReaperIntervalMillis` 주기로 스케줄, 종료 hook에서 `shutdownNow()`.
- Produces: `AgentOptions.{traceReaperIntervalMillis()=10000, traceIdleFlushMillis()=30000, traceLateWriteGraceMillis()=10000, inFlightGuardMillis()=traceIdleFlushMillis}`.

- [ ] **Step 1: 실패 IT 작성** — in-process로 registry+reaper를 실제 배선해, JVM 종료 없이 idle store가 `.exec`로 떨어지는지(REQ-016 수용). 데몬 스레드 sleep 대신 reaper를 짧은 간격으로 돌리고 주입 clock으로 idle 도달.

```java
package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.*;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;
import io.pjacoco.agent.store.TraceStoreReaper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** REQ-016: a long-running (never-exiting) tracer-mode service flushes a finished trace's .exec via the
 *  idle reaper, with no JVM shutdown. Driven with an injected clock + explicit ticks for determinism. */
class TraceStoreLifecycleIT {
    @Test
    void idleReaperFlushWithoutJvmExit(@TempDir Path dir) throws Exception {     // matrix-matched name
        AtomicLong clock = new AtomicLong(1_000);
        Metrics metrics = new Metrics();
        TestStoreRegistry reg = new TestStoreRegistry(dir, new ExecWriter(), metrics, new AgentLog(),
                false, 1000, clock::get, true, 30_000);
        TraceStoreReaper reaper = new TraceStoreReaper(reg, clock::get, 30_000, 10_000);

        TestStore s = reg.forCoverageKey("4bf92f");      // a trace handled by the service
        s.record(7L, "com/x/Svc", 0, 2);
        clock.set(1_000);        reaper.tick();          // active
        // the service keeps running (no JVM exit); time passes with the trace finished/idle
        clock.set(1_000 + 30_000); reaper.tick();        // reaper flushes the idle trace
        assertTrue(Files.exists(dir.resolve("4bf92f.exec")),
                "finished trace's .exec is collectable without JVM shutdown (REQ-016)");
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :agent:integrationTest --tests '*TraceStoreLifecycleIT*'`(배선 전이면 FAIL/미존재).
- [ ] **Step 3: 구현 AgentOptions 4개 옵션** — `maxTraceMappings()` 패턴대로 파싱(기본값 위 명시). `inFlightGuardMillis`는 미지정 시 `traceIdleFlushMillis()` 반환.
- [ ] **Step 4: 구현 Bootstrap 배선** — **registry는 기존처럼 premain 상단에서 1회만 생성**(현 42–46행)하되 **9-arg로 `options.inFlightGuardMillis()` 전달**(나머지 배선 `bindRegistry`/shutdown hook/`ServletInboundActivator`/control endpoint는 그대로). 그 다음 **`if (options.traceKeyAutoCreate())` 블록에만** reaper 배선을 추가: `TraceStoreReaper reaper = new TraceStoreReaper(registry, clockSupplier, options.traceIdleFlushMillis(), options.traceLateWriteGraceMillis());` + 데몬 스케줄러를 **익명 `ThreadFactory`**(람다 금지 — bootstrap CL 안전, 기존 Bootstrap의 익명 `Runnable`/`LongSupplier` 패턴과 일치)로 생성:

```java
ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
    new java.util.concurrent.ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "pjacoco-reaper"); t.setDaemon(true); return t;
        }
    });
scheduler.scheduleWithFixedDelay(new Runnable() {
    public void run() { reaper.tick(); }     // tick() swallows Throwable internally
}, interval, interval, java.util.concurrent.TimeUnit.MILLISECONDS);
```

  종료 hook에서 **partial dump 이전에** `scheduler.shutdownNow()`(reaper가 최종 dump와 경쟁하지 않도록 순서: reaper stop → `dumpRemainingAsPartial` → aggregate → endpoint stop → trace-map dump → summary). (reaper *로직*은 T3/T4의 `tick()` 직접호출 테스트로 결정론 검증; *데몬 스케줄/옵션 전달*은 얇은 배선이라 컴파일+회귀로 확인 — premain은 `Instrumentation`을 요구해 단위화가 비현실적, 이 defer를 DoD에 명시.)
- [ ] **Step 5: 통과 확인** — IT PASS + 기존 agent unit/integration 회귀 green(9-arg 위임으로 기존 8-arg 호출처는 무변경 컴파일 — 확인).
- [ ] **Step 6: 커밋** — `git commit -m "feat(agent): wire idle reaper daemon + lifecycle options (REQ-016)"`

---

## Task 7: 문서 동기화 + 매트릭스 갱신 + 전체 regression (DoD)

**REQ-IDs:** REQ-016, REQ-017, REQ-018, REQ-019

**Files:**
- Modify: `docs/superpowers/requirements/2026-06-19-trace-context-per-test-coverage-requirements.md`(REQ-016/017/018 🟢, REQ-019 🟡→🟢, 실제 테스트명 대조, Level 근거)
- Modify: `docs/superpowers/specs/2026-06-19-trace-context-per-test-coverage-design.md`(§6.4: reaper/grace/idle-eviction 구현 확정값 명시; §10 해당 항목 해소)
- Modify: `README.md`(생명주기 옵션 `traceReaperIntervalMillis`/`traceIdleFlushMillis`/`traceLateWriteGraceMillis`/`inFlightGuardMillis` + 상시 가동 서비스의 reaper 동작 설명)

- [ ] **Step 1: 매트릭스 갱신**
  - REQ-016 🔴→🟢 → `TraceStoreLifecycleIT#idleReaperFlushWithoutJvmExit`(매트릭스 기존 행명과 일치) + `TraceStoreReaperTest#{idleStoreFlushedWithoutJvmExit, idleStoreEvictedAfterGrace}` (integration; Level 셀에 "주입 clock 결정론, Docker 다중서비스는 C3b" 근거). 매트릭스 비고에 "기존 control stop(`/test/stop`)은 즉시 flush 수단으로 유지; design §6.4 (b) scope-close flush는 C3a 범위 외" 1줄.
  - REQ-017 🔴→🟢 → `TraceStoreReaperTest#lateWriteWithinGraceIsReflushedNotLost`
  - REQ-018 🔴→🟢 → `TestStoreRegistryEvictionTest#{idleEvictedBeforeInFlight, unavoidableInFlightEvictionIsCounted}`
  - REQ-019 🟡→🟢 → `MetricsTest#{... , unmappedTraceIdsStartsAtZeroAndCounts, evictedInFlightTracesStartsAtZeroAndCounts}`; 비고 "C1+C2+C3a 전 카운터 완료". **🟡→🟢 전환** (모든 신규 카운터 구현됨).
  - C3a 완료 섹션: REQ-016(Must)+017/018(Should)+019(Should) 🟢.
- [ ] **Step 2: design spec §6.4 확정값 반영** — idle reaper(기본 idle 30s/간격 10s), grace(10s), idle-우선 eviction+inFlightGuard, evictedInFlightTraces. §10의 "idle-reaper·grace 기본값" 해소 표시(드레인 타임아웃은 C3b).
- [ ] **Step 3: README** — 생명주기 옵션 4개 + 상시 가동 서비스 reaper 설명(JVM 종료 불필요), late-write grace, idle-우선 eviction 한 단락.
- [ ] **Step 4: 전체 regression** — `./gradlew test`(전 모듈: agent unit+integrationTest + testkit 4 + gradle-plugin) 또는 동등. 가능하면 `./gradlew clean build`. 전부 PASS. skip 항목(OtelWeaveE2E e2eTest는 OTel jar 부재 시 assumeTrue skip = C1 게이트) 명시. **Bootstrap 데몬 스케줄/옵션 전달은 reaper tick() 테스트로 로직 검증 + 컴파일·회귀로 배선 확인(premain은 Instrumentation 요구로 단위화 비현실적)** 임을 DoD에 기록.
- [ ] **Step 5: 커밋** — `git commit -m "docs(c3a): store-lifecycle matrix green + design/README sync (REQ-016..019)"`

---

## C3b로의 연결 (다음 plan)

C3a가 산출한 **JVM 종료 없는 per-traceId `.exec` flush**가 C3b의 입력이다. C3b(별도 plan, GA-2 게이트):
- REQ-015 — `TraceCoverageMerger`(C2 단일 디렉터리)를 **서비스 축 + 중앙 traceId→testId 맵**으로 확장해 서비스 간 병합 리포트.
- REQ-023 — N개 서비스 `.exec` 중앙 **수집 토폴로지(공유 볼륨 baseline)** + **drain-wait 타임아웃**(비동기 Tram/CDC/Kafka 지연 흡수).
- GA-2 spike — OTel javaagent의 Kafka 홉 자동 전파 검증(거짓이면 명시 전파 폴백). Kafka-consumer weave 갭은 C1에서 닫힘(PR #13).
- E2E — legacy-tram(Brave) + tainted-spring(OTel) Docker 다중 서비스(최고 실현가능; 로컬 불가 시 CI 전용 문서화).

---

## Self-Review (작성자 점검 완료)

**1. Spec coverage:** REQ-016(T2 활동추적+T3 reaper+T6 수용IT) · REQ-017(T4 grace) · REQ-018(T5 idle-우선+카운터) · REQ-019 잔여(T1 카운터+T5 증가). 불변 제약 REQ-001(핫패스)은 T2에서 `writes++`만 추가하고 `HotPathInvariantTest` 재실행으로 가드.

**2. Placeholder scan:** 모든 코드 스텝에 완전 코드. reaper 알고리즘은 명시적 상태기계. Bootstrap 배선은 데몬 팩토리·scheduleWithFixedDelay·shutdownNow 구체화. T6 IT는 reaper.tick 직접 호출로 결정론(데몬 sleep 의존 없음).

**3. Type consistency:** `TestStore.{writes():long, lastActivityMillis():long, lastActivityMillis(long):void}`, `TraceStoreReaper(TestStoreRegistry,Metrics,LongSupplier,long,long)`+`tick():void`, `TestStoreRegistry.{snapshotStores():List<TestStore>, flushStore(String):void, evictWithoutFlush(String):void, 생성자(...,long inFlightGuardMillis)}`, `Metrics.evictedInFlightTraces:AtomicLong`, `AgentOptions.{traceReaperIntervalMillis,traceIdleFlushMillis,traceLateWriteGraceMillis,inFlightGuardMillis}():long/int` — task 간 일관.

**4. 핫패스 가드:** 유일한 record() 변경은 `writes++`(clock·트레이서·동기화 없음). clock은 reaper 전용. REQ-001 letter 유지.

---

## 3-벤더 design-doc 리뷰 반영 (2026-06-20)

Claude Sonnet + Gemini 3.5 Flash(High) + Cursor(auto). 판정: Claude·Gemini approved_with_conditions, **Cursor needs_revision(Critical: `writes` JMM)**. 작성자 판정(수용/기각):

**수용해 반영:**
- **`writes` JMM 가시성(Cursor I1 Critical / Claude I4):** volatile(=probe당 배리어, REQ-001 위배) 대신 **flush-on-evict**로 유실 차단 — evict 직전 항상 재flush(snapshot이 현재 probe 배열을 읽음). `writes` staleness는 flush 타이밍만 영향, 유실 불가. JMM 노트를 핵심 설계 #2·Task 2에 명시.
- **flushStore cap-race NPE(Claude I2/Gemini I1 일부):** `flushStore`에 `if (s==null) return;` null-guard.
- **reaper-local 맵 누수(Gemini I1/Cursor I3):** 매 tick `keySet().retainAll(snap.keySet())` prune.
- **Bootstrap registry 1회 생성(Cursor I2):** registry는 premain 상단 1회(9-arg), tracer 블록엔 reaper만. 람다 대신 **익명 ThreadFactory**(Gemini I3, bootstrap CL 안전).
- **생성자 호출처(Claude I3):** 9-arg + 8-arg 위임(inFlightGuard=0) → 기존 15+ 호출처 무변경, Bootstrap.premain만 갱신(enumerate).
- **첫 관측 조기 flush(Gemini I5):** 첫 관측은 idle 판정 않고 lastActivity=now.
- **reaper Metrics 미사용(Gemini I4):** 생성자에서 제거.
- **테스트명 불일치(Claude I1):** `idleReaperFlushWithoutJvmExit`(매트릭스 행명)로 통일.
- **`>=` 경계(Cursor I8), tie-break(Cursor I11):** inclusive `>=` 통일, enforceCap 동점 = oldest start→key.
- **snapshotStores 결합 명시(Claude I5/Cursor I9):** `List` → `Map<String,TestStore>`(키=registry 키).
- **scope 명시(Cursor I5/I6/I7):** idle-우선 비-트레이서 무회귀 비고, control-stop flush 유지, scope-close flush(§6.4 b) C3a 범위 외.
- **enforceCap 동기 I/O(Gemini I2):** 기존 동작; maxstores 설정 완화 안내.
- **전모듈 회귀(Gemini I6):** `./gradlew test`로 확대.
- **TOCTOU sub-tick(Claude I6):** flush-on-evict로 손실 최소화 — 문서화.
- **Bootstrap 배선 테스트(Cursor I4/Claude):** reaper 로직은 tick() 테스트, 데몬 스케줄은 컴파일+회귀(premain Instrumentation 요구) — defer를 DoD 명시.

**기각:** 없음(전 findings 타당, 전부 반영 또는 명시적 문서화).

재리뷰: flush-on-evict는 reaper evict 경로의 보강(구조 동일)이며 task 경계 불변 → 부분 재리뷰 불요. design §6.4 동기화는 T7에서 변경분만 design-doc 리뷰 재실행.
