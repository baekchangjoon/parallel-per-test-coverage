# Trace Context per-test Coverage — C3b Implementation Plan (분산 수집/병합)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **출처:** design spec `docs/superpowers/specs/2026-06-19-trace-context-per-test-coverage-design.md`(§5.2-4, §6.3, §6.5, §9 GA-2), 요구사항명세 `docs/superpowers/requirements/2026-06-19-trace-context-per-test-coverage-requirements.md`(REQ-015, REQ-023).
> **분해:** C3 후반부 **C3b(분산 수집/병합)**. C3a(store 생명주기, main 머지완료)가 산출한 "JVM 종료 없는 per-traceId `.exec` flush"가 입력. **C3 출발점=cross-service testId 병합** — C2의 단일-디렉터리 `TraceCoverageMerger`를 **서비스 축 + 중앙 맵 + drain-wait 수집**으로 확장한다.

**Goal:** 여러 서비스가 각자 raw traceId로 남긴 per-trace 커버리지를, 러너의 중앙 `traceId→testId` 맵으로 **서비스 간 병합**해 testId별·서비스별 리포트를 만든다(비동기 다운스트림 지연은 drain-wait로 흡수).

**Architecture:** 핫패스·에이전트 store 무변경. 신규 `DistributedCoverageMerger`(서비스 축: N개 서비스 출력 디렉터리 + 중앙 `TraceMapping` → `report/<service>/<testId>.exec`)와 `DistributedCollector`(공유 볼륨에서 per-service `.exec`를 drain-wait 후 수집→병합). 둘 다 핫패스 밖 **오프라인 단계**(러너/CI가 SUT 종료 없이 실행 — C3a reaper가 per-traceId flush를 보장). 실행 진입점 CLI `TraceMergeMain` 추가(C2/C3a의 알려진 "merge 실행 CLI 부재" followup 해소).

**Tech Stack:** Java 8, `org.jacoco.core.tools.ExecFileLoader`(relocate), `java.nio.file`, Docker(외부 E2E: legacy-tram Brave / tainted-spring OTel), JUnit5, Gradle. 수집 토폴로지 baseline = **공유 볼륨**(design §6.5 택1).

## Global Constraints

- 핫패스 무변경: C3b는 에이전트 store/probe 경로에 코드를 추가하지 않는다(전부 오프라인 수집/병합). 에이전트는 C3a까지로 충분(per-traceId `.exec` + `trace-map.properties` 산출).
- 트레이서 **런타임 하드 의존 0**, Java 8. merger/collector/CLI는 순수 JDK + jacoco-core(이미 의존).
- best-effort: 수집/병합은 한 파일/서비스 오류를 swallow하고 나머지를 계속(부분 리포트 허용, REQ-003).
- **서비스 차원 보존**: 동일 코드를 공유하는 서비스의 JaCoCo classId 충돌을 피하려 출력을 서비스별로 분리(`report/<service>/<testId>.exec`) (design §5.2-4 "공유 시 서비스 차원으로 분리").
- 중앙 맵은 **러너가 한 번** 보고(per-service 등록 불필요, design §5.2-3) — C2 `trace-map.properties`(`TestIdMappingRegistry.loadFrom`) 재사용.
- 비동기(Tram/CDC/Kafka) 지연 → **drain-wait 타임아웃** 후 수집(design §6.4/§7).

---

## 범위(REQ 매핑)와 완료 정의

| REQ-ID | 우선순위 | 내용 | 산출 Task |
|--------|----------|------|-----------|
| REQ-023 | Must | per-service `.exec` 중앙 수집 + drain 대기 | T2(merger), T3(collector), T5(E2E) |
| REQ-015 | Must | 서비스 간 per-test 커버리지 병합 리포트 | T2, T4(CLI), T6(E2E) |

