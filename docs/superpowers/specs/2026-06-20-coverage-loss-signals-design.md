# 설계: in-process per-test 귀속 손실의 가시화 (coverage-loss signals) — phase 1 (pjacoco)

상태: 리뷰용 초안 (3-벤더 design-doc 리뷰 반영 rev1)
작성일: 2026-06-20
브랜치: `feat-coverage-loss-signals`
대상 repo: pjacoco (parallel-per-test-coverage)
연계: phase 2(TIA, 별도 repo `test-impact-analysis`)가 본 phase가 정의하는 **신호 계약**을 소비한다.

## 1. 배경 & 문제

소비자(graph-rag-test-generator)의 `samples/order-service`에 TIA in-process 수집을 적용했더니
20개 테스트 중 7개가 `0 커버`, 13개는 비어있지 않았다. 그러나 비어있지 않은 13개를 까보니
실제로 잡힌 라인은 **테스트 스레드에서 시딩한 `new User(...)`·`new Order(...)` 엔티티 생성자뿐**이고,
정작 HTTP 요청이 실행한 컨트롤러·서비스·시큐리티 코드는 한 줄도 없었다. 원인:

- in-process 수집은 `PjacocoInProcessExtension`이 **테스트 스레드**의 `CoverageContext`(ThreadLocal)만
  set 한다.
- 대상 모듈은 `@SpringBootTest(webEnvironment=RANDOM_PORT)` + `TestRestTemplate`이라, 프로덕션 코드가
  **Tomcat 워커 스레드**에서 실행된다.
- 워커 스레드는 `CoverageContext.get() == null` → `CoverageBridge.recordCoverage`의
  `if (store == null) return;`에서 **프로브가 카운터도 없이 조용히 버려진다**.

즉 in-process는 "프로세스가 같은 JVM"이라는 이유로 선택됐지만, **프로덕션 코드가 테스트 스레드가
아닌 워커 스레드에서 도는** 토폴로지에는 부적합하다. 이런 경우는 out-of-process **baggage 모델**
(`ServletAdvice`가 요청의 `test.id`로 워커 스레드에 `CoverageContext.set`)이 이미 정답을 제공한다.

**핵심 결함은 "잘못 골랐다"가 아니라 "잘못 골라도 도구가 조용히 그럴듯한 데이터를 낸다"는 침묵
실패다.** 본 phase는 **손실을 신호로 전환**한다(런타임 + 데이터). 손실을 *고치는* 것(=워커 스레드
캡처)이 아니라 — 그건 out-of-process baggage가 이미 함 — **손실을 보이게** 만드는 것이 목표다.

## 2. 목표 & 불변식

- **목표**: in-process 수집 중 발생하는 per-test 귀속 손실을 (a) 런타임 경고/카운터, (b) per-test
  데이터 플래그로 **항상 가시화**한다.
- **불변식 — 침묵 손실 0**: 활성 컨텍스트 없는 스레드에서 프로브가 1개라도 버려지면, 그 사실이
  반드시 (a) `Metrics`에 누적되고 (b) shutdown summary에 노출되며 (c) 드롭 시점에 활성 in-process
  test가 있었다면 그 test(들)의 sidecar에 표시된다. **빈-store 가드가 이 신호를 삼키지 않는다**(§4 I1).
- **불변식 — 오탐 0(false positive)**: 프로덕션 코드가 **테스트 스레드에서** 실행되는 정상 in-process
  (직접 호출/MockMvc/`webEnvironment=MOCK`/순수 단위·통합)은 어떤 per-test 플래그도, servlet WARN도
  내지 않는다.
- **불변식 — hot-path 무해**: 정상 수집(손실 0)에서 `recordCoverage`의 추가 비용은 단일 atomic 분기
  1회 이하. 귀속용 active-set 조회 비용은 **이미 잘못된** 경로(`store==null`)에서만 발생.
- **불변식 — 앱 비파괴**: 에이전트는 어떤 경우에도 애플리케이션 스레드에 예외를 던지지 않는다
  (기존 `recordCoverage`/`ServletAdvice`의 swallow 규약 유지).
- **불변식 — 후방호환**: 기존 `TestStore` 3-인자 생성자·`ExecWriter`/`Json` 호출부·sidecar 포맷을
  깨지 않는다(신규 필드는 setter/조건부 emit, 기존 소비자는 무시 가능).
- **비목표(phase 2, TIA repo)**: `tia convert`의 플래그 소비/`--fail-on-empty`, 수집 하니스
  fail-fast, GETTING-STARTED/SKILL 결정 체크리스트. 본 phase는 그 **신호를 생산**만 한다.
