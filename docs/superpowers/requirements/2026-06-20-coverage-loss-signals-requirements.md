# in-process per-test 귀속 손실 가시화 요구사항명세 (phase 1, pjacoco)

> 출처(design spec): docs/superpowers/specs/2026-06-20-coverage-loss-signals-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 green).
> **REQ-ID 네임스페이스(중요)**: 같은 repo의 기존 요구사항·테스트가 `REQ-003/005/006/007` 등을 다른
> 의미로 이미 사용한다(2026-06-19 trace-context 요구사항, `BraveScopeWeaveIT`/`TracerAbsentFallbackIT`의
> `@DisplayName("REQ-00x")`). 충돌을 피하려 본 feature는 **`CLS-REQ-00X`**(Coverage-Loss-Signals) 접두를
> 쓰고, 수용 테스트도 `@DisplayName("CLS-REQ-00X: …")`로 참조한다.
> 검증 레벨 주: pjacoco 최고 실현 out-of-process 레벨은 `:agent:e2eTest`(실 `-javaagent`)이나, 런타임
> 카운터(`Metrics`)는 에이전트 JVM 외부에서 못 읽어(`.exec`/`.json`·HTTP control만 노출) 메커니즘
> 검증은 `integrationTest`(ByteBuddy + `bindMetrics`/test `AgentLog` 직접 단언)가 최고 실현 레벨이다
> (design §5 하니스 분리). 회귀는 `e2eTest`/`e2eJakartaTest`/`e2eCondyTest`까지 포함한다.

## 요구사항 목록

### CLS-REQ-001 — servlet 무-baggage·무컨텍스트(수집 윈도우 내) 인바운드의 손실 신호
- 유형: Functional
- 우선순위: Must
- 설명: in-process 수집 중 HTTP 인바운드 요청이 test.id를 못 풀고(트레이서·baggage 없음) 그 스레드에
  활성 `CoverageContext`도 없으며 **레지스트리에 active store가 있을 때**, 손실을 카운터와 1회성 경고로
  가시화한다(active store 게이트로 startup 헬스체크 노이즈 제거 — design §4①).
- 수용기준:
  - Given active store가 ≥1 등록된 수집 윈도우, When baggage 없는 요청이 컨텍스트 없는 워커 스레드에서
    처리됨, Then `Metrics.missingTestIdInbound`가 증가하고 JVM당 정확히 1회 WARN이 로그된다(이후 동일
    요청은 추가 로그 없음 — AtomicBoolean 1-shot).
  - Given 정상 baggage(`test.id`) 요청, When 처리됨, Then `missingTestIdInbound`는 증가하지 않는다.
  - Given active store가 없는 startup 시점, When baggage 없는 인프라/헬스 요청, Then 증가·경고 없음.
- 검증 방식: test `AgentLog`를 `ServletAdvice.log`에 바인드해 WARN 캡처 + `bindMetrics`로 카운터 단언
  (System.err 캡처 금지 — 취약).
- 검증 레벨: integration (ByteBuddy servlet weave + fake `HttpServlet`)

### CLS-REQ-002 — 무컨텍스트 스레드 프로브 드롭 카운트 + 미귀속 분류
- 유형: Functional
- 우선순위: Must
- 설명: `CoverageBridge.recordCoverage`가 `store==null`로 버리는 프로브를 전역 `droppedNoContext`로
  집계하고, 드롭 시점에 active store가 없으면(어느 test에도 못 붙음) `unattributedDrops`로도 분류한다
  (per-test sidecar 미생성).
- 수용기준:
  - Given 계측된 프로덕션 클래스, When 활성 `CoverageContext` 없는 스레드가 그 라인 실행, Then
    `Metrics.droppedNoContext`가 증가한다.
  - Given active store가 0인 상태, When 무컨텍스트 스레드 드롭 발생, Then `droppedNoContext`와
    `Metrics.unattributedDrops`가 함께 증가하고, 어떤 per-test sidecar에도 플래그가 생기지 않는다.
  - Given 활성 컨텍스트 있는(테스트) 스레드 실행, When 라인 실행, Then `droppedNoContext` 미증가,
    커버리지 정상 귀속.
- 검증 레벨: integration

### CLS-REQ-003 — servlet 아닌 cross-thread 손실 포착
- 유형: Functional
- 우선순위: Must
- 설명: servlet 인바운드가 아닌 cross-thread 위임(@Async/ExecutorService 등) 손실도 ②의 일반 그물
  (`droppedNoContext`)에 포착된다.
- 수용기준:
  - Given active in-process test, When 작업을 자식 스레드/스레드풀로 위임해 프로덕션 라인이 그 스레드에서
    실행, Then `droppedNoContext`가 증가한다(servlet WARN 경로와 무관).