**선결(GA-2 게이트):** OTel javaagent가 Kafka 홉에서 trace context를 자동 전파한다(§9). C1 cross-JVM 실측(mindgraph classCount 0→14, `DiaryCreatedConsumer` 귀속, PR #13)이 consumer 귀속을 이미 입증 → T1에서 재확인/인용. 거짓이면 Kafka 경계 명시 전파(인터셉터/헤더) 폴백.

**C3b 완료 정의(DoD):** REQ-015·023(Must) 매트릭스 🟢. **핵심 병합/수집 로직은 in-process 통합 테스트로 결정론 검증**(crafted 다중 서비스 디렉터리 + 중앙 맵). **수용(E2E)은 실제 Docker 다중 서비스**(Docker 가용·환경 present 확인됨): Brave=legacy-tram, OTel=tainted-spring. 전체 regression green. 문서 갱신. 이로써 **요구사항 매트릭스 전 in-scope REQ 100% 🟢**(C1 12 + C2 4 + C3a 4 + C3b 2; 제외: Won't 2, deferred 1).

**더블 루프:** 바깥(수용) = REQ-023 `DistributedCollectIT`(T5) + REQ-015 `LegacyTramDistributedE2E`/`TaintedSpringDistributedE2E`(T6, Docker). 안쪽 = T2~T4 단위/통합 TDD.

---

## File Structure

**신규 (agent 모듈):**
- `agent/src/main/java/io/pjacoco/agent/output/DistributedCoverageMerger.java` — 서비스 축 병합. `merge(Map<String,Path> serviceDirs, TraceMapping central, Path reportDir, Metrics metrics)` → `reportDir/<service>/<testId>.exec`(서비스 내 동일 testId의 다수 traceId는 OR-merge). C2 `TraceCoverageMerger.merge`를 서비스별 `reportDir/<service>/`로 호출하는 얇은 상위 루프(병합 로직 중복 없음). per-service 오류는 swallow + `AgentLog.warn`(I6).
- `agent/src/main/java/io/pjacoco/agent/output/DistributedCollector.java` — 공유 볼륨 수집 + drain-wait. **확정 API:** `void collectAfterDrain(Path sharedVolume, TraceMapping central, Path reportDir, long drainWaitMillis, Sleeper sleeper, Metrics metrics)` → `sleeper.sleep(drainWaitMillis)` 후 `sharedVolume`의 **하위 디렉터리만**(`Files.isDirectory`, `trace-map.properties`/stray 파일·hidden 제외) 서비스로 발견해 `DistributedCoverageMerger.merge` 호출. 중첩 `interface Sleeper { void sleep(long millis) throws Exception; }`(주입; 실제는 `Thread.sleep` 래퍼, 테스트는 late-arrival hook). `LongSupplier clock` 불필요(제거).
- `agent/src/main/java/io/pjacoco/agent/output/TraceMergeMain.java` — CLI 진입점. args: `--map <trace-map.properties> --report <dir> [--drain-wait-ms N(기본 15000)]` + **택1 입력 모드**: `--shared <dir>`(하위 디렉터리=서비스, drain-wait 수집) 또는 반복 `--service-dir <serviceName>=<path>`(명시 서비스, drain-wait 없이 즉시 병합). 두 모드 동시 지정은 오류(비-0). 러너/CI가 실행(merge 실행 CLI 부재 followup 해소).

**수정 (agent 모듈):**
- (핫패스/store 무변경.) `DistributedCoverageMerger`는 C2 `TraceCoverageMerger.merge(Path,TraceMapping,Path,Metrics)`를 그대로 위임 호출(시그니처 확인됨 — outputDir를 생성하고 `<testId>.exec` 기록).

**신규 (spike/문서):**
- GA-2는 C1 cross-JVM 실측 증거 인용(별도 spike 코드 불요) → `docs/superpowers/plans/ga-spike-results.md`에 기록.

**테스트:**
- `agent/src/test/java/io/pjacoco/agent/output/DistributedCoverageMergerTest.java`(서비스 축 + N:1 + 미등록 raw)
- `agent/src/integrationTest/java/io/pjacoco/agent/it/DistributedCollectIT.java`(REQ-023 drain-wait, 주입 Sleeper)
- `agent/src/test/java/io/pjacoco/agent/output/TraceMergeMainTest.java`(CLI `--shared` + `--service-dir` 모드)
- **E2E(Docker): `agent/src/integrationTest/java/io/pjacoco/agent/it/{LegacyTramDistributedE2E,TaintedSpringDistributedE2E}.java`** — **`integrationTest` source set에 배치**(repo에 `e2eTest` source set 없음; `integrationTest`가 실존). `@Tag` 불필요 대신 **`assumeTrue` 게이트**(env var + Docker)로 부재 시 skip(기본 CI는 env 미설정→skip). build.gradle.kts 변경 없음.

**§10 미해결 질문 해소(이 plan에서 확정):** §6.5 수집 토폴로지 = **공유 볼륨**(서비스가 `<sharedVol>/<service>/`에 출력). **drain-wait 기본값 = 15000ms**(CLI `--drain-wait-ms` 기본, §10·T4·T6 전 구간 동일; 단위 테스트만 `0` 명시로 결정론). per-service `.exec` 명명 = C3a reaper의 `<traceId>.exec`. **design §6.4(b) 루트 scope-close 즉시 flush는 C3b 범위 외**(idle reaper+grace로 충분; 후속 옵션) — T7에서 design §6.4에 defer 명시.

---

## Task 1: GA-2 spike — OTel Kafka 자동 전파 확인 (게이트)

**REQ-IDs:** (gate for REQ-015 OTel 경로)

**목표 질문:** OTel javaagent가 tainted-spring의 Kafka producer→consumer 홉에서 trace context(traceId)를 자동 전파하는가? (HTTP뿐 아니라 Kafka record header.)

- [ ] **Step 1: C1 증거 검토** — `docs/superpowers/decisions/2026-06-20-otel-weave-kafka-consumer-gap.md`(RESOLVED) + C1 실측: tainted-spring에서 mindgraph(별도 JVM, Kafka consumer)의 `classCount` 0→14, `DiaryCreatedConsumer`가 producer traceId에 귀속됨. **이는 consumer 스레드가 producer와 동일 traceId를 관측함을 의미 = Kafka 홉 trace 전파 PASS의 실측 증거.**
- [ ] **Step 2: 결정 기록** — `docs/superpowers/plans/ga-spike-results.md`에 GA-2 항목 추가: PASS(C1 cross-JVM 실측 근거). T6 OTel E2E가 동일 경로를 testId 귀속까지 확장해 재확인. **거짓이었다면** Kafka 경계 명시 전파(`eventuate-tram-spring-cloud-sleuth-tram-starter` 류 또는 Kafka 인터셉터) 폴백 — 단 증거상 불필요.
- [ ] **Step 3: 커밋** — `git commit -m "docs(spike): GA-2 OTel Kafka propagation PASS (C1 cross-JVM evidence) (C3b gate)"`

**Exit criteria:** GA-2 PASS 기록(증거 기반). FAIL이었으면 T6를 명시 전파로 재설계.

---

## Task 2: DistributedCoverageMerger (서비스 축 병합)

**REQ-IDs:** REQ-015, REQ-023(병합 부분)

**Files:**
- Create: `agent/src/main/java/io/pjacoco/agent/output/DistributedCoverageMerger.java`
- (필요 시) Modify: `agent/src/main/java/io/pjacoco/agent/output/TraceCoverageMerger.java`(단일-디렉터리 merge 재사용 헬퍼 추출)
- Test: `agent/src/test/java/io/pjacoco/agent/output/DistributedCoverageMergerTest.java`

**Interfaces:**
- Consumes: `TraceMapping`(C2), `TraceCoverageMerger`(C2 단일-서비스 merge), `Metrics`.
- Produces: `void DistributedCoverageMerger.merge(Map<String,Path> serviceDirs, TraceMapping central, Path reportDir, Metrics metrics)` — 각 `(service, dir)`에 대해 C2 단일-서비스 merge를 `reportDir/<service>/` 하위로 실행(중앙 맵 적용, 미등록→raw + `unmappedTraceIds++`). 결과: `reportDir/<service>/<testId>.exec`(서비스 내 N:1 OR-merge; 서비스 간은 디렉터리로 분리=classId 충돌 회피).

> **재사용:** C2 `TraceCoverageMerger.merge(inputDir, mapping, outputDir, metrics)`가 이미 단일 디렉터리를 testId로 그룹·OR-merge한다. `DistributedCoverageMerger`는 그것을 **서비스마다 `reportDir/<service>/`를 outputDir로** 호출하는 얇은 상위 루프다(DRY — 병합 로직 중복 없음).

- [ ] **Step 1: 실패 테스트 작성** — 2개 서비스 디렉터리(svcA: traceId T1, svcB: traceId T2), 둘 다 중앙 맵에서 `com.x.T#m`에 매핑 → 서비스별 `<testId>.exec` 산출.

```java
package io.pjacoco.agent.output;

import static org.junit.jupiter.api.Assertions.*;
import io.pjacoco.agent.mapping.TraceMapping;
import io.pjacoco.agent.observability.Metrics;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DistributedCoverageMergerTest {

    private static void writeExec(Path dir, String key, long classId, String cls, int probeIdx) throws Exception {
        Files.createDirectories(dir);
        boolean[] p = new boolean[] { false, false }; p[probeIdx] = true;
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(dir.resolve(key + ".exec")))) {
            ExecutionDataWriter w = new ExecutionDataWriter(os);
            w.visitSessionInfo(new SessionInfo(key, 0L, 0L));
            w.visitClassExecution(new ExecutionData(classId, cls, p));
        }
    }

    @Test
    void perServicePerTestIdReport(@TempDir Path tmp) throws Exception {            // REQ-015
        Path svcA = tmp.resolve("reservation"), svcB = tmp.resolve("ledger"), report = tmp.resolve("report");
        writeExec(svcA, "T1", 10L, "com/x/Reservation", 0);
        writeExec(svcB, "T2", 20L, "com/x/Ledger", 1);
        Map<String,Path> services = new LinkedHashMap<>();
        services.put("reservation", svcA); services.put("ledger", svcB);
        TraceMapping central = traceId -> "com.x.OrderIT#placesOrder";             // both traceIds -> one testId

        new DistributedCoverageMerger().merge(services, central, report, new Metrics());

        // service dimension preserved: each service has its own <testId>.exec
        assertTrue(Files.exists(report.resolve("reservation/com.x.OrderIT#placesOrder.exec")));
        assertTrue(Files.exists(report.resolve("ledger/com.x.OrderIT#placesOrder.exec")), "downstream service coverage present");
    }

    @Test
    void multipleTraceIdsOneTestIdWithinServiceMerged(@TempDir Path tmp) throws Exception {  // REQ-013 across service
        Path svc = tmp.resolve("svc"), report = tmp.resolve("report");
        writeExec(svc, "T1", 42L, "com/x/Svc", 0);
        writeExec(svc, "T2", 42L, "com/x/Svc", 1);                                 // same class, different probe
        Map<String,Path> services = new LinkedHashMap<>(); services.put("svc", svc);
        new DistributedCoverageMerger().merge(services, traceId -> "com.x.T#m", report, new Metrics());

        org.jacoco.core.tools.ExecFileLoader l = new org.jacoco.core.tools.ExecFileLoader();
        l.load(report.resolve("svc/com.x.T#m.exec").toFile());
        boolean[] probes = l.getExecutionDataStore().get(42L).getProbes();
        assertTrue(probes[0] && probes[1], "N:1 within a service OR-merged");
    }

    @Test
    void unmappedTraceIdFallsBackToRawPerService(@TempDir Path tmp) throws Exception {  // REQ-012 across service
        Path svc = tmp.resolve("svc"), report = tmp.resolve("report");
        writeExec(svc, "rawT", 7L, "com/x/A", 0);
        Metrics m = new Metrics();
        new DistributedCoverageMerger().merge(java.util.Collections.singletonMap("svc", svc),
                traceId -> null, report, m);
        assertTrue(Files.exists(report.resolve("svc/rawT.exec")));
        assertEquals(1L, m.unmappedTraceIds.get());
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :agent:test --tests '*DistributedCoverageMergerTest*'` → FAIL.
- [ ] **Step 3: 구현** — `DistributedCoverageMerger.merge`가 서비스마다 `reportDir.resolve(service)`를 outputDir로 C2 merge 호출:

```java
package io.pjacoco.agent.output;

import io.pjacoco.agent.mapping.TraceMapping;
import io.pjacoco.agent.observability.Metrics;
import java.nio.file.Path;
import java.util.Map;

/** Cross-service report-time merge (design §5.2-4): runs the single-service {@link TraceCoverageMerger}
 *  per service into {@code reportDir/<service>/}, applying ONE central traceId->testId map. The service
 *  dimension is preserved as a subdirectory so JaCoCo classIds from different services never collide. */
public final class DistributedCoverageMerger {

    private final TraceCoverageMerger one = new TraceCoverageMerger();

    public void merge(Map<String, Path> serviceDirs, TraceMapping central, Path reportDir, Metrics metrics)
            throws Exception {
        for (Map.Entry<String, Path> e : serviceDirs.entrySet()) {
            try {
                one.merge(e.getValue(), central, reportDir.resolve(e.getKey()), metrics);
            } catch (Throwable t) {
                if (metrics != null) metrics.swallowedExceptions.incrementAndGet();   // best-effort per service
                System.err.println("[pjacoco] distributed merge failed for service '" + e.getKey() + "': " + t);
            }
        }
    }
}
```

- [ ] **Step 4: 통과 확인** — PASS.
- [ ] **Step 5: 커밋** — `git commit -m "feat(agent): DistributedCoverageMerger service-axis cross-service merge (REQ-015)"`

---

## Task 3: DistributedCollector (공유 볼륨 수집 + drain-wait)

**REQ-IDs:** REQ-023

**Files:**
- Create: `agent/src/main/java/io/pjacoco/agent/output/DistributedCollector.java`
- Test: `agent/src/integrationTest/java/io/pjacoco/agent/it/DistributedCollectIT.java`

**Interfaces:**
- Consumes: `DistributedCoverageMerger`(T2), `TraceMapping`.
- Produces:
  - `interface Sleeper { void sleep(long millis) throws Exception; }`(주입; 실제는 `Thread.sleep` 래퍼. **`throws Exception`** — 테스트 hook가 checked 예외를 던질 수 있게).
  - `void DistributedCollector.collectAfterDrain(Path sharedVolume, TraceMapping central, Path reportDir, long drainWaitMillis, Sleeper sleeper, Metrics metrics)` — (1) `sleeper.sleep(drainWaitMillis)`로 비동기 다운스트림 flush 대기, (2) `sharedVolume`의 **하위 디렉터리만**(`Files.isDirectory`, `trace-map.properties`·`.`-hidden·stray 파일 제외) 서비스로 발견(`Map<service,dir>`), (3) `DistributedCoverageMerger.merge` 호출. sharedVolume 부재/빈 경우 빈 맵으로 no-op(warn).

- [ ] **Step 1: 실패 테스트 작성** — drain-wait 동안 늦은 다운스트림 `.exec`가 도착하고, 수집이 그것을 포함.

```java
package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.*;
import io.pjacoco.agent.mapping.TraceMapping;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.DistributedCollector;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** REQ-023: per-service .exec collected from a shared volume after a drain-wait, with a downstream
 *  service's .exec arriving DURING the drain window (simulating async Tram/CDC/Kafka lag). */
class DistributedCollectIT {

    private static void writeExec(Path dir, String key, long classId, String cls) throws Exception {
        Files.createDirectories(dir);
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(dir.resolve(key + ".exec")))) {
            ExecutionDataWriter w = new ExecutionDataWriter(os);
            w.visitSessionInfo(new SessionInfo(key, 0L, 0L));
            w.visitClassExecution(new ExecutionData(classId, cls, new boolean[] { true }));
        }
    }

    @Test
    void downstreamCollectedAfterDrain(@TempDir Path tmp) throws Exception {
        Path shared = tmp.resolve("shared"), report = tmp.resolve("report");
        Path upstream = shared.resolve("reservation"), downstream = shared.resolve("ledger");
        writeExec(upstream, "T", 10L, "com/x/Reservation");                       // upstream already flushed

        TraceMapping central = traceId -> "com.x.OrderIT#placesOrder";
        // Sleeper simulates the drain window; the downstream .exec "arrives" during the wait.
        DistributedCollector.Sleeper lateArrival = millis -> writeExec(downstream, "T", 20L, "com/x/Ledger");

        new DistributedCollector().collectAfterDrain(shared, central, report, 15000, lateArrival, new Metrics());

        assertTrue(Files.exists(report.resolve("reservation/com.x.OrderIT#placesOrder.exec")));
        assertTrue(Files.exists(report.resolve("ledger/com.x.OrderIT#placesOrder.exec")),
                "downstream .exec that arrived during drain-wait is collected (REQ-023)");
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :agent:integrationTest --tests '*DistributedCollectIT*'` → FAIL.
- [ ] **Step 3: 구현** — `DistributedCollector`: sleeper.sleep → `Files.newDirectoryStream(sharedVolume)`로 하위 디렉터리 수집 → `DistributedCoverageMerger.merge`. 전부 best-effort.
- [ ] **Step 4: 통과 확인** — PASS.
- [ ] **Step 5: 커밋** — `git commit -m "feat(agent): DistributedCollector shared-volume collect + drain-wait (REQ-023)"`

---

## Task 4: TraceMergeMain CLI (실행 진입점)

**REQ-IDs:** REQ-015(운영), C2/C3a followup

**Files:**
- Create: `agent/src/main/java/io/pjacoco/agent/output/TraceMergeMain.java`
- Test: `agent/src/test/java/io/pjacoco/agent/output/TraceMergeMainTest.java`

**Interfaces:**
- Produces: `static void TraceMergeMain.main(String[] args)`(종료코드로 `System.exit`) + 테스트 가능한 `static int run(String[] args)`(0=성공, 비-0=오류). 공통 args: `--map <props>`, `--report <dir>`, `--drain-wait-ms <N>`(**기본 15000**). **입력 모드 택1**:
  - `--shared <dir>`: 하위 디렉터리=서비스, `DistributedCollector.collectAfterDrain`(real `Thread.sleep` Sleeper, drain-wait 적용).
  - 반복 `--service-dir <serviceName>=<path>`: 명시 서비스 맵 구성(`=` 1회 split), `DistributedCoverageMerger.merge` 직접 호출(drain-wait 없음).
  - 두 모드 동시 지정 또는 둘 다 없음 → 비-0 + usage(stderr).

- [ ] **Step 1: 실패 테스트** — `--shared`(drain 0) + `--service-dir` 두 모드 각각.

```java
@Test
void runSharedMode(@TempDir Path tmp) throws Exception {
    // build shared/svc/<traceId>.exec + trace-map.properties(traceId=com.x.T#m), then:
    int code = TraceMergeMain.run(new String[]{
        "--shared", shared.toString(), "--map", mapFile.toString(),
        "--report", report.toString(), "--drain-wait-ms", "0" });   // unit: explicit 0 for determinism
    assertEquals(0, code);
    assertTrue(Files.exists(report.resolve("svc/com.x.T#m.exec")));
}
@Test
void runServiceDirMode(@TempDir Path tmp) throws Exception {
    int code = TraceMergeMain.run(new String[]{
        "--service-dir", "reservation=" + svcDir.toString(),
        "--map", mapFile.toString(), "--report", report.toString() });
    assertEquals(0, code);
    assertTrue(Files.exists(report.resolve("reservation/com.x.T#m.exec")));
}
@Test
void runRejectsNoInputMode(@TempDir Path tmp) {
    assertEquals(2, TraceMergeMain.run(new String[]{ "--map", "x", "--report", "y" }));  // neither mode
}
```

- [ ] **Step 2: 실패 확인** — FAIL.
- [ ] **Step 3: 구현** — arg 파싱(`--service-dir name=dir`는 `indexOf('=')`로 split, 반복 누적) → `TestIdMappingRegistry.loadFrom(map)` → `--shared`면 `collectAfterDrain`(real Sleeper=`Thread.sleep`), `--service-dir`면 `DistributedCoverageMerger.merge`. 모드 오류/누락 인자는 비-0 + stderr usage. 외부 예외(map 읽기 등)는 catch→비-0.
- [ ] **Step 4: 통과 확인** — PASS.
- [ ] **Step 5: 커밋** — `git commit -m "feat(agent): TraceMergeMain CLI entrypoint for distributed merge (REQ-015)"`

---

## Task 5: REQ-023 수용 — drain-wait 수집 E2E

**REQ-IDs:** REQ-023

**Files:**
- Test: `agent/src/integrationTest/java/io/pjacoco/agent/it/DistributedCollectIT.java`(T3에서 작성; 매트릭스 수용은 `downstreamCollectedAfterDrain`)

> **E2E 레벨(최고 실현가능):** REQ-023의 본질(drain-wait 후 늦은 다운스트림 `.exec` 무유실 수집)은 **수집 레이어**의 행위로, 주입 Sleeper로 비동기 도착을 결정론적으로 재현한다(T3). 실제 CDC/Kafka 지연을 가진 Docker 다중 서비스 수집은 T6 E2E가 부수적으로 입증(legacy-tram CDC). 따라서 REQ-023 매트릭스 수용 = `DistributedCollectIT#downstreamCollectedAfterDrain`(integration, 최고 실현가능 — Docker 수집은 T6에서 실측).

- [ ] **Step 1: 확인** — T3 `DistributedCollectIT#downstreamCollectedAfterDrain`이 green(이미 T3에서 작성·통과). 매트릭스 REQ-023 행을 이 테스트로 매핑(T7). 추가 작업 없으면 이 task는 T3로 충족됨을 기록.

---

## Task 6: REQ-015 수용 — 분산 Docker E2E (Brave + OTel)

**REQ-IDs:** REQ-015 (전제: GA-1, GA-2[T1], REQ-023[T3])

> **실현가능성:** Docker 가용(29.5.3)·환경 present. **진짜 분산 E2E를 실행**하되, 무겁고 환경 의존이므로 **`assumeTrue` 게이트**로 부재 시 skip. 게이트 env var(테스트 상단): Brave=`LEGACY_TRAM_ROOT`(기본 `~/github_graph-rag-test-generator/graph-rag/samples/legacy-tram`), OTel=`TAINTED_SPRING_ROOT`(기본 `~/github_tainted-spring/tainted-spring-platform`) + Docker 데몬 reachable. **위치:** `integrationTest` source set(repo에 `e2eTest` 없음), 패키지 `io.pjacoco.agent.it`. 기본 CI(env 미설정)에서는 skip되며 skip 사유를 로그.

**Files:**
- Test: `agent/src/integrationTest/java/io/pjacoco/agent/it/LegacyTramDistributedE2E.java`(Brave)
- Test: `agent/src/integrationTest/java/io/pjacoco/agent/it/TaintedSpringDistributedE2E.java`(OTel)

**공통 E2E 흐름(각 환경):**
1. pjacoco agent(shaded jar, `./gradlew :agent:shadowJar`) → 각 서비스에 주입: `JAVA_TOOL_OPTIONS=-javaagent:<pjacoco>=destfile=/shared/<service>,traceKeyAutoCreate=true,`**`traceIdleFlushMillis=5000,traceReaperIntervalMillis=2000,traceLateWriteGraceMillis=2000`** (reaper가 빨리 flush해 drain-wait 15s 안에 per-traceId `.exec` 산출 — **타이밍 필수**: idle 5s+grace 2s ≈ 7s < drain 15s). OTel 경로는 OTel javaagent 동반(설치 순서 GA-3) + `/shared` 호스트 볼륨 마운트(서비스별 `<service>` 하위).
2. docker-compose up(환경 오버레이 — agent 주입 + 공유 볼륨 추가본; tainted-spring 메모리 노트상 jar 경로 sed 필요할 수 있음).
3. 블랙박스 테스트 1건: **고정 traceId**(`traceparent: 00-<fixedTraceId>-<span>-01` 헤더, Brave는 B3 `X-B3-TraceId: <fixedTraceId>`)로 진입 요청 → 서비스 경계 넘어 다운스트림 도달.
4. 러너가 중앙 맵 생성: `TestIdMappingRegistry reg = new TestIdMappingRegistry(16); reg.register(fixedTraceId, "<FQCN#method>"); reg.writeTo(mapFile)`.
5. **drain-wait 15s** 후 `TraceMergeMain.run(--shared /shared --map mapFile --report <out> --drain-wait-ms 15000)`.
6. **단언:** `report/<downstream-service>/<FQCN#method>.exec` 존재·non-empty(다운스트림 귀속 — 현행 분산 미귀속에서 0이던 것이 채워짐).

- [ ] **Step 1: 실패 E2E 작성(red)** — Brave `LegacyTramDistributedE2E`: `@DisplayName("REQ-015: cross-service coverage merged for one test (Brave/legacy-tram)")`. 상단 `assumeTrue(dockerUp() && Files.isDirectory(legacyTramRoot))`. order-web→reservation→(Tram/Kafka/CDC)→ledger. drain 후 병합 → `report/reservation/<testId>.exec` **그리고 `report/ledger/<testId>.exec`** 존재. (다운스트림 미귀속이면 red.)
- [ ] **Step 2: red 확인** — 배선 전이면 FAIL; 환경 미가동이면 assumeTrue skip(사유 로그).
- [ ] **Step 3: 환경 기동 + 배선** — legacy-tram 오버레이(agent 주입 + 짧은 reaper 옵션 + 공유 볼륨)로 docker-compose up, 고정 traceId 블랙박스 호출, 중앙 맵 writeTo, `TraceMergeMain` 수집·병합. (helper: compose up/down, healthcheck 대기.)
- [ ] **Step 4: green 확인** — Brave: `report/ledger/<testId>.exec` 존재·non-empty. OTel `TaintedSpringDistributedE2E`: tainted-spring BFF→Kafka→다운스트림 동일 패턴(GA-2[T1] 경로 재확인). **바닥선: 최소 1개 환경(Brave 또는 OTel) 실측 green**; 나머지 환경이 기동 불가하면 assumeTrue skip + 사유 문서화(묵시적 green 금지).
- [ ] **Step 5: 커밋** — `git commit -m "test(e2e): cross-service per-test coverage merge, Brave + OTel (REQ-015)"`

> **타이밍 근거(리뷰 반영):** 기본 reaper idle 30s + grace 10s ≈ 40s > drain 15s이면 수집 시 `.exec` 부재로 red/flake. 따라서 E2E는 agent 옵션을 **idle 5s/interval 2s/grace 2s**로 낮춰 flush를 drain 안으로 보장한다(위 Step 1). (프로덕션 기본값은 그대로; E2E 전용 단축.)

> **불가 시 폴백(정직):** 특정 환경(예: tainted-spring 오버레이 jar 경로 깨짐, 메모리 노트)이 기동 불가하면, 그 환경 E2E는 `assumeTrue` skip하고 **다른 환경(가동 가능한 쪽)으로 REQ-015를 실측**하며, 미가동 환경은 "CI/전용 러너 검증"으로 문서화(skip 이유 명시 — 묵시적 green 금지). 최소 1개 환경(Brave 또는 OTel)에서 실측 green이 REQ-015 수용의 바닥선.

---

## Task 7: 문서 동기화 + 매트릭스 100% + 전체 regression (DoD)

**REQ-IDs:** REQ-015, REQ-023

**Files:**
- Modify: `docs/superpowers/requirements/...-requirements.md`(REQ-015/023 🟢, 실제 테스트명, 전체 매트릭스 100% 선언)
- Modify: `docs/superpowers/specs/...-design.md`(§5.2-4 DistributedCoverageMerger 구현 확정, §6.5 공유 볼륨·drain 15s 확정, §10 해소; §9 GA-2 PASS)
- Modify: `README.md`(분산: 공유 볼륨 + `TraceMergeMain` CLI 사용법 + drain-wait + 서비스별 리포트)

- [ ] **Step 1: 매트릭스 + REQ-023 Level 역전파** — REQ-023: **요구사항명세의 `검증 레벨: E2E black-box`를 `integration (최고 실현가능; Sleeper 주입 결정론 + Docker CDC는 T6 부수 실측)`로 갱신**(C2 REQ-012 강등과 동일 템플릿) → 수용 테스트 `DistributedCollectIT#downstreamCollectedAfterDrain` (+ T6 `LegacyTramDistributedE2E`를 REQ-023 Docker 실측 증거로 병기). REQ-015 → `LegacyTramDistributedE2E` + `TaintedSpringDistributedE2E`(E2E; skip 환경은 사유 명시) + `DistributedCoverageMergerTest`(병합 로직 단위 증거). 둘 다 🟢. **전체 매트릭스 100% 선언**: C1 12 + C2 4 + C3a 4 + C3b 2 = in-scope Must/Should 전부 🟢; 제외 Won't 2·deferred 1.
- [ ] **Step 2: design/§10** — DistributedCoverageMerger·DistributedCollector·TraceMergeMain·공유 볼륨·drain 15s 확정. **§6.4(b) 루트 scope-close 즉시 flush = C3b 범위 외(deferred)** 명시. §9 GA-2 PASS 기록.
- [ ] **Step 3: README** — 분산 수집/병합 절: 각 서비스에 agent(`destfile=/shared/<service>`, `traceKeyAutoCreate=true`) + 공유 볼륨, 러너가 중앙 맵 등록, `TraceMergeMain --shared /shared --map trace-map.properties --report <out>`로 drain-wait 수집→`report/<service>/<testId>.exec`.
- [ ] **Step 4: 전체 regression** — **`./gradlew check`**(unit `test` + `integrationTest` 포함; agent `check`는 integrationTest에 dependsOn) 또는 `./gradlew :agent:test :agent:integrationTest :testkit-*:test :gradle-plugin:test`. PASS. **Docker E2E(`LegacyTramDistributedE2E`/`TaintedSpringDistributedE2E`)는 env var 게이트 — 실행 시 green, env 부재 시 assumeTrue skip(사유 명시); 어느 환경 실측 green/어느 환경 skip을 보고에 기록**(묵시적 green 금지).
- [ ] **Step 5: 커밋** — `git commit -m "docs(c3b): distributed merge matrix green + design/README sync (REQ-015, REQ-023); requirements 100%"`

---

## Self-Review (작성자 점검 완료)

**1. Spec coverage:** REQ-015(T2 서비스축 병합 + T4 CLI + T6 Docker E2E) · REQ-023(T3 drain-wait collector + T5 수용 + T6 CDC 실측). GA-2(T1). 불변(REQ-001/002/003)은 C3b가 핫패스 무변경(오프라인)이라 자연 충족.

**2. Placeholder scan:** 핵심 코드(merger/collector) 완전 수록. CLI(T4)·E2E(T6)는 환경 의존이라 흐름+단언을 구체화하되 외부 오버레이 세부는 환경별 가변(정직하게 "최소 1환경 실측 green" 바닥선 + skip 사유 명시 규약). E2E는 본질적으로 환경 결합이므로 plan은 계약(무엇을 단언)을 고정.

**3. Type consistency:** `DistributedCoverageMerger.merge(Map<String,Path>, TraceMapping, Path, Metrics):void`, `DistributedCollector.{Sleeper, collectAfterDrain(Path,TraceMapping,Path,long,Sleeper,Metrics):void}`, `TraceMergeMain.{main(String[]):void, run(String[]):int}`, C2 `TraceCoverageMerger.merge(Path,TraceMapping,Path,Metrics)` 재사용 — 일관.

**4. 핫패스:** C3b는 에이전트 store/probe에 코드 0 추가(전부 오프라인 수집/병합). REQ-001 자명 충족.

---

## 3-벤더 design-doc 리뷰 반영 (2026-06-20)

Claude Sonnet(approved_with_conditions) + Gemini 3.5 Flash(needs_revision) + Cursor(needs_revision). 작성자 판정(수용/기각) — **전 findings 수용, 기각 없음**:

**수용해 반영:**
- **`e2eTest` source set 부재(3-way Critical):** repo는 `integrationTest`(@Tag)·`otelE2e`·`e2eJakarta`만 존재 → Docker E2E를 `integrationTest`(pkg `io.pjacoco.agent.it`)에 `assumeTrue(env)` 게이트로 배치(build.gradle 변경 불요).
- **Sleeper checked-exception(Gemini Critical):** `interface Sleeper { void sleep(long) throws Exception; }`(테스트 hook가 Exception 던짐).
- **drain-wait 기본 0 vs 15000(Cursor/Gemini Critical):** 15000으로 통일; 단위 테스트만 명시적 `0`.
- **drain 15s < reaper idle 30s+grace(Gemini/Cursor important):** E2E agent 옵션을 idle 5s/interval 2s/grace 2s로 단축(flush가 drain 안에). Task 6 타이밍 step 의무화.
- **Collector 시그니처 불일치(3-way important):** `collectAfterDrain(Path,TraceMapping,Path,long,Sleeper,Metrics)`로 통일, `LongSupplier clock` 제거(File Structure 갱신).
- **`--service-dir name=dir` 형식(3-way):** normative 고정 + `--shared`와 택1 분기 + 테스트 3건(shared/service-dir/모드오류).
- **REQ-023 Level 역전파(Claude/Cursor important):** T7에서 requirements REQ-023 Level을 "integration(최고 실현가능)+Docker T6 실측"으로 갱신(C2 REQ-012 템플릿).
- **§6.4(b) scope-close flush defer(Cursor important):** C3b 범위 외 명시(T7 design 갱신).
- **T7 regression `./gradlew check`/integrationTest(Cursor/Gemini important):** DistributedCollectIT가 integrationTest이므로 `check`로 수정.
- **E2E env 게이트(Cursor I10) + 중앙맵 구성(Claude I5):** `LEGACY_TRAM_ROOT`/`TAINTED_SPRING_ROOT` + Docker, 중앙맵은 `TestIdMappingRegistry.register+writeTo`로 구체화.
- **collector dir 필터(Cursor I9) + merge 오류 로깅(Gemini I6):** isDirectory + trace-map.properties/hidden 제외; per-service 실패 stderr 로그.

**재리뷰:** 변경은 task 내부 구체화·시그니처 통일·E2E 경로/타이밍 보정으로 구조 동일 → 부분 재리뷰 불요. T7에서 design §6.4/§10·requirements REQ-023 갱신분만 design-doc 리뷰 재실행.