- **비목표**: in-process가 워커 스레드를 캡처하게 만드는 것(= out-of-process baggage 재구현);
  WebSocket/STOMP 전용 fixture(phase 1은 @Async/Executor로 cross-thread 증명, WS는 추후).

## 3. 아키텍처 — 신호 계약 (phase 1↔2 경계)

```
[pjacoco 에이전트 — in-process 수집]
  ① ServletAdvice.activate: key==null AND CoverageContext.get()==null → missingTestIdInbound++ + 1회성 WARN
  ② CoverageBridge.recordCoverage: store==null → droppedNoContext++  +  active-set 귀속(아래 DATA)
  DATA: 드롭 시 active in-process store(들)에 직접 누적(TestStore.droppedProbes) → flush가 sidecar로 직렬화
        └ shutdown: Metrics.summary()에 missingTestIdInbound/droppedNoContext/unattributedDrops 누계 1줄
        ▼ (계약 = 산출물 contract; 신규 필드는 조건부·additive)
  <testId>.json sidecar:  { "result": ..., "incompleteAttribution": true, "droppedProbes": N, "attribution": "exact|conservative" }
        ▼
[phase 2 — TIA tia convert]  이 필드를 testwise.json tests[]로 승격 → index → tia.db (phase 2 범위)
```

런타임 신호(①②+summary)는 **사람**이 보는 것, 데이터 신호(DATA)는 **도구/에이전트**가 보는 것 —
둘 다 필요하다(로그를 안 읽는 소비자도 데이터로 손실을 본다).

## 4. 컴포넌트

### ① servlet inbound WARN — `agent/.../inbound/servlet/ServletAdvice`
`activate(request)`에서 트레이서·baggage 모두로 `key`를 못 풀면(`key == null`) 워커 스레드 프로브가
드롭된다. 단, **MockMvc/MOCK servlet 디스패치는 테스트 스레드에서 돌며 이미 `CoverageContext`가
set 돼 있다**(InProcessExtension이 beforeEach에 set). 따라서 오탐을 막기 위해:
- 조건: `key == null` **AND `CoverageContext.get() == null`** (이 스레드에 활성 컨텍스트가 없을 때만).
  → MockMvc(테스트 스레드, 컨텍스트 있음)는 카운트/경고 안 됨(R6 보장). RANDOM_PORT 워커 스레드
  (컨텍스트 없음)만 잡힘.
- 동작: `Metrics.missingTestIdInbound`(신규 AtomicLong) 증가 + **JVM당 1회 WARN**.
  - 정확히-1회: `ServletAdvice`에 `static final AtomicBoolean MISSING_ID_WARNED`를 두고
    `compareAndSet(false,true)` 성공 시에만 로그(volatile boolean 아님 — race-safe).
  - 로깅: `ServletAdvice`에 `public static volatile AgentLog log`를 추가하고
    `ServletInboundActivator`에서 바인드(기존 `registry`/`metrics` static과 동형). null이면 `System.err`.
- **메시지(중립적 표현)** — 정상 out-of-process의 무태그 인프라/헬스 요청에도 발화할 수 있으므로
  단정적 "DROPPED"/"use out-of-process"를 피한다:
  `[pjacoco] WARN inbound HTTP request had no test.id (no tracer scope, no 'baggage: test.id') and no active in-process context; its probes are not attributed to any test. If this is a black-box HTTP test (e.g. SpringBootTest RANDOM_PORT + TestRestTemplate/RestAssured), in-process attributes only the test thread — use the out-of-process baggage model (test.id baggage). (logged once; see shutdown summary for totals)`

### ② orphan-probe 카운터 — `agent/.../probe/CoverageBridge.recordCoverage`
```java
TestStore store = CoverageContext.get();
if (store == null) {
    Metrics m = metrics;
    if (m != null) m.droppedNoContext.incrementAndGet();
    DropAttributor a = attributor;            // bound by Bootstrap (like bindMetrics)
    if (a != null) a.attribute();             // active-set 귀속 — broken-path 한정
    return;
}
```
- servlet이 못 보는 **WebSocket/STOMP 브로커 스레드·@Async·ExecutorService·스케줄러**까지 포착하는
  일반 그물.
- 비용: 정상 수집에선 이 분기에 **도달하지 않으므로** 추가 비용 0. 손실 경로에서만 atomic 증가 +
  귀속 조회.
- **`includes` 필터가 배경 노이즈를 1차로 차단**: 에이전트는 프로덕션 `includes` 패키지만 계측하므로
  netty 이벤트루프·actuator·GC 보조 스레드 등 인프라 스레드는 애초에 프로브를 만들지 않아 드롭도
  없다(§7 노이즈 항목 참조).