- 검증 레벨: integration (@Async/Executor fixture)

### CLS-REQ-004 — per-test 데이터 플래그 (직렬, 빈 test-thread 포함, race-safe)
- 유형: Functional
- 우선순위: Must
- 설명: 직렬 in-process에서 cross-thread 손실이 있는 test는, 테스트 스레드가 한 줄도 계측 안 됐어도
  (classCount=0) 빈-store 가드에 폐기되지 않고 sidecar에 손실을 표기한다. 폐기/flush 결정은
  `TestStoreRegistry`의 synchronized 메서드에서 store 제거 후 `droppedProbes`를 재read해 결정한다
  (check-then-act race 회피 — design §4 stopUnlessEmpty).
- 수용기준:
  - Given 직렬 in-process에서 한 test가 cross-thread로만 프로덕션 코드 실행(test-thread classCount=0,
    droppedProbes>0), When deactivate, Then `<testId>.json`이 기록되며(폐기 안 됨)
    `incompleteAttribution=true`, `attribution="exact"`, `droppedProbes>0`을 포함한다.
  - Given classCount=0 **및** droppedProbes=0인 진짜 빈 store, When deactivate, Then 폐기되어 sidecar
    없음(기존 동작 보존).
- 검증 레벨: integration

### CLS-REQ-005 — per-test 데이터 플래그 (병렬, 보수 귀속)
- 유형: Functional
- 우선순위: Must (사용자 지시: 병렬 포함)
- 설명: in-JVM 병렬 수집에서 드롭 시점에 동시 active test가 여럿이면, 손실을 누락 없이 표기하되 귀속
  모호를 보수적으로 처리한다.
- 수용기준:
  - Given in-JVM 병렬 in-process(동시 active test ≥2), When 컨텍스트 없는 스레드에서 드롭 발생, Then
    드롭 시점 동시 active test들의 sidecar가 `incompleteAttribution=true`, `attribution="conservative"`,
    `droppedProbes>0`(store별 누적 상한)으로 표기되고, 어떤 손실도 무표기로 사라지지 않는다.
- 검증 레벨: integration (신규 `IncompleteAttributionParallelIT` — ByteBuddy + 동시 active store + 워커
  드롭. `PjacocoInProcessFunctionalTest`는 테스트 스레드 직접 호출이라 워커 드롭 시나리오가 없어 채택
  안 함 — 리뷰 확정.)

### CLS-REQ-006 — 오탐 0 (in-thread/MockMvc는 무신호)
- 유형: Non-functional
- 우선순위: Must
- 설명: 프로덕션 코드가 테스트 스레드에서 실행되는 정상 in-process는 어떤 손실 신호도 내지 않는다.
- 수용기준:
  - Given in-process에서 프로덕션 코드를 테스트 스레드에서 직접 호출하거나, 컨텍스트가 set된 테스트
    스레드에서 servlet dispatch(MockMvc 등가)로 실행, When 테스트 수행, Then `missingTestIdInbound=0`,
    `droppedNoContext=0`, 어떤 sidecar에도 `incompleteAttribution`이 없다.
- 검증 방식: integrationTest classpath에 Spring/MockMvc 의존이 없으므로, MockMvc 실호출 대신
  **surrogate**(테스트 스레드에 `CoverageContext.set` 후 `ServletAdvice.activate` 호출 = 디스패치 등가)로
  검증한다(design §4① 근거). 직접호출 케이스는 SUT 메서드 직접 호출.
- 검증 레벨: integration

### CLS-REQ-007 — shutdown summary 노출
- 유형: Non-functional
- 우선순위: Must
- 설명: 종료 시 한 줄 summary가 신규 손실 카운터 3개를 full-name key로 노출한다(기존 필드 rename은
  비목표 — 신규 3개만 비약어).
- 수용기준:
  - Given 임의 수집 실행, When `Metrics.summary()` 호출, Then 문자열이 `missingTestIdInbound=`,
    `droppedNoContext=`, `unattributedDrops=`를 (값과 함께) 포함한다.
- 검증 방식: 기존 `MetricsTest`를 **확장**(신규 `@Test` 추가) — 단순 contains가 아니라 키별 값 단언
  (오타/0값 회귀 포착).
- 검증 레벨: unit (`MetricsTest`)

### CLS-REQ-008 — 회귀 무손상 & 산출물 후방호환
- 유형: Non-functional
- 우선순위: Must
- 설명: 기존 수집·산출물 계약을 깨지 않는다. sidecar 신규 필드는 additive·조건부, `TestStore` 3-인자
  생성자·`Json`/`ExecWriter` 기존 호출 불변.
