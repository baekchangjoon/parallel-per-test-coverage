# Trace Context per-test Coverage — C2 Implementation Plan (traceId ↔ testId 매핑)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **출처:** design spec `docs/superpowers/specs/2026-06-19-trace-context-per-test-coverage-design.md`, 요구사항명세 `docs/superpowers/requirements/2026-06-19-trace-context-per-test-coverage-requirements.md`.
> **분해:** 이 plan은 **C2(traceId ↔ testId 매핑)** 만 다룬다. C1(in-process 소비 + async)은 완료(매트릭스 12/12 🟢, PR #13 머지). C3(분산 집계)는 별도 plan(`-c3.md`)으로, C2 green 이후 작성한다. C3의 출발점은 본 C2가 산출하는 단일-서비스 `TraceCoverageMerger`를 **서비스 축 + drain-wait + 중앙 수집**으로 확장하는 것이다(§아래 "C3로의 연결").

**Goal:** 트레이서 모드에서 raw traceId 슬롯으로 기록된 per-trace 커버리지를, 러너가 등록한 `traceId→testId` 맵으로 사람이 읽는 testId 리포트로 변환·병합한다(미등록이면 raw traceId 그대로).

**Architecture:** 핫패스·store 구조·C1 경로는 전부 무변경. 신규 `TestIdMappingRegistry`(bounded `traceId→testId` 맵, FQCN#method 정규화)를 control endpoint `POST /__coverage__/trace/map`로 채우고 JVM 종료 시 `trace-map.properties`로 덤프한다. 신규 `TraceCoverageMerger`가 출력 디렉터리의 per-key `.exec`를 그 맵으로 그룹핑(미등록→raw)·N:1 병합해 `<testId>.exec`를 산출한다. 매핑·병합은 전부 **핫패스 밖(리포트 시점)** 이다(design §5.3).

**Tech Stack:** Java 8, `org.jacoco.core` 0.8.12(`ExecFileLoader`/`ExecutionDataStore`/`ExecutionDataWriter` — 에이전트 jar에서 `io.pjacoco.shaded.jacoco`로 relocate), `com.sun.net.httpserver`(기존 control endpoint), `java.util.Properties`(맵 영속화, 의존성 0), JUnit5(agent 단위/통합), Gradle.

## Global Constraints

- 핫패스 `CoverageBridge.recordCoverage`는 `CoverageContext`(ThreadLocal) **1-read** 유지 — 매핑/병합은 핫패스에 어떤 코드도 추가하지 않는다 (REQ-001, design §3-1).
- 트레이서 **런타임 하드 의존 0**, Java 8 호환. C2 신규 코드(`mapping`·merger·endpoint)는 **순수 Java**로 트레이서 API를 전혀 보지 않는다 — reflection도 불필요 (REQ-002).
- 모든 신규 경로 best-effort: `catch (Throwable)` swallow, SUT/러너로 throw 금지 (REQ-003).
- **store 구조·JSON 스키마·testId 슬롯 무변경.** 트레이서 모드는 `testId` 슬롯에 raw traceId를 그대로 둔다(의도된 중간 산출물). display 매핑은 **병합/리포트 시점에만** (design §5.3).
- 매핑 저장소는 **bounded**(LRU 상한) — traceId 카디널리티가 높아 장기 실행 서비스 OOM 방지 (REQ-011).
- 정규 testId 형식 = **`FQCN#method`** (REQ-014).

---

## 운영 계약 — merge는 누가·언제 실행하나 (중요)

C2의 매핑·병합은 **에이전트 핫패스/종료 hook이 아니라 러너(또는 CI post-step)가 실행하는 오프라인 단계**다 — 이것이 C3의 중앙 병합 모델과 동일하다(design §5.2-3 "C3에선 per-service 등록 불필요, 러너가 중앙에 한 번 보고").

- **에이전트(SUT)가 하는 일:** per-trace `<traceId>.exec` 산출(C1 경로) + 종료 시 `trace-map.properties` 덤프(control endpoint로 등록된 매핑). **merge는 하지 않는다.**
- **러너/CI가 하는 일:** SUT 종료 후 `TraceCoverageMerger.merge(outDir, TestIdMappingRegistry.loadFrom(outDir/trace-map.properties), reportDir, metrics)`를 호출해 `reportDir/<testId>.exec`를 산출. 단일 서비스(C2)에서는 outDir 1개, C3에서는 N개 서비스 outDir + 중앙 맵.
- **in-place 금지:** `reportDir`는 입력 `outDir`와 **다른 디렉터리**여야 한다(덮어쓰기·재귀 방지). merger는 입력에서 `aggregate.exec`(`AgentOptions.aggregateFile()` 기본값)와 비정규 파일을 제외한다(Task 4).
- 따라서 Bootstrap은 mapping registry 생성·endpoint 주입·`trace-map.properties` 덤프까지만 배선한다(merger 호출 배선 없음 — Task 5 Interfaces 참조).

> **design drift 주의(implementer):** sibling design spec §5.2-4는 아직 `TraceCoverageMerger`를 "신규, C3"로 기술한다. 본 plan은 그 **단일-서비스 시드를 C2에서 도입**한다(REQ-011/012/013의 "리포트/병합" 수용을 단일 서비스에서 검증하기 위해 필수). 이는 새 요구가 아니라 §10 미해결 질문("출력 포맷·merge 적용 가부")의 해소이며, Task 8에서 design §5.2-4/§10을 동기화한다. 구현 착수 전 design §5.2-4·§5.3·§10을 본 plan과 함께 읽어 phase 귀속 혼동을 피하라.

---

## 범위(REQ 매핑)와 완료 정의

| REQ-ID | 우선순위 | 내용 | 산출 Task |
|--------|----------|------|-----------|
| REQ-011 | Must | control endpoint 매핑 등록(bounded) | T3, T5 |
| REQ-012 | Must | 미등록 traceId → raw traceId를 testId로 | T4, T6 |
| REQ-013 | Should | N:1 — 한 testId의 여러 traceId 병합 | T4 |
| REQ-014 | Should | testId를 FQCN#method로 정규화 | T1, T7 |
| REQ-019 | Should(부분) | 관측성 카운터 `unmappedTraceIds` 추가(C2 몫) | T2, T4 |

**C2 완료 정의(DoD):** REQ-011·012(Must) + REQ-013·014(Should) 매트릭스 🟢, 각 green REQ가 실제 통과 테스트와 대응(테스트명 대조), REQ-019는 `unmappedTraceIds` 추가로 추가 전진(여전히 🟡 — `evictedInFlightTraces`는 C3), 전체 regression(unit+integration+기존 E2E) green, 영향 문서 갱신.

**더블 루프:** 바깥 루프(수용) = REQ-011 `TraceMapEndpointIT`(T5) + REQ-012 `UnmappedTraceReportIT`(T6). 안쪽 루프 = T1~T4 단위 TDD. T5/T6의 수용 테스트는 의존 task가 green이 되기 전엔 실패(red)하는 것이 정상이며, 약화·주석처리 금지.

---

## File Structure

**신규 (agent 모듈):**
- `agent/src/main/java/io/pjacoco/agent/mapping/TraceMapping.java` — 조회 인터페이스 `String testIdFor(String traceId)`(미등록이면 null). 병합기와 registry의 결합 지점.
- `agent/src/main/java/io/pjacoco/agent/mapping/TestIdNormalizer.java` — `static String normalize(String)`. 정규 `FQCN#method` 형식화(공백/구분자), 패키지 날조 없음.
- `agent/src/main/java/io/pjacoco/agent/mapping/TestIdMappingRegistry.java` — bounded(LRU) `traceId→testId` 맵, `TraceMapping` 구현. 등록 시 정규화. `trace-map.properties` 덤프/로드.
- `agent/src/main/java/io/pjacoco/agent/output/TraceCoverageMerger.java` — 출력 디렉터리의 `*.exec`를 `TraceMapping`으로 그룹핑·N:1 병합 → `<testId>.exec` 산출. **C3가 서비스 축으로 확장할 단일-서비스 시드**(design §5.2-4).

**수정 (agent 모듈):**
- `agent/src/main/java/io/pjacoco/agent/control/ControlEndpoint.java` — `/__coverage__/trace/map?traceId=&testId=` 라우트 추가, 생성자에 `TestIdMappingRegistry` 인자 추가.
- `agent/src/main/java/io/pjacoco/agent/Bootstrap.java` — mapping registry 생성·endpoint 주입, 종료 hook에서 `trace-map.properties` 덤프.
- `agent/src/main/java/io/pjacoco/agent/AgentOptions.java` — `maxTraceMappings()`(기본 100000) 추가.
- `agent/src/main/java/io/pjacoco/agent/observability/Metrics.java` — `unmappedTraceIds` 카운터 + summary 반영.

**수정 (testkit 모듈, REQ-014 어댑터 정렬):**
- `testkit-junit5/src/main/java/io/pjacoco/testkit/junit5/PjacocoExtension.java` — `getSimpleName()` → `getName()`.
- `testkit-junit4/src/main/java/io/pjacoco/testkit/junit4/PjacocoRule.java` — `getSimpleName()` → `getName()`.

**테스트:**
- `agent/src/test/java/io/pjacoco/agent/mapping/TestIdNormalizationTest.java`
- `agent/src/test/java/io/pjacoco/agent/mapping/TestIdMappingRegistryTest.java`
- `agent/src/test/java/io/pjacoco/agent/observability/MetricsTest.java`(기존 — 케이스 추가)
- `agent/src/test/java/io/pjacoco/agent/output/TraceCoverageMergerTest.java`
- `agent/src/integrationTest/java/io/pjacoco/agent/it/TraceMapEndpointIT.java`
- `agent/src/integrationTest/java/io/pjacoco/agent/it/UnmappedTraceReportIT.java`
- `testkit-junit5/src/test/.../PjacocoExtensionTest.java`(없으면 신설) / `testkit-junit4/src/test/.../PjacocoRuleTest.java`(있으면 갱신)

**§10 미해결 질문 해소(이 plan에서 확정):**
- registry 키 일반화·`traceKeyAutoCreate` 플래그명 → C1에서 이미 확정(`forCoverageKey`, `traceKeyAutoCreate`).
- `TraceCoverageMerger` 출력 포맷·`ExecutionDataStore.merge()` 적용 가부 → **`ExecFileLoader`가 동일 classId 프로브 배열을 OR-merge**(JaCoCo 표준 병합)로 확정(T4). 서비스 축 표현은 C3로 이월.
- 매핑 등록 어댑터 측 API → C2 기본 경로는 **control endpoint**(러너가 traceId 확보 후 등록). testkit 어댑터의 traceId 회수 헬퍼는 C3에서(러너 중앙 보고 모델).

---

## Task 1: TestIdNormalizer (정규 FQCN#method 형식화)

**REQ-IDs:** REQ-014

**Files:**
- Create: `agent/src/main/java/io/pjacoco/agent/mapping/TestIdNormalizer.java`
- Test: `agent/src/test/java/io/pjacoco/agent/mapping/TestIdNormalizationTest.java`

**Interfaces:**
- Produces: `public static String TestIdNormalizer.normalize(String raw)` — null/blank → null; `'#'` 기준으로 class/method를 trim 후 `class#method`로 재조합; `'#'` 없으면 trim한 입력 그대로(패키지/구분자 날조 안 함).

- [ ] **Step 1: 실패 테스트 작성**

```java
package io.pjacoco.agent.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class TestIdNormalizationTest {
    @Test
    void toFqcnHashMethod() {
        assertEquals("com.x.T#m", TestIdNormalizer.normalize("com.x.T#m"));
        assertEquals("com.x.T#m", TestIdNormalizer.normalize("  com.x.T # m  "));
        assertEquals("com.x.T", TestIdNormalizer.normalize("com.x.T"));     // no method separator: kept
        assertEquals("com.x.T", TestIdNormalizer.normalize("com.x.T#"));    // empty method dropped
        assertNull(TestIdNormalizer.normalize(null));
        assertNull(TestIdNormalizer.normalize("   "));
        assertNull(TestIdNormalizer.normalize("#m"));                       // empty class -> null
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :agent:test --tests '*TestIdNormalizationTest*'` → FAIL(클래스 없음).

- [ ] **Step 3: 구현**

```java
package io.pjacoco.agent.mapping;

/**
 * Canonicalizes a testId to {@code FQCN#method} shape: trims, and normalizes the single {@code '#'}
 * separator with trimmed class/method segments. It does NOT fabricate a package (a simple class name
 * stays a simple class name) — the canonical FQCN must be supplied by the registrant (see REQ-014 and
 * the testkit adapter alignment in Task 7).
 */
public final class TestIdNormalizer {
    private TestIdNormalizer() {}

    /** @return canonical {@code class#method}, or null for null/blank/empty-class input. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        int hash = s.indexOf('#');
        if (hash < 0) return s;
        String cls = s.substring(0, hash).trim();
        String method = s.substring(hash + 1).trim();
        if (cls.isEmpty()) return null;
        return method.isEmpty() ? cls : cls + "#" + method;
    }
}
```

- [ ] **Step 4: 통과 확인** — `./gradlew :agent:test --tests '*TestIdNormalizationTest*'` → PASS.
- [ ] **Step 5: 커밋**

```bash
git add agent/src/main/java/io/pjacoco/agent/mapping/TestIdNormalizer.java \
        agent/src/test/java/io/pjacoco/agent/mapping/TestIdNormalizationTest.java
git commit -m "feat(agent): TestIdNormalizer canonicalizes testId to FQCN#method (REQ-014)"
```

---

## Task 2: Metrics.unmappedTraceIds 카운터

**REQ-IDs:** REQ-019(부분 — C2 몫)

**Files:**
- Modify: `agent/src/main/java/io/pjacoco/agent/observability/Metrics.java`
- Test: `agent/src/test/java/io/pjacoco/agent/observability/MetricsTest.java`(기존 파일에 케이스 추가)

**Interfaces:**
- Produces: `public final AtomicLong Metrics.unmappedTraceIds` — 병합 시 미등록 traceId를 raw로 폴백할 때 증가(T4가 사용). `summary()`에 `unmapped=` 포함.

- [ ] **Step 1: 실패 테스트 추가** — 기존 `MetricsTest`에:

```java
    @Test
    void unmappedTraceIdsStartsAtZeroAndCounts() {
        Metrics m = new Metrics();
        org.junit.jupiter.api.Assertions.assertEquals(0L, m.unmappedTraceIds.get());
        m.unmappedTraceIds.incrementAndGet();
        org.junit.jupiter.api.Assertions.assertTrue(m.summary().contains("unmapped=1"));
    }
```

- [ ] **Step 2: 실패 확인** — `./gradlew :agent:test --tests '*MetricsTest*'` → FAIL(필드/summary 토큰 없음).

- [ ] **Step 3: 구현** — `Metrics`에 필드와 summary 토큰 추가:

```java
    /** Incremented when a per-trace store is reported without a registered traceId->testId mapping
     *  (the raw traceId is used as the testId). */
    public final AtomicLong unmappedTraceIds = new AtomicLong();
```

그리고 `summary()`의 반환 문자열 끝에 추가:

```java
                + " unmapped=" + unmappedTraceIds.get();
```

(주: `summary()`의 마지막 `+` 표현식(`fallbackActivations`)에 위 토큰을 이어 붙인다.)

- [ ] **Step 4: 통과 확인** — `./gradlew :agent:test --tests '*MetricsTest*'` → PASS.
- [ ] **Step 5: 커밋**

```bash
git add agent/src/main/java/io/pjacoco/agent/observability/Metrics.java \
        agent/src/test/java/io/pjacoco/agent/observability/MetricsTest.java
git commit -m "feat(agent): add unmappedTraceIds counter (REQ-019 partial)"
```

---

## Task 3: TraceMapping + TestIdMappingRegistry (등록·조회·bounded·영속화)

**REQ-IDs:** REQ-011, REQ-012(조회 폴백 null 계약), REQ-014(등록 시 정규화)

**Files:**
- Create: `agent/src/main/java/io/pjacoco/agent/mapping/TraceMapping.java`
- Create: `agent/src/main/java/io/pjacoco/agent/mapping/TestIdMappingRegistry.java`
- Test: `agent/src/test/java/io/pjacoco/agent/mapping/TestIdMappingRegistryTest.java`

**Interfaces:**
- Consumes: `TestIdNormalizer.normalize` (T1).
- Produces:
  - `interface TraceMapping { String testIdFor(String traceId); }` — **미등록이면 null**(raw 폴백 판단은 호출자/merger 몫, REQ-012).
  - `class TestIdMappingRegistry implements TraceMapping`:
    - `TestIdMappingRegistry(int maxEntries)`
    - `void register(String traceId, String testId)` — 정규화 후 bounded 저장. traceId/정규화 결과 null이면 no-op.
    - `String testIdFor(String traceId)` — 매핑된 정규 testId 또는 null.
    - `int size()`
    - `void writeTo(java.nio.file.Path file)` — `trace-map.properties` 덤프(best-effort).
    - `static TraceMapping loadFrom(java.nio.file.Path file)` — 파일에서 불변 `TraceMapping` 로드(없으면 항상-null 매핑).

- [ ] **Step 1: 실패 테스트 작성**

```java
package io.pjacoco.agent.mapping;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestIdMappingRegistryTest {

    @Test
    void registeredLookupReturnsNormalizedTestId() {                 // REQ-011 + REQ-014
        TestIdMappingRegistry r = new TestIdMappingRegistry(100);
        r.register("4bf92f", "  com.x.T # m ");
        assertEquals("com.x.T#m", r.testIdFor("4bf92f"));
    }

    @Test
    void unregisteredLookupReturnsNull() {                           // REQ-012 contract
        assertNull(new TestIdMappingRegistry(100).testIdFor("nope"));
    }

    @Test
    void blankTestIdIsNotRegistered() {
        TestIdMappingRegistry r = new TestIdMappingRegistry(100);
        r.register("t", "   ");
        assertNull(r.testIdFor("t"));
    }

    @Test
    void boundedEvictionDropsEldest() {                             // REQ-011 bounded
        TestIdMappingRegistry r = new TestIdMappingRegistry(2);
        r.register("t1", "com.x.T#a");
        r.register("t2", "com.x.T#b");
        r.register("t3", "com.x.T#c");                              // evicts t1 (eldest)
        assertEquals(2, r.size());
        assertNull(r.testIdFor("t1"));
        assertEquals("com.x.T#c", r.testIdFor("t3"));
    }

    @Test
    void writeAndLoadRoundTrips(@TempDir Path dir) throws Exception {
        TestIdMappingRegistry r = new TestIdMappingRegistry(100);
        r.register("4bf92f", "com.x.T#m");
        Path f = dir.resolve("trace-map.properties");
        r.writeTo(f);
        TraceMapping loaded = TestIdMappingRegistry.loadFrom(f);
        assertEquals("com.x.T#m", loaded.testIdFor("4bf92f"));
        assertNull(loaded.testIdFor("absent"));
    }

    @Test
    void loadFromMissingFileYieldsAlwaysNullMapping(@TempDir Path dir) throws Exception {
        TraceMapping loaded = TestIdMappingRegistry.loadFrom(dir.resolve("nope.properties"));
        assertNull(loaded.testIdFor("anything"));
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :agent:test --tests '*TestIdMappingRegistryTest*'` → FAIL(클래스 없음).

- [ ] **Step 3: 구현 — `TraceMapping`**

```java
package io.pjacoco.agent.mapping;

/** Resolves a coverage key (traceId) to a human-readable testId. Returns {@code null} when the key has
 *  no registered mapping — callers fall back to the raw key (REQ-012). */
public interface TraceMapping {
    String testIdFor(String traceId);
}
```

- [ ] **Step 4: 구현 — `TestIdMappingRegistry`**

```java
package io.pjacoco.agent.mapping;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Bounded {@code traceId -> testId} map for the report/merge layer (REQ-011). LRU by access order with a
 * hard cap so a high-cardinality, long-running service cannot OOM. testIds are canonicalized on register
 * (REQ-014). Pure Java — no tracer dependency, no hot-path involvement (display mapping is report-time
 * only, design §5.3).
 */
public final class TestIdMappingRegistry implements TraceMapping {

    private final int maxEntries;
    private final Map<String, String> map;

    public TestIdMappingRegistry(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
        this.map = Collections.synchronizedMap(new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > TestIdMappingRegistry.this.maxEntries;
            }
        });
    }

    /** Register a mapping. No-op when {@code traceId} is null or {@code testId} normalizes to null. */
    public void register(String traceId, String testId) {
        try {
            if (traceId == null) return;
            String t = TestIdNormalizer.normalize(testId);
            if (t == null) return;
            map.put(traceId, t);
        } catch (Throwable ignored) {
            // best-effort: never disturb the control plane (REQ-003)
        }
    }

    @Override public String testIdFor(String traceId) {
        return traceId == null ? null : map.get(traceId);
    }

    public int size() { return map.size(); }

    /** Best-effort dump to a {@code key=value} properties file for offline/central merge (C3 seed). */
    public void writeTo(Path file) {
        try {
            Properties p = new Properties();
            synchronized (map) {
                for (Map.Entry<String, String> e : map.entrySet()) p.setProperty(e.getKey(), e.getValue());
            }
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            try (OutputStream os = Files.newOutputStream(file)) {
                p.store(os, "pjacoco traceId->testId map");
            }
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    /** Load an immutable mapping from a properties file. Missing/unreadable file -> always-null mapping. */
    public static TraceMapping loadFrom(Path file) {
        final Properties p = new Properties();
        try {
            if (Files.exists(file)) {
                try (InputStream is = Files.newInputStream(file)) { p.load(is); }
            }
        } catch (Throwable ignored) {
            // fall through to an empty (always-null) mapping
        }
        return new TraceMapping() {
            @Override public String testIdFor(String traceId) {
                return traceId == null ? null : p.getProperty(traceId);
            }
        };
    }
}
```

- [ ] **Step 5: 통과 확인** — `./gradlew :agent:test --tests '*TestIdMappingRegistryTest*'` → PASS.
- [ ] **Step 6: 커밋**

```bash
git add agent/src/main/java/io/pjacoco/agent/mapping/TraceMapping.java \
        agent/src/main/java/io/pjacoco/agent/mapping/TestIdMappingRegistry.java \
        agent/src/test/java/io/pjacoco/agent/mapping/TestIdMappingRegistryTest.java
git commit -m "feat(agent): bounded TestIdMappingRegistry + TraceMapping (REQ-011, REQ-012, REQ-014)"
```

---

## Task 4: TraceCoverageMerger (N:1 병합 + 미등록 raw 폴백)

**REQ-IDs:** REQ-013, REQ-012, REQ-019(부분)

**Files:**
- Create: `agent/src/main/java/io/pjacoco/agent/output/TraceCoverageMerger.java`
- Test: `agent/src/test/java/io/pjacoco/agent/output/TraceCoverageMergerTest.java`

**Interfaces:**
- Consumes: `TraceMapping`(T3), `Metrics`(T2). JaCoCo `org.jacoco.core.tools.ExecFileLoader`, `org.jacoco.core.data.{ExecutionDataStore,ExecutionDataWriter,ExecutionData,SessionInfo,SessionInfoStore}` (에이전트 jar에서 relocate되나 `.exec` 포맷은 vanilla라 상호운용).
- Produces: `void TraceCoverageMerger.merge(Path inputDir, TraceMapping mapping, Path outputDir, Metrics metrics)` — `inputDir`의 `*.exec` **정규 파일**(서브디렉터리·`aggregate.exec` 제외)을 파일 stem(=key)으로 읽어 `mapping.testIdFor(key)`(null이면 raw key + `unmappedTraceIds++`)로 그룹핑, 동일 testId의 `.exec`를 `ExecFileLoader`로 OR-merge, `outputDir/<testId>.exec`로 출력.
- 상수: `static final String AGGREGATE_EXEC = "aggregate.exec";` — `AgentOptions.aggregateFile()` 기본값과 동일(whole-run 덤프는 per-key 산출물이 아니므로 입력에서 제외; 운영 계약 참조).

> **병합 정확성 근거(0.8.12 검증):** JaCoCo `ExecutionDataStore.put(ExecutionData)`는 동일 classId가 이미 있으면 내부적으로 `ExecutionData.merge(other)`(프로브 `boolean[]` OR)를 호출한다(`ExecutionDataStore`에 public `merge()` 메서드는 없음 — 병합은 `put` 경로). `ExecFileLoader.load(File)`는 읽은 레코드를 그 store에 `put`으로 전달하므로, **같은 `ExecFileLoader` 인스턴스에 여러 `.exec`를 순차 load**하면 자동 OR-merge된다(REQ-013). `org.jacoco:org.jacoco.core:0.8.12` 번들에 `ExecFileLoader`/`ExecutionDataStore` 포함 확인됨.

> **C2 non-goal:** `ExecWriter`는 `<testId>.exec`와 `<testId>.json` sidecar를 쌍으로 쓰지만, C2 merger는 **`.exec`만 병합**한다. 병합 testId의 JSON sidecar(서비스 축·메타데이터 포함)는 C3의 서비스-축 리포트에서 다룬다 — C2에서 명시적 non-goal.

- [ ] **Step 1: 실패 테스트 작성** — 헬퍼로 vanilla `.exec`를 만들고(같은 classId, 다른 probe), 병합 결과를 `ExecFileLoader`로 다시 읽어 검증.

```java
package io.pjacoco.agent.output;

import static org.junit.jupiter.api.Assertions.*;
import io.pjacoco.agent.mapping.TraceMapping;
import io.pjacoco.agent.observability.Metrics;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.tools.ExecFileLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceCoverageMergerTest {

    /** Write a single-class .exec named {@code key.exec} covering exactly {@code probeIdx} of 2 probes. */
    private static void writeExec(Path dir, String key, long classId, String className, int probeIdx) throws Exception {
        Files.createDirectories(dir);
        boolean[] probes = new boolean[] { false, false };
        probes[probeIdx] = true;
        Path exec = dir.resolve(key + ".exec");
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(exec))) {
            ExecutionDataWriter w = new ExecutionDataWriter(os);
            w.visitSessionInfo(new SessionInfo(key, 0L, 0L));
            w.visitClassExecution(new ExecutionData(classId, className, probes));
        }
    }

    private static boolean[] probesFor(Path execFile, long classId) throws Exception {
        ExecFileLoader l = new ExecFileLoader();
        l.load(execFile.toFile());
        ExecutionData d = l.getExecutionDataStore().get(classId);
        return d == null ? null : d.getProbes();
    }

    @Test
    void multipleTraceIdsOneTestId(@TempDir Path tmp) throws Exception {       // REQ-013
        Path in = tmp.resolve("in"), out = tmp.resolve("out");
        writeExec(in, "T1", 42L, "com/x/Svc", 0);
        writeExec(in, "T2", 42L, "com/x/Svc", 1);
        TraceMapping map = traceId -> "com.x.Svc#it";                          // both map to one testId
        new TraceCoverageMerger().merge(in, map, out, new Metrics());

        Path merged = out.resolve("com.x.Svc#it.exec");
        assertTrue(Files.exists(merged));
        boolean[] p = probesFor(merged, 42L);
        assertNotNull(p);
        assertTrue(p[0] && p[1], "both traceIds' probes must be OR-merged");    // union
    }

    @Test
    void unmappedTraceFallsBackToRawAndCounts(@TempDir Path tmp) throws Exception {  // REQ-012 + REQ-019
        Path in = tmp.resolve("in"), out = tmp.resolve("out");
        writeExec(in, "rawTrace123", 7L, "com/x/Other", 0);
        Metrics metrics = new Metrics();
        TraceMapping empty = traceId -> null;                                  // nothing registered
        new TraceCoverageMerger().merge(in, empty, out, metrics);

        assertTrue(Files.exists(out.resolve("rawTrace123.exec")), "raw traceId used as testId");
        assertEquals(1L, metrics.unmappedTraceIds.get());
    }

    @Test
    void emptyInputDirIsNoOp(@TempDir Path tmp) throws Exception {
        Path in = tmp.resolve("in"), out = tmp.resolve("out");
        Files.createDirectories(in);
        new TraceCoverageMerger().merge(in, traceId -> null, out, new Metrics());
        // no throw; out may be created empty
        assertTrue(!Files.exists(out) || Files.list(out).count() == 0);
    }

    @Test
    void aggregateExecAndDirsAreExcluded(@TempDir Path tmp) throws Exception {   // operational contract
        Path in = tmp.resolve("in"), out = tmp.resolve("out");
        writeExec(in, "T1", 9L, "com/x/Svc", 0);
        writeExec(in, "aggregate", 9L, "com/x/Svc", 1);     // whole-run dump -> aggregate.exec, must be skipped
        Files.createDirectories(in.resolve("nested.exec"));  // a directory ending in .exec, must be skipped
        Metrics metrics = new Metrics();
        new TraceCoverageMerger().merge(in, traceId -> null, out, metrics);

        assertTrue(Files.exists(out.resolve("T1.exec")));
        assertTrue(!Files.exists(out.resolve("aggregate.exec")), "aggregate.exec must not be merged");
        assertEquals(1L, metrics.unmappedTraceIds.get(), "only T1 counted, not aggregate/dir");
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :agent:test --tests '*TraceCoverageMergerTest*'` → FAIL(클래스 없음).

- [ ] **Step 3: 구현**

```java
package io.pjacoco.agent.output;

import io.pjacoco.agent.mapping.TraceMapping;
import io.pjacoco.agent.observability.Metrics;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.tools.ExecFileLoader;

/**
 * Report-time merge: groups an output directory's per-key {@code <key>.exec} snapshots by their resolved
 * testId ({@link TraceMapping#testIdFor}; unmapped keys fall back to the raw key, REQ-012) and OR-merges
 * each group into a single {@code <testId>.exec} (REQ-013). Authored against {@code org.jacoco.*} exactly
 * like {@link ExecWriter}/{@link AggregateWriter} so the shadow plugin relocates it consistently; the
 * {@code .exec} format is vanilla JaCoCo, so a merge over agent-produced files is interoperable.
 *
 * <p>This is the single-service seed of the cross-service {@code TraceCoverageMerger} (design §5.2-4); C3
 * extends it with a service axis, drain-wait, and central collection.
 */
public final class TraceCoverageMerger {

    /** Whole-run aggregate dump (AgentOptions.aggregateFile() default) — not a per-key snapshot, excluded. */
    static final String AGGREGATE_EXEC = "aggregate.exec";

    /** @param mapping nullable; null behaves as an always-unmapped mapping (everything keyed by raw key). */
    public void merge(Path inputDir, TraceMapping mapping, Path outputDir, Metrics metrics) throws Exception {
        if (!Files.isDirectory(inputDir)) return;
        Map<String, ExecFileLoader> byTestId = new LinkedHashMap<String, ExecFileLoader>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(inputDir, "*.exec")) {
            for (Path f : ds) {
                if (!Files.isRegularFile(f)) continue;                       // skip dirs named *.exec
                if (AGGREGATE_EXEC.equals(f.getFileName().toString())) continue;  // skip whole-run dump
                String key = stem(f);
                String testId = (mapping == null) ? null : mapping.testIdFor(key);
                if (testId == null) {
                    testId = key;
                    if (metrics != null) metrics.unmappedTraceIds.incrementAndGet();
                }
                ExecFileLoader loader = byTestId.get(testId);
                if (loader == null) { loader = new ExecFileLoader(); byTestId.put(testId, loader); }
                loader.load(f.toFile());                    // OR-merges same-classId probes
            }
        }
        if (byTestId.isEmpty()) return;
        Files.createDirectories(outputDir);
        for (Map.Entry<String, ExecFileLoader> e : byTestId.entrySet()) {
            writeMerged(outputDir, e.getKey(), e.getValue().getExecutionDataStore());
        }
    }

    private static void writeMerged(Path dir, String testId, ExecutionDataStore store) throws Exception {
        Path exec = dir.resolve(testId + ".exec");
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(exec))) {
            ExecutionDataWriter w = new ExecutionDataWriter(os);
            SessionInfoStore sessions = new SessionInfoStore();
            sessions.visitSessionInfo(new SessionInfo(testId, 0L, 0L));
            sessions.accept(w);
            store.accept(w);
        }
    }

    private static String stem(Path f) {
        String n = f.getFileName().toString();
        return n.substring(0, n.length() - ".exec".length());
    }
}
```

- [ ] **Step 4: 통과 확인** — `./gradlew :agent:test --tests '*TraceCoverageMergerTest*'` → PASS.
- [ ] **Step 5: 커밋**

```bash
git add agent/src/main/java/io/pjacoco/agent/output/TraceCoverageMerger.java \
        agent/src/test/java/io/pjacoco/agent/output/TraceCoverageMergerTest.java
git commit -m "feat(agent): TraceCoverageMerger N:1 merge + raw-traceId fallback (REQ-013, REQ-012, REQ-019)"
```

---

## Task 5: control endpoint `/trace/map` + Bootstrap 배선 + 종료 덤프

**REQ-IDs:** REQ-011

**Files:**
- Modify: `agent/src/main/java/io/pjacoco/agent/control/ControlEndpoint.java`
- Modify: `agent/src/main/java/io/pjacoco/agent/AgentOptions.java`
- Modify: `agent/src/main/java/io/pjacoco/agent/Bootstrap.java`
- Test: `agent/src/integrationTest/java/io/pjacoco/agent/it/TraceMapEndpointIT.java`

**Interfaces:**
- Consumes: `TestIdMappingRegistry`(T3). `TraceCoverageMerger`(T4)는 **이 task에서 배선하지 않는다** — 러너/CI가 오프라인 호출(운영 계약 참조). `TraceMapEndpointIT.registeredShown`은 검증용으로만 merger를 직접 호출한다.
- Produces:
  - `ControlEndpoint(TestStoreRegistry registry, TestIdMappingRegistry mapping, String host, int port)` — 생성자에 mapping 추가.
  - 신규 라우트 `POST /__coverage__/trace/map?traceId=<T>&testId=<FQCN#method>` → `mapping.register(traceId, testId)` → `200 "mapped ..."`; 둘 중 누락 시 `400`.
  - `AgentOptions.maxTraceMappings()` → `int`(기본 100000).
  - Bootstrap: `trace-map.properties`를 종료 hook에서 `outDir`에 덤프.

> **호환성:** `ControlEndpoint` 생성자 시그니처가 바뀐다. 호출처는 `Bootstrap`(이 task에서 갱신)과 기존 테스트 `ControlEndpointTest` 뿐 — 후자는 Step에서 함께 갱신한다.

- [ ] **Step 1: 실패 통합 테스트 작성**

```java
package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.*;
import io.pjacoco.agent.control.ControlEndpoint;
import io.pjacoco.agent.mapping.TestIdMappingRegistry;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.output.TraceCoverageMerger;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceMapEndpointIT {

    private static int post(int port, String pathAndQuery) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + pathAndQuery).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) { os.write(new byte[0]); }
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }

    private static void writeExec(Path dir, String key) throws Exception {
        Files.createDirectories(dir);
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(dir.resolve(key + ".exec")))) {
            ExecutionDataWriter w = new ExecutionDataWriter(os);
            w.visitSessionInfo(new SessionInfo(key, 0L, 0L));
            w.visitClassExecution(new ExecutionData(9L, "com/x/Svc", new boolean[] { true }));
        }
    }

    @Test
    void registeredShown(@TempDir Path dir) throws Exception {                  // REQ-011 acceptance #1
        TestIdMappingRegistry mapping = new TestIdMappingRegistry(100);
        TestStoreRegistry reg = new TestStoreRegistry(dir, new ExecWriter(), new Metrics(),
                new AgentLog(), false, 1000, System::currentTimeMillis);
        ControlEndpoint ep = new ControlEndpoint(reg, mapping, "127.0.0.1", 0);
        int port = ep.start();
        try {
            // 1) register a mapping THROUGH the HTTP control plane (%23 -> '#')
            assertEquals(200, post(port, "/__coverage__/trace/map?traceId=4bf92f&testId=com.x.T%23m"));
            assertEquals("com.x.T#m", mapping.testIdFor("4bf92f"));            // registered + normalized
            assertEquals(400, post(port, "/__coverage__/trace/map?traceId=onlytrace"));  // missing testId
        } finally {
            ep.stop();
        }
        // 2) report-time: a per-trace .exec keyed by the raw traceId, merged with the registered mapping,
        //    surfaces under the FQCN#method testId (full register -> merge -> report path, REQ-011).
        Path in = dir.resolve("traces"), out = dir.resolve("report");
        writeExec(in, "4bf92f");
        new TraceCoverageMerger().merge(in, mapping, out, new Metrics());
        assertTrue(Files.exists(out.resolve("com.x.T#m.exec")), "merged report keyed by registered testId");
    }

    @Test
    void boundedEvictionThroughEndpoint(@TempDir Path dir) throws Exception {   // REQ-011 acceptance #2
        TestIdMappingRegistry mapping = new TestIdMappingRegistry(2);           // cap = 2
        TestStoreRegistry reg = new TestStoreRegistry(dir, new ExecWriter(), new Metrics(),
                new AgentLog(), false, 1000, System::currentTimeMillis);
        ControlEndpoint ep = new ControlEndpoint(reg, mapping, "127.0.0.1", 0);
        int port = ep.start();
        try {
            assertEquals(200, post(port, "/__coverage__/trace/map?traceId=t1&testId=com.x.T%23a"));
            assertEquals(200, post(port, "/__coverage__/trace/map?traceId=t2&testId=com.x.T%23b"));
            assertEquals(200, post(port, "/__coverage__/trace/map?traceId=t3&testId=com.x.T%23c"));  // evicts t1
            assertEquals(2, mapping.size(), "store stays within the bound under sustained registration");
            assertNull(mapping.testIdFor("t1"), "eldest (t1) evicted");
            assertEquals("com.x.T#c", mapping.testIdFor("t3"));
        } finally {
            ep.stop();
        }
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :agent:integrationTest --tests '*TraceMapEndpointIT*'` → FAIL(생성자 시그니처/라우트 없음).

- [ ] **Step 3: 구현 — `ControlEndpoint`** — 생성자에 `mapping` 추가, 라우트 등록, 핸들러 추가:

```java
    private final TestIdMappingRegistry mapping;
    // ...
    public ControlEndpoint(TestStoreRegistry registry, TestIdMappingRegistry mapping, String host, int port) {
        this.registry = registry;
        this.mapping = mapping;
        this.host = host;
        this.port = port;
    }
```

`start()`의 컨텍스트 등록에 추가(기존 start/stop 다음):

```java
        server.createContext("/__coverage__/trace/map", new HttpHandler() {
            public void handle(HttpExchange ex) throws IOException { handleTraceMap(ex); }
        });
```

핸들러(기존 `handleStop` 다음):

```java
    private void handleTraceMap(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        String traceId = q.get("traceId");
        String testId = q.get("testId");
        if (traceId == null || testId == null) { respond(ex, 400, "missing traceId or testId"); return; }
        mapping.register(traceId, testId);
        respond(ex, 200, "mapped " + traceId + " -> " + testId);
    }
```

`import io.pjacoco.agent.mapping.TestIdMappingRegistry;` 추가.

- [ ] **Step 4: 구현 — `AgentOptions.maxTraceMappings()`** — `traceKeyAutoCreate()` 옆에:

```java
    /** Bounded cap for the report-time traceId->testId map (REQ-011). Default 100000. */
    public int maxTraceMappings() { return Integer.parseInt(get("maxTraceMappings", "100000")); }
```

- [ ] **Step 5: 구현 — `Bootstrap` 배선** — registry 생성 직후 mapping 생성, endpoint 생성자에 주입, 종료 hook에 덤프 추가:

```java
        final TestIdMappingRegistry mapping = new TestIdMappingRegistry(options.maxTraceMappings());
```

endpoint 생성(line 72)을 다음으로 교체:

```java
            ControlEndpoint endpoint = new ControlEndpoint(registry, mapping, options.controlHost(), options.controlPort());
```

종료 hook의 aggregate 다음(line 95 이후)에 추가:

```java
                mapping.writeTo(outDir.resolve("trace-map.properties"));
```

`import io.pjacoco.agent.mapping.TestIdMappingRegistry;` 추가.

- [ ] **Step 6: 기존 `ControlEndpointTest` 갱신** — `new ControlEndpoint(reg, host, port)` 호출부를 `new ControlEndpoint(reg, new TestIdMappingRegistry(1000), host, port)`로 갱신(또는 테스트 헬퍼). 컴파일 회복.

- [ ] **Step 7: 통과 확인** — `./gradlew :agent:test :agent:integrationTest --tests '*ControlEndpoint*' --tests '*TraceMapEndpointIT*'` → PASS.

- [ ] **Step 8: 커밋**

```bash
git add agent/src/main/java/io/pjacoco/agent/control/ControlEndpoint.java \
        agent/src/main/java/io/pjacoco/agent/AgentOptions.java \
        agent/src/main/java/io/pjacoco/agent/Bootstrap.java \
        agent/src/test/java/io/pjacoco/agent/control/ControlEndpointTest.java \
        agent/src/integrationTest/java/io/pjacoco/agent/it/TraceMapEndpointIT.java
git commit -m "feat(agent): /trace/map control route + mapping registry wiring + shutdown dump (REQ-011)"
```

---

## Task 6: REQ-012 수용 — 미등록 traceId가 리포트에 raw로 (실측 .exec 경유)

**REQ-IDs:** REQ-012

**Files:**
- Test: `agent/src/integrationTest/java/io/pjacoco/agent/it/UnmappedTraceReportIT.java`

> **E2E 타당성(최고 실현가능 레벨):** REQ-012의 본질(미등록 key → raw testId)은 **transport-독립**인 리포트 레이어 행위다 — 트레이서/분산 토폴로지와 무관하게, per-key `.exec`가 매핑 없이 병합될 때의 키 계약이 전부다. 완전 분산 out-of-process E2E는 C3의 `TaintedSpringDistributedE2E`(REQ-015) 영역이며, C2 단독으로는 그 인프라를 띄울 수 없다. 따라서 C2에서는 **실제 에이전트 경로가 생성한 per-key `.exec`**(in-process `CoverageControl` start/record/stop = 실 `TestStoreRegistry`→`ExecWriter` 산출물, 합성 파일 아님)를 입력으로 merger를 돌려 raw traceId 키가 리포트에 그대로 나오는지 실측한다 — 출력-파일 계약을 실 파이프라인으로 검증한다. 요구사항명세는 각 REQ에 "최고 실현가능 검증 레벨"을 허용하므로(분산 인프라 부재 시 통합/블랙박스로 강등 가능), 매트릭스의 REQ-012 Level을 이 근거와 함께 "integration(최고 실현가능)"으로 갱신한다(T8).

- [ ] **Step 1: 실패 테스트 작성** — 실 에이전트 경로로 `<rawTraceId>.exec` 생성 → 빈 매핑으로 merge → raw 키 산출 + 카운터.

```java
package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.*;
import io.pjacoco.agent.api.CoverageControl;
import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.output.TraceCoverageMerger;
import io.pjacoco.agent.mapping.TestIdMappingRegistry;
import io.pjacoco.agent.probe.CoverageBridge;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** REQ-012: a tracer-mode store keyed by a raw traceId, when reported with no mapping, keeps the raw
 *  traceId as the testId. Drives the real registry->ExecWriter pipeline to produce the input .exec. */
class UnmappedTraceReportIT {

    @AfterEach
    void cleanup() {                                    // CoverageControl.registry is static volatile
        CoverageControl.bindRegistry(null);             // parity with CoverageControlTest — avoid pollution
        CoverageContext.clear();
    }

    @Test
    void rawTraceIdAsTestId(@TempDir Path tmp) throws Exception {
        Path raw = tmp.resolve("raw"), merged = tmp.resolve("merged");
        Metrics metrics = new Metrics();
        TestStoreRegistry reg = new TestStoreRegistry(raw, new ExecWriter(), metrics, new AgentLog(),
                false, 1000, System::currentTimeMillis, /*traceKeyAutoCreate*/ true);
        CoverageBridge.bindMetrics(metrics);
        CoverageControl.bindRegistry(reg);

        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        // tracer-mode store auto-created for the raw traceId key, record some coverage, flush to .exec
        CoverageControl.activate(traceId, null);
        CoverageBridge.setTotalProbeCount("com/x/Svc", 2);
        CoverageBridge.recordCoverage(Class.forName("java.lang.String"), strClassId(), 0);
        CoverageControl.deactivate(traceId, "passed");           // -> writes <traceId>.exec

        assertTrue(Files.exists(raw.resolve(traceId + ".exec")), "agent produced raw-traceId .exec");

        // report with an EMPTY mapping -> raw traceId passes through as testId, counter increments
        new TraceCoverageMerger().merge(raw, TestIdMappingRegistry.loadFrom(tmp.resolve("absent.properties")),
                merged, metrics);

        assertTrue(Files.exists(merged.resolve(traceId + ".exec")), "raw traceId is the testId");
        assertEquals(1L, metrics.unmappedTraceIds.get());
    }

    /** classId of java.lang.String under the agent's hashing — any stable non-instrumented class works as a
     *  probe carrier here; we only assert the key/file contract, not probe semantics. */
    private static long strClassId() { return "java/lang/String".hashCode(); }
}
```

> 구현 메모: 위 테스트는 `CoverageControl.activate/deactivate`와 `CoverageBridge`의 실제 시그니처에 맞춘다 — 구현 시 C1의 `OtelWeaveE2E`/`TraceConsumeFailureIT`가 store를 채우는 방식(같은 `CoverageBridge.recordCoverage(Class, long, int)` + `setTotalProbeCount`)을 참조해 정확히 일치시킨다. classId 인자는 `record`가 받는 `long`이며, 값 자체는 무의미(키/파일 계약만 단언)하다. 핵심 단언 2개(파일명=raw traceId, `unmappedTraceIds==1`)는 불변이다.

- [ ] **Step 2: 실패 확인** — merger 호출 전(T4 미존재 시) 또는 신규 IT로 FAIL 확인. T4 이후엔 store 산출 경로만 맞추면 GREEN으로 수렴.

- [ ] **Step 3: 산출 경로 맞춤** — `CoverageControl`/`CoverageBridge` 실제 시그니처로 테스트를 조정(컴파일·실행). 프로덕션 코드 변경 불요(REQ-012는 T4 merger로 이미 구현됨).

- [ ] **Step 4: 통과 확인** — `./gradlew :agent:integrationTest --tests '*UnmappedTraceReportIT*'` → PASS.

- [ ] **Step 5: 커밋**

```bash
git add agent/src/integrationTest/java/io/pjacoco/agent/it/UnmappedTraceReportIT.java
git commit -m "test(agent): unmapped traceId reported as raw testId, end-to-end via real .exec (REQ-012)"
```

---

## Task 7: REQ-014 어댑터 정렬 — 블랙박스 어댑터를 FQCN으로

**REQ-IDs:** REQ-014

> **배경:** REQ-014가 명시한 "getClassName vs getSimpleName 불일치 제거". 현 상태(확인됨): in-process 어댑터(`PjacocoInProcessExtension`/`PjacocoInProcessRule`)·`RunLeafAdvice`는 FQCN(`getName()`/`getClassName()`)을, **블랙박스 어댑터 `PjacocoExtension`(junit5)·`PjacocoRule`(junit4)만 `getSimpleName()`** 을 쓴다. 둘을 FQCN으로 정렬해 모든 어댑터가 정규 `FQCN#method`를 산출하게 한다. T1 정규화는 등록 경계의 방어선, 이 task는 소스 정렬.
>
> **행위 변화 고지:** 블랙박스 junit5/junit4 사용자의 per-test 산출물 testId가 `SimpleName#method` → `FQCN#method`로 바뀐다. README/changelog에 명시(T8). 영향: 출력 파일명·JSON `testId` 슬롯. 다운스트림 리포트는 FQCN으로 더 정확해진다(버그 수정 성격).

**Files:**
- Modify: `testkit-junit5/src/main/java/io/pjacoco/testkit/junit5/PjacocoExtension.java`
- Modify: `testkit-junit4/src/main/java/io/pjacoco/testkit/junit4/PjacocoRule.java`
- Modify (기존 테스트, 현재 SimpleName 단언 → red→green 신호): `testkit-junit5/src/test/java/io/pjacoco/testkit/junit5/PjacocoExtensionTest.java`, `testkit-junit4/src/test/java/io/pjacoco/testkit/junit4/PjacocoRuleTest.java`

> **실측 기준값(확인됨):** 두 테스트는 mock/`Description`으로 testId 도출을 직접 검증한다.
> - `PjacocoExtensionTest`는 top-level `PjacocoExtensionTest.class` + `sampleMethod`를 사용 → FQCN `io.pjacoco.testkit.junit5.PjacocoExtensionTest#sampleMethod`. control-URL 쿼리는 `Pjacoco.enc()`(=`URLEncoder`)로 인코딩(`#`→`%23`, 점은 리터럴) → `testId=io.pjacoco.testkit.junit5.PjacocoExtensionTest%23sampleMethod`.
> - `PjacocoRuleTest`는 nested `SampleSuite`(`createTestDescription(SampleSuite.class, ...)`)를 사용 → FQCN `io.pjacoco.testkit.junit4.PjacocoRuleTest$SampleSuite#doesThing`. 인코딩 시 `$`→`%24`, `#`→`%23` → `testId=io.pjacoco.testkit.junit4.PjacocoRuleTest%24SampleSuite%23doesThing`.

- [ ] **Step 1: 기존 테스트의 기대값을 SimpleName→FQCN으로 갱신(red 신호)** — 현재 SimpleName을 단언하므로 어댑터 변경 후 FAIL→갱신 후 PASS.
  - `PjacocoExtensionTest`:
    - `beforeEachStartsAndSetsId_afterEachStopsAndClears`:
      - `assertEquals("PjacocoExtensionTest#sampleMethod", Pjacoco.currentTestId(), ...)` → `assertEquals("io.pjacoco.testkit.junit5.PjacocoExtensionTest#sampleMethod", Pjacoco.currentTestId(), ...)`
      - start 쿼리 `"/__coverage__/test/start?testId=PjacocoExtensionTest%23sampleMethod"` → `"/__coverage__/test/start?testId=io.pjacoco.testkit.junit5.PjacocoExtensionTest%23sampleMethod"`
      - stop 쿼리 동일하게 `...&result=passed` 앞부분을 FQCN-encoded로.
    - `afterEachReportsFailedWhenExecutionExceptionPresent`: stop 쿼리의 testId를 FQCN-encoded로(`...%23sampleMethod&result=failed`).
  - `PjacocoRuleTest`:
    - `passingTest_startsThenStopsPassed_andClears`:
      - `assertEquals("SampleSuite#doesThing", idDuring[0], ...)` → `assertEquals("io.pjacoco.testkit.junit4.PjacocoRuleTest$SampleSuite#doesThing", idDuring[0], ...)`
      - start/stop 쿼리의 `SampleSuite%23doesThing` → `io.pjacoco.testkit.junit4.PjacocoRuleTest%24SampleSuite%23doesThing`.
    - `failingTest_stopsFailed`: stop 쿼리 `SampleSuite%23boom` → `io.pjacoco.testkit.junit4.PjacocoRuleTest%24SampleSuite%23boom`.

- [ ] **Step 2: 실패 확인** — 어댑터 변경 전 위 갱신을 먼저 적용하면 FAIL(현재 코드가 SimpleName 산출). `./gradlew :testkit-junit5:test :testkit-junit4:test` → FAIL.

- [ ] **Step 3: 구현 — `PjacocoExtension`** — `testId(ExtensionContext)` 헬퍼(현재 `getSimpleName()` 사용)를 `getName()`으로:

```java
    private static String testId(ExtensionContext context) {
        return context.getRequiredTestClass().getName() + "#" + context.getRequiredTestMethod().getName();
    }
```

- [ ] **Step 4: 구현 — `PjacocoRule`** — `className` 도출(현재 `testClass.getSimpleName()`)을 `getName()`으로:

```java
        String className = testClass != null ? testClass.getName() : description.getClassName();
```

- [ ] **Step 5: 통과 확인** — `./gradlew :testkit-junit5:test :testkit-junit4:test` → PASS. (in-process 어댑터 `PjacocoInProcessExtension`/`PjacocoInProcessRule`은 이미 `getName()`이라 무변경; `RunLeafAdvice`도 FQCN.)

- [ ] **Step 6: 커밋**

```bash
git add testkit-junit5/src/main/java/io/pjacoco/testkit/junit5/PjacocoExtension.java \
        testkit-junit4/src/main/java/io/pjacoco/testkit/junit4/PjacocoRule.java \
        testkit-junit5/src/test/java/io/pjacoco/testkit/junit5/PjacocoExtensionTest.java \
        testkit-junit4/src/test/java/io/pjacoco/testkit/junit4/PjacocoRuleTest.java
git commit -m "fix(testkit): black-box adapters emit FQCN#method, aligning all adapters (REQ-014)"
```

---

## Task 8: 문서 동기화 + 매트릭스 갱신 + 전체 regression (DoD)

**REQ-IDs:** REQ-011, REQ-012, REQ-013, REQ-014, REQ-019(부분)

**Files:**
- Modify: `docs/superpowers/requirements/2026-06-19-trace-context-per-test-coverage-requirements.md`(매트릭스 REQ-011~014 → 🟢, 실제 테스트명 대조, REQ-012 Level 근거, REQ-019 비고 갱신)
- Modify: `docs/superpowers/specs/2026-06-19-trace-context-per-test-coverage-design.md`(§5.2-4: `TraceCoverageMerger`를 "C2에서 단일-서비스 시드 도입, C3에서 서비스 축 확장"으로 명확화; §10 해소 항목 표시)
- Modify: `README.md`(`/trace/map` 사용법 + `maxTraceMappings`/`trace-map.properties` + 블랙박스 어댑터 FQCN 행위 변화)

- [ ] **Step 1: 매트릭스 갱신** — 현 매트릭스(requirements 라인 237~245)의 **stale 계획-테스트명을 실제 구현명으로 교체**하고 Status를 🟢로. (현 행은 `TraceMapEndpointIT#{registeredShown,boundedEviction}`(OK), `UnmappedTraceE2E#rawTraceIdAsTestId`(→교체), `TraceMergeTest#multipleTraceIdsOneTestId`(→교체), `TestIdNormalizationTest#toFqcnHashMethod`(OK)로 적혀 있음.)
  - REQ-011 🔴→🟢 → `TraceMapEndpointIT#{registeredShown, boundedEvictionThroughEndpoint}` (integration). registeredShown이 등록→merge→`<FQCN#method>.exec`까지 검증(수용기준 #1), boundedEvictionThroughEndpoint가 HTTP 연속 등록 LRU(수용기준 #2).
  - REQ-012 🔴→🟢 → **`UnmappedTraceE2E#rawTraceIdAsTestId` → `UnmappedTraceReportIT#rawTraceIdAsTestId`로 클래스명 교체** + `TraceCoverageMergerTest#unmappedTraceFallsBackToRawAndCounts`. Level 셀: "E2E → integration(최고 실현가능)" + 근거 한 줄(분산 인프라는 C3, 리포트 레이어는 transport-독립).
  - REQ-013 🔴→🟢 → **`TraceMergeTest#multipleTraceIdsOneTestId` → `TraceCoverageMergerTest#multipleTraceIdsOneTestId`로 클래스명 교체** (+ `aggregateExecAndDirsAreExcluded` 부가 증거).
  - REQ-014 🔴→🟢 → `TestIdNormalizationTest#toFqcnHashMethod` (unit) + `PjacocoExtensionTest`/`PjacocoRuleTest`(어댑터 FQCN 정렬, 행 비고에 명시).
  - REQ-019 🟡 유지 → 수용 테스트 열에 `MetricsTest#unmappedTraceIdsStartsAtZeroAndCounts` 추가, 비고를 **"C1: scopeHookInjectionFailures+fallbackActivations; C2: unmappedTraceIds; C3: evictedInFlightTraces"** 로 갱신(현 비고는 unmappedTraceIds를 C3로 잘못 귀속 — C2로 정정).
  - C2 완료 섹션 추가: Must REQ-011·012 + Should REQ-013·014 = 4/4 🟢, REQ-019 추가 전진(여전히 🟡).

- [ ] **Step 2: design spec 명확화** — §5.2-4에 한 줄: "단일-서비스 형태를 C2에서 도입(`io.pjacoco.agent.output.TraceCoverageMerger`), 서비스 축·drain-wait·중앙 수집은 C3." §10의 "출력 포맷/`ExecutionDataStore.merge()` 적용 가부"를 "해소: `ExecFileLoader` OR-merge"로 표시. (변경분만 → 3-벤더 design-doc 리뷰 재실행은 T8 이후 별도.)

- [ ] **Step 3: README 갱신** — control 평면 표에 `POST /__coverage__/trace/map?traceId=&testId=` 추가; `maxTraceMappings`(기본 100000)·종료 시 `trace-map.properties` 산출·`TraceCoverageMerger`로 `<testId>.exec` 병합 설명; 블랙박스 junit5/4 어댑터가 이제 FQCN#method를 산출한다는 행위 변화 1줄.

- [ ] **Step 4: 전체 regression** — `./gradlew clean build` (또는 `:agent:test :agent:integrationTest :testkit-junit5:test :testkit-junit4:test :testkit-restassured:test`) → 전부 PASS. 기존 C1 E2E(`OtelWeaveE2E` 등 OTel agent jar 있을 때)·통합 회귀 green 확인. 실행 불가 항목(예: OTel agent jar 부재로 `assumeTrue` skip)은 명시.

- [ ] **Step 5: 커밋**

```bash
git add docs/superpowers/requirements/2026-06-19-trace-context-per-test-coverage-requirements.md \
        docs/superpowers/specs/2026-06-19-trace-context-per-test-coverage-design.md \
        README.md
git commit -m "docs(c2): traceId->testId mapping matrix green + design/README sync (REQ-011..014, REQ-019)"
```

---

## C3로의 연결 (다음 plan의 출발점)

C2가 산출한 단위들이 C3의 직접 입력이다 — **C3의 출발점은 "cross-service testId 병합"**:
- `TestIdMappingRegistry`/`trace-map.properties` → C3에선 **러너가 중앙에 한 번** 보고하는 `traceId→testId` 맵(per-service 등록 불필요, design §5.2-3).
- `TraceCoverageMerger`(단일 디렉터리) → C3에서 **서비스 축 + drain-wait 타임아웃 + 중앙 수집 토폴로지**(공유 볼륨/control-pull/CI 아티팩트 택1, design §6.5)로 확장 → REQ-015(서비스 간 병합 리포트)·REQ-023(수집+drain).
- `unmappedTraceIds` 카운터 → REQ-019 잔여(`evictedInFlightTraces`)와 함께 C3에서 완성.
- 본 Kafka-consumer 갭은 C1 잠재버그로 **이미 닫힘**(PR #13) — C3 신규 기능 아님.

---

## Self-Review (작성자 점검 완료)

**1. Spec coverage:** REQ-011(T3 구조+T5 endpoint/bounded) · REQ-012(T4 merger 폴백+카운터, T6 실측) · REQ-013(T4 N:1 OR-merge) · REQ-014(T1 정규화+T7 어댑터 정렬) · REQ-019 C2 몫(T2 카운터+T4 증가) — 전부 task 대응. 불변 제약(REQ-001/002/003)은 Global Constraints로 상속(신규 코드 핫패스 무관·순수 Java·best-effort).

**2. Placeholder scan:** 모든 코드 스텝에 완전한 코드 수록. "적절히 처리" 류 없음. T6의 `CoverageBridge`/`CoverageControl` 시그니처 맞춤은 "C1 참조 테스트와 정확히 일치" + 불변 단언 2개로 구체화(플레이스홀더 아님 — 구현 시 기존 통과 테스트가 정답 형태 제공).

**3. Type consistency:** `TraceMapping.testIdFor(String):String`(미등록 null), `TestIdMappingRegistry.{register(String,String):void, testIdFor, size():int, writeTo(Path):void, loadFrom(Path):TraceMapping}`, `TestIdNormalizer.normalize(String):String`, `TraceCoverageMerger.merge(Path,TraceMapping,Path,Metrics):void`, `ControlEndpoint(TestStoreRegistry,TestIdMappingRegistry,String,int)`, `AgentOptions.maxTraceMappings():int`, `Metrics.unmappedTraceIds:AtomicLong` — task 간 일관.

---

## 3-벤더 design-doc 리뷰 반영 (2026-06-20)

Claude Sonnet + Gemini 3.5 Flash(High) + Cursor(auto) 동시 리뷰 — 셋 다 **approved_with_conditions**. 작성자 판정(수용/기각 + 근거):

**수용해 반영:**
- **REQ-011 full-path 수용 부재(Cursor I1, 최강):** `TraceMapEndpointIT.registeredShown`을 register(HTTP)→`<traceId>.exec`→`merge`→`<FQCN#method>.exec` 존재까지 확장(수용기준 #1을 실 경로로 증명).
- **merge 실행 주체·시점 미정의(Cursor I3/I11):** "운영 계약" 절 신설(러너/CI 오프라인 호출, Bootstrap은 merge 미배선, in-place 금지). Task 5 Interfaces에서 `TraceCoverageMerger` consumes 제거.
- **inputDir=outDir 시 `aggregate.exec` 오병합(Cursor I4) + 비정규 파일(Gemini I1):** merger가 `aggregate.exec`·서브디렉터리 제외 + `aggregateExecAndDirsAreExcluded` 테스트.
- **Task 7 commit에 `PjacocoRuleTest` 누락(Claude I2/Cursor I7, 3-way):** git add 포함 + 두 테스트의 정확한 FQCN(인코딩 `%23`/`%24`) 기대값 명시.
- **`UnmappedTraceReportIT` 정적 상태 정리 부재(Claude I3):** `@AfterEach`로 `CoverageControl.bindRegistry(null)`+`CoverageContext.clear()`(CoverageControlTest 패턴).
- **매트릭스 stale 이름 + REQ-019 귀속(Claude I1/Cursor I6):** Task 8에 `UnmappedTraceE2E→UnmappedTraceReportIT`, `TraceMergeTest→TraceCoverageMergerTest` 교체 명시 + REQ-019 비고 C1/C2/C3 분리(unmappedTraceIds=C2).
- **boundedEviction이 endpoint 미경유(Claude I4/Cursor I2):** `boundedEvictionThroughEndpoint`로 cap=2 + HTTP 3 POST 재작성.
- **`ExecutionDataStore.merge()` 명명(Claude I5):** "public merge() 없음 — `put()`이 동일 classId 시 `ExecutionData.merge()` 호출" 로 정확화.
- **JSON sidecar 미병합(Cursor I9):** C2 명시적 non-goal 한 줄.
- **design drift(Cursor I5):** plan 서두 "design drift 주의" 박스 추가.
- **CLAUDE.md 인용 검증불가(Cursor I10, 부분):** REQ-012 Level 근거를 요구사항명세의 "최고 실현가능 레벨" 근거로 self-contained화.

**기각(근거):**
- **outputDir이 정규 파일일 때 가드(Gemini I2):** `outputDir`은 호출자(러너)가 통제하는 새 reportDir이며 운영 계약상 입력과 분리됨 — 인위적 충돌 시 merge가 예외를 전파(throws Exception)하면 충분, 가드는 과설계.
- **line 39 vs 40 등 미세 위치(Claude I6):** Task 7을 "라인 번호" 대신 "`testId(...)` 헬퍼/`className` 도출" 패턴 로케이터로 기술해 무의미화(반영으로 흡수).

재리뷰: 변경은 모두 task 내부 보강(수용 테스트 추가·운영 계약 명문화·기대값 정정)으로 구조 재설계 없음 → 부분 재리뷰 불요. design spec §5.2-4/§10 동기화는 T8에서 수행 후 변경분만 design-doc 리뷰 재실행.