### DATA 플래그 — per-test `incompleteAttribution` (active-set 직접 귀속)
드롭은 워커 스레드에서 발생해 그 시점엔 `store==null`이라 어느 test인지 직접은 모른다. **전역 델타
대신 active in-process store 집합에 직접 누적**한다(전역 델타의 inter-test 누수·`stores.remove` 순서
문제·배경 노이즈 오귀속을 회피):

- 신규 컴포넌트 `DropAttributor`(`TestStoreRegistry` 참조를 보유): `attribute()`는 active store 스냅샷을
  보고 귀속한다. **바인딩 계약(리뷰 I1)**: `TestStoreRegistry.stores`는 `private final`이므로 신규
  public `Collection<TestStore> activeSnapshot()`(반환=`new ArrayList<>(stores.values())`, ConcurrentHashMap
  의 thread-safe 순회로 락 불요, 반환 후 안전 순회)를 추가한다. `DropAttributor`는 `new DropAttributor(registry)`로
  레지스트리를 직접 참조하고, `Bootstrap.premain`이 `CoverageBridge.bindMetrics(metrics)` **직후·**
  `ProbeInstrumentation.install(...)` **직전**에 `CoverageBridge.bindAttributor(new DropAttributor(registry))`로
  바인드한다(첫 프로브가 미바인드로 누락되는 순서 모호 제거 — 리뷰 I4). `attribute()`는 스냅샷을 보고:
  - size == 1 → 그 store의 `droppedProbes` +1 (귀속 정확).
  - size > 1 → active store 전부 `droppedProbes` +1 **및 각 store의 `attributionConservative=true`**
    (in-JVM 병렬: 어느 test가 유발했는지 모호 → 보수적 과표기. 엄격 posture).
  - size == 0 → 어느 test에도 못 붙임 → `Metrics.unattributedDrops`(신규)만 증가(per-test sidecar
    없음). 테스트 윈도우 밖 배경 드롭·strict-mode 미등록 key 거부 경로가 여기 해당(§7).
- `TestStore`(후방호환): 3-인자 생성자 유지, **volatile/atomic 필드 + setter 추가** —
  `AtomicLong droppedProbes`, `volatile boolean attributionConservative`. 워커 스레드에서 증가하므로
  thread-safe.
- 직렬화: per-test 카운트가 **store에 얹혀 있으므로** 단일 funnel `TestStoreRegistry.flush(store,...)`가
  모든 종료 경로(stop/`dumpRemainingAsPartial`/`enforceCap` 축출)에서 자동으로 sidecar에 쓴다 —
  delta 계산 불필요, partial/cap 경로도 자동 커버(리뷰 I7/Gemini I4 해소).
- `attribution` 값: `attributionConservative ? "conservative" : "exact"`. `incompleteAttribution`는
  `droppedProbes > 0`일 때만 emit. conservative의 `droppedProbes`는 store별 누적 상한치(여러 동시 test에
  중복 계상될 수 있는 **상한 추정**임을 phase 2/문서가 인지).

### 빈-store 가드 수정 — `agent/.../api/CoverageControl.deactivate` (리뷰 I1, 모든 벤더)
현재: `if (store != null && store.classCount() == 0) reg.discard(testId);` → 테스트 스레드가 한 줄도
계측 안 됐고(=워커 스레드에만 커버리지) **드롭만 있는** R4 케이스가 flush 없이 버려져 sidecar가 안
써진다 → 침묵 손실. 수정:
```java
if (store != null && store.classCount() == 0 && store.droppedProbes() == 0) {
    reg.discard(testId);                 // 진짜 빈 store만 폐기
} else {
    reg.stop(testId, result);            // 드롭 신호가 있으면 flush(빈 .exec + 플래그 sidecar)
}
```
`ExecWriter`/`tia convert`는 빈 `.exec`(0 커버 라인)를 이미 허용한다(phase 2의 `--fail-on-empty`가
이 케이스를 잡는다).

### sidecar 직렬화 — `agent/.../output/ExecWriter` + `output/Json`
- `Json`에 `put(String key, boolean value)` 추가(raw `true`/`false` literal). 기존 String/long put 유지.
- `ExecWriter.write(...)`가 `TestStore`에서 `droppedProbes`/`attributionConservative`를 읽어
  `incompleteAttribution`(true일 때만)·`droppedProbes`·`attribution`을 sidecar에 emit. 시그니처는
  store에서 읽으므로 **파라미터 추가 불필요**(후방호환).