- 수용기준:
  - Given 손실이 없는 정상 수집, When sidecar 생성, Then `incompleteAttribution`/`droppedProbes`/
    `attribution` 필드가 **생략**되어 기존 포맷과 동일하다(`assertFalse(json.contains(...))`로 단언 —
    contains-true 부재로 갈음 금지).
  - Given droppedProbes>0, When sidecar 생성, Then 세 필드가 올바른 값으로 emit된다(positive 단언).
  - Given 본 변경, When `:agent:e2eTest`·`:agent:e2eJakartaTest`·`:agent:e2eCondyTest`(CI 동등)·
    in-process 단위 수집·`ExecWriterTest` 실행, Then 모두 green.
- 검증 레벨: e2e black-box + integration

> **비기능 노트(정밀도)**: sidecar `droppedProbes`는 late-write 윈도우(design §7)로 인해 **하한 추정치**
> 일 수 있다(at-least N). `incompleteAttribution` 플래그 자체는 1개라도 증가하면 유지되므로 영향 없다.
> phase 2 소비자는 count를 정밀치가 아닌 하한으로 해석한다.

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| CLS-REQ-001 | servlet 무-baggage·무컨텍스트(윈도우 내) → WARN+카운터 | `MissingTestIdInboundIT#missingId_increments_and_warnsOnce` · `#withBaggage_noSignal` · `#noActiveStore_noSignal` | integration | 🔴 planned |
| CLS-REQ-002 | 무컨텍스트 드롭 카운트 + active==0 미귀속 분류 | `OrphanProbeCounterIT#noContextThread_incrementsDroppedNoContext` · `#noActiveStore_incrementsUnattributedDrops` | integration | 🔴 planned |
| CLS-REQ-003 | servlet 아닌 cross-thread 손실 포착 | `CrossThreadDropIT#asyncWorker_incrementsDroppedNoContext` | integration | 🔴 planned |
| CLS-REQ-004 | per-test 플래그(직렬, classCount=0, race-safe) | `IncompleteAttributionSerialIT#workerOnlyTest_flaggedExact_notDiscarded` · `#trulyEmpty_discarded` | integration | 🔴 planned |
| CLS-REQ-005 | per-test 플래그(병렬, conservative) | `IncompleteAttributionParallelIT#concurrentDrops_flaggedConservative_noLoss` | integration | 🔴 planned |
| CLS-REQ-006 | 오탐 0(in-thread/MockMvc surrogate 무신호) | `NoFalsePositiveInThreadIT#directCall_noSignal` · `#contextSetServletDispatch_noSignal` | integration | 🔴 planned |
| CLS-REQ-007 | shutdown summary 카운터 노출 | `MetricsTest#summary_includesLossCounters` (확장, 값 단언) | unit | 🔴 planned |
| CLS-REQ-008 | 회귀 무손상 & sidecar 후방호환 | `ExecWriterTest#noDrop_omitsAttributionFields` · `#withDrop_emitsAttributionFields` + `:agent:e2eTest`/`e2eJakartaTest`/`e2eCondyTest` green | e2e + integration | 🔴 planned |

Coverage: 0/8 green (0%) — target 100% (대상: Must 8개; Should/Could/Won't 없음, 연기 없음)

## E2E 연동 규칙 (구현 단계)

- 각 수용 테스트는 `@DisplayName("CLS-REQ-00X: …")`로 REQ-ID를 참조한다(기존 REQ-00x와 충돌 회피).
- 이중루프: 외부 루프로 CLS-REQ-001~008의 수용 테스트를 먼저 작성(🔴→🟡), 내부 TDD로 구현해 🟡→🟢.
- 상태 갱신: 구현 시 매트릭스를 직접 갱신. PR 전 8/8 green 및 각 green REQ가 실제 통과 테스트와
  대응(테스트명 대조)하는지 확인. 불일치 시 실제 테스트 결과가 정답.

## 자기검토

1. **고아 행위 없음**: design §4 컴포넌트(①→001, ②→002/003, DATA→004/005, 빈-store 가드 race-safe→004,
   오탐0 불변식→006, summary→007, unattributedDrops→002/007, 후방호환·late-write→008)와 §2 불변식 전부
   매핑. ✅
2. **원자성**: 각 REQ 단일 행위. servlet WARN(001)/일반 orphan+미귀속(002)/비-servlet 경로(003) 분리. ✅
3. **수용기준 완비**: 전 REQ Given-When-Then, 측정 가능(카운터 증감·sidecar 필드 존재/부재·값). 검증
   방식(로그 캡처·surrogate·확장)도 명시. ✅
4. **커버리지 규칙 명시**: 분모=Must 8, 제외 없음. ✅