### shutdown summary — `agent/.../observability/Metrics.summary()`
`summary()`에 기존 명명 규칙대로 `missingTestIdInbound=<n> droppedNoContext=<n> unattributedDrops=<n>`
추가(약어 금지 — `fallbackActivations=` 등과 일관, MetricsTest 파싱 호환).

## 5. 수용/E2E 테스트 (이 phase의 정의된 done)

**하니스 분리(리뷰 Cursor I1):** `agent/build.gradle.kts`의 `integrationTest`는 `@Tag("e2e")`를
제외하고 ByteBuddy 인스트루먼트로 돌며, `e2eTest`만 실제 `-javaagent`를 붙인다. 따라서:
- **메커니즘 IT**(`agent/src/integrationTest`, `ProbeRoutingIT`/`BraveScopeWeaveIT` 패턴 —
  `ByteBuddyAgent.install()` + 수동 `CoverageContext`/Metrics 바인드): R1·R2·R3·R4·R6.
- **실-에이전트 E2E**(`@Tag("e2e")`, `e2eTest` 태스크, `SpecAcceptanceE2E` 패턴, 실 HTTP): R7.
- **병렬 in-process**(R5): 기존 `:gradle-plugin` `PjacocoInProcessFunctionalTest`(실 `-javaagent`,
  JUnit 병렬 소비자 빌드)에 보수 귀속 검증을 추가하거나 agent에 병렬 IT를 신설.

검증 동작을 `@DisplayName`에 명시한다.

1. **R1 servlet 무-baggage(컨텍스트 없음) → WARN+카운터** [IT] (리뷰 I3): `SpecAcceptanceE2E`(e2eTest)는
   에이전트 JVM 내부 `Metrics`를 읽을 수 없으므로(`.exec`/`.json`·HTTP control만 접근), R1은 IT로 둔다 —
   `ByteBuddyAgent.install()`로 servlet advice 위빙 + fake `HttpServlet` + `CoverageBridge.bindMetrics(testMetrics)`
   직접 단언(`BraveScopeWeaveIT`/`UnmappedTraceReportIT` 패턴). baggage 없는 요청을 컨텍스트 없는 스레드에서
   → `missingTestIdInbound>0` + WARN(AtomicBoolean) 1회. (정상 baggage 요청은 무신호.)
2. **R2 워커/비-테스트 스레드 드롭 → orphan 카운터** [IT]: 컨텍스트 없는 스레드가 프로덕션 라인 실행
   → `droppedNoContext>0`. (Metrics는 `CoverageBridge.bindMetrics(testMetrics)`로 테스트가 주입 —
   `UnmappedTraceReportIT` 패턴.)
3. **R3 비-servlet cross-thread** [IT]: @Async/ExecutorService 실행(WS는 phase 1 비목표) →
   `droppedNoContext>0`. servlet WARN으로는 안 잡히는 케이스 포함.
4. **R4 데이터 플래그(직렬, 빈 test-thread)** [IT]: 직렬 in-process에서 cross-thread만 실행(테스트
   스레드 classCount=0)한 test → 빈-store 가드가 폐기하지 않고, `<testId>.json`에
   `incompleteAttribution=true, attribution="exact"`. (sidecar는 temp outputDir에서 읽어 단언 —
   `SpecAcceptanceE2E` 패턴.)
5. **R5 데이터 플래그(병렬)** [gradle-plugin functional / 병렬 IT]: in-JVM 병렬에서 동시 active test에
   `attribution="conservative"` 보수 표기, 손실 누락 0.
6. **R6 오탐 0(음성)** [IT]: 프로덕션 코드가 **테스트 스레드에서** 실행(직접 호출 / MockMvc·MOCK
   servlet 디스패치 — 컨텍스트 set 상태) → `missingTestIdInbound=0`, `droppedNoContext=0`, 어떤
   sidecar에도 `incompleteAttribution` 없음.
7. **R7 회귀 무손상** [전체]: 기존 out-of-process baggage `:agent:e2eTest`·in-process 단위 수집·
   `ExecWriterTest` green, 산출물 포맷 후방호환(기존 소비자가 새 필드 무시해도 동작). **`MetricsTest`는
   신규 카운터(`missingTestIdInbound=`/`droppedNoContext=`/`unattributedDrops=`) 단언으로 확장**한다 —
   기존 contains-check는 오타를 못 잡으므로 명시 단언 필요(리뷰 I5).

## 6. 변경 컴포넌트 요약

| seam | 변경 |
|---|---|
| `observability/Metrics` | `missingTestIdInbound`·`droppedNoContext`·`unattributedDrops` 추가; `summary()` 노출 |
| `probe/CoverageBridge` | `store==null` 분기 `droppedNoContext++` + `attributor.attribute()`; `bindAttributor` static |
| (신규) `probe/DropAttributor` | `TestStoreRegistry` 참조 보유, `activeSnapshot()` 귀속(size 1=exact / >1=conservative / 0=unattributed) |
| `inbound/servlet/ServletAdvice` | `key==null && context==null` 게이트 → `missingTestIdInbound++` + 1회 WARN(AtomicBoolean); `static AgentLog log` |
| `inbound/servlet/ServletInboundActivator` | `ServletAdvice.log` 바인드 |
| `api/CoverageControl` | `deactivate` 빈-store 가드에 `&& droppedProbes()==0` (드롭 있으면 flush) |
| `store/TestStore` | `AtomicLong droppedProbes` + `volatile boolean attributionConservative` + setter/getter (3-인자 생성자 유지) |
| `store/TestStoreRegistry` | 신규 public `Collection<TestStore> activeSnapshot()` (`new ArrayList<>(stores.values())`) |
| `agent/Bootstrap` | `premain`에서 `bindMetrics` 직후·`ProbeInstrumentation.install` 직전 `bindAttributor(new DropAttributor(registry))` |
| `output/Json` | `put(String, boolean)` 오버로드 |
| `output/ExecWriter` | sidecar에 `incompleteAttribution`/`droppedProbes`/`attribution`(store에서 읽어 조건부 emit) |
| `agent/src/integrationTest`, `e2eTest`, `gradle-plugin` | R1~R7 수용 테스트 + fixture(워커/@Async, in-thread 음성) |

## 7. 리스크 & 엣지

- **hot-path 비용**: `droppedNoContext++`·귀속 조회는 손실 경로에서만. order-service처럼 드롭이
  대량이면 요청당 수십~수백 회 active-set 스냅샷이 날 수 있음 → active 수가 작아(직렬 1) 무해하나,
  병렬 대량 손실 시 O(drops×active). 필요 시 클래스당 1회 샘플링으로 최적화(현재 비목표).
- **배경 스레드 노이즈**: `droppedNoContext`는 전역이라 테스트 윈도우 밖 드롭도 누계에 포함될 수
  있다. 단 (a) `includes`가 프로덕션 패키지로 한정해 인프라 스레드 프로브 자체가 거의 없고,
  (b) per-test 귀속은 **active store가 있을 때만** 붙으며 active 0이면 `unattributedDrops`로 분리되어
  per-test 오탐이 안 난다. 전역 카운터>0이어도 의미 신호는 per-test `incompleteAttribution`임을
  문서화.
- **단일 active 중 배경 드롭 오귀속**: active=1인 동안 무관한 배경 스레드가 프로덕션 라인을 드롭하면
  그 test에 exact로 붙을 수 있음(드묾, includes로 완화). 보수적 false-positive로 수용(엄격 posture).
- **strict-mode 미등록 key 드롭**: `forCoverageKey`가 strict에서 null(미등록) → 컨텍스트 미설정 →
  워커 드롭은 `droppedNoContext`에 잡히나 store가 없어 active 0 → `unattributedDrops`. 정상 동작.
- **WARN 노이즈**: 정상 out-of-process의 무태그 인프라/헬스 요청도 컨텍스트 없는 워커에서 돌면 1회
  WARN을 유발할 수 있음 → 메시지를 중립적으로(§4①), JVM당 1회로 제한.
- **droppedProbes late-write 윈도우(리뷰 I2)**: `stop()`이 `stores.remove` 후 `flush`로 sidecar를
  직렬화하는 사이, 그 store 참조를 이미 잡은 워커 스레드의 `attribute()`가 `droppedProbes`를 추가
  증가시킬 수 있다. AtomicLong이라 증가 자체는 안전하나, flush 시점 read와 늦은 증가 사이엔
  happens-before가 없다 → sidecar가 그 좁은 윈도우의 드롭만큼 **과소 보고**될 수 있음. 침묵 손실이
  아니라 **보수적 하한(at-least N)** 방향이므로 phase 1에서 수용(`incompleteAttribution` 플래그 자체는
  영향 없음 — 1개라도 증가했으면 flush 전이면 true). 정밀화(flush 직전 재read)는 비목표.
- **계약 변경**: sidecar 신규 필드는 additive·조건부, `TestStore` 생성자 불변, `Json`/`ExecWriter`
  기존 호출 무변 → 기존 `tia convert`(필드 무시)와 후방호환. phase 2가 이 필드를 소비.
