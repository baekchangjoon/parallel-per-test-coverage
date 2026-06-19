# Trace Context 소비 기반 per-test 커버리지 요구사항명세

> 출처(design spec): `docs/superpowers/specs/2026-06-19-trace-context-per-test-coverage-design.md`
> 완료 정의(DoD): 커버리지 대상 요구사항이 각각 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 🟢).
> 단계화: C1 → C2 → C3 (plan에서 phase로 분리, C1 먼저). 백엔드: Brave/Sleuth + OTel.
> GA-1/2/3(선결 검증 가정)은 제품 행위가 아니라 phase 착수 전 **spike 게이트**이므로 REQ 행이 아니라 plan의 검증 태스크로 둔다(각 REQ의 검증 레벨 비고에 전제로 인용).

---

## 요구사항 목록

### 불변 제약 (전 범위 공통)

### REQ-001 — 핫패스 무변경 (probe당 ThreadLocal 1-read)
- 유형: Non-functional
- 우선순위: Must
- 설명: trace context 소비를 추가해도 `CoverageBridge.recordCoverage`의 핫패스는 `CoverageContext`(ThreadLocal) 1-read → `store.record()`를 유지하고, probe당 트레이서 조회를 추가하지 않는다.
- 수용기준:
  - Given trace 소비 기능이 통합된 빌드, When `recordCoverage`의 호출 경로를 검사(단위 가드 테스트)하면, Then probe당 트레이서 API 호출이 0이고 ThreadLocal read는 1회다.
- 검증 레벨: unit (hot-path invariant guard)

### REQ-002 — 트레이서 의존 reflective/optional, Java 8, 하드 의존 0
- 유형: Non-functional
- 우선순위: Must
- 설명: `brave.*`/`io.opentelemetry.*`에 대한 컴파일·런타임 하드 의존이 없고, agent 산출물은 트레이서 없이도 로드/동작하며 Java 8에서 빌드·실행된다.
- 수용기준:
  - Given 트레이서 라이브러리가 클래스패스에 **없는** SUT, When agent를 attach하면, Then `ClassNotFoundException`/`NoClassDefFoundError` 없이 폴백 경로로 동작한다.
  - Given shaded agent jar, When 의존성/바이트코드 버전을 검사하면, Then 트레이서 하드 의존이 없고 타깃 바이트코드가 Java 8이다.
- 검증 레벨: integration (no-tracer attach) + build guard

### REQ-003 — best-effort, SUT로 예외 전파 금지
- 유형: Non-functional
- 우선순위: Must
- 설명: 신규 경로(TestIdSource/TraceScopeBridge/매핑/flush)는 모든 예외를 swallow하며 애플리케이션 코드로 throw하지 않는다. 커버리지 손실은 허용, 앱 크래시는 불가.
- 수용기준:
  - Given TestIdSource/scope 훅이 내부 오류를 던지는 상황(주입 실패·reflective 오류), When SUT 요청을 처리하면, Then 애플리케이션 응답은 정상이고 예외가 SUT로 전파되지 않으며 `swallowedExceptions`/관련 카운터가 증가한다.
- 검증 레벨: integration

### C1 — in-process trace context 소비 + async 전파

### REQ-004 — OTel trace context를 coverage key로 in-process 소비
- 유형: Functional
- 우선순위: Must
- 설명: OTel이 활성인 단일 서비스에서, 현재 스레드의 valid traceId를 coverage key로 사용해 해당 요청 실행 커버리지를 그 키의 store에 기록한다.
- 수용기준:
  - Given OTel javaagent가 붙은 서비스 + pjacoco, When 한 traceId로 동기 요청을 처리하면, Then 그 요청이 실행한 클래스 커버리지가 해당 traceId 키 store에 기록된다.
- 검증 레벨: E2E black-box (tainted-spring 단일 서비스) — 전제 GA-1(OTel)·GA-3

### REQ-005 — Brave trace context를 coverage key로 in-process 소비
- 유형: Functional
- 우선순위: Must
- 설명: Brave/Sleuth가 활성인 단일 서비스에서, 현재 스레드의 valid traceId를 coverage key로 사용해 커버리지를 기록한다.
- 수용기준:
  - Given Sleuth가 붙은 서비스(Java 8) + pjacoco, When 한 traceId로 동기 요청을 처리하면, Then 커버리지가 해당 traceId 키 store에 기록된다.
- 검증 레벨: E2E black-box (legacy-tram 단일 서비스) — 전제 GA-1(Brave)·GA-3

### REQ-006 — async 핸드오프 커버리지가 같은 test로 귀속 (핵심 갭 해소)
- 유형: Functional
- 우선순위: Must
- 설명: 요청 핸들러가 executor/`@Async`/별도 스레드로 작업을 넘겨도, 트레이서가 trace context를 전파하는 한 그 스레드의 커버리지가 동일 coverage key에 귀속된다.
- 수용기준:
  - Given trace 소비가 활성인 서비스, When 테스트가 executor/`@Async`로 넘어가는 코드 경로를 실행하면, Then 그 async 코드의 커버리지가 진입 요청과 같은 coverage key store에 나타난다(현행에서는 0인 것이 채워짐).
- 검증 레벨: E2E black-box (단일 서비스, Brave 또는 OTel) — red 재현: 현재 async 커버리지 0. 전제 GA-1

### REQ-007 — 트레이서 부재 시 기존 동작 무회귀
- 유형: Functional
- 우선순위: Must
- 설명: 트레이서가 없으면 기존 servlet baggage 헤더/JUnit zero-touch/in-process 경로가 현행 그대로 동작한다.
- 수용기준:
  - Given 트레이서 없는 SUT, When 기존 servlet baggage / JUnit 경로로 per-test 커버리지를 수집하면, Then 현행과 동일한 per-testId 결과가 나온다(기존 E2E 회귀 green).
- 검증 레벨: E2E black-box / integration (기존 스위트 회귀)

### REQ-008 — 모드 우선순위: scope 브리지 활성 시 inbound advice 바인딩 no-op
- 유형: Functional
- 우선순위: Must
- 설명: `TraceScopeBridge`가 활성인 동안 `ServletAdvice`/`RunLeafAdvice`의 `CoverageContext` 바인딩이 trace 바인딩을 덮어쓰지 않는다(단일 우선순위 규칙).
- 수용기준:
  - Given 트레이서 + servlet 양쪽 경로가 같은 스레드에서 도는 요청, When 요청을 처리하면, Then coverage key는 trace 기준으로 일관되며 servlet baggage 바인딩이 그것을 덮어쓰지 않는다.
- 검증 레벨: integration

### REQ-009 — cross-thread scope close 시 컨텍스트 무오염
- 유형: Non-functional (correctness)
- 우선순위: Must
- 설명: scope를 연 스레드와 닫는 스레드가 다를 때(CompletableFuture 등), 닫는 스레드의 `CoverageContext`를 오염시키지 않는다(복원 상태는 scope 객체 필드 보관).
- 수용기준:
  - Given 스레드 A에서 scope를 열고 스레드 B에서 close, When B가 자신의 coverage key로 기록 중이면, Then B의 컨텍스트가 A의 복원으로 오염되지 않는다.
- 검증 레벨: unit

### REQ-010 — valid SpanContext만 키로 사용, invalid는 local 폴백
- 유형: Functional
- 우선순위: Must
- 설명: `OtelTestIdSource`/`BraveTestIdSource`는 `SpanContext.isValid()`(non-zero)일 때만 traceId를 키로 쓰고, invalid(no-op span, `0…0`)이면 local로 폴백해 zero-traceId phantom store를 만들지 않는다.
- 수용기준:
  - Given 활성 span이 없는(invalid) 스레드, When TestIdSource가 키를 resolve하면, Then traceId 키를 만들지 않고 local 소스로 폴백한다(zero-trace store 미생성).
- 검증 레벨: unit

### C2 — traceId ↔ testId 매핑

### REQ-011 — control endpoint로 traceId→testId 매핑 등록
- 유형: Functional
- 우선순위: Must
- 설명: `POST /__coverage__/trace/map?traceId=<T>&testId=<FQCN#method>`로 매핑을 등록하면 리포트/병합 시 그 testId로 표시된다.
- 수용기준:
  - Given 매핑이 등록된 traceId의 커버리지, When 리포트를 생성하면, Then 해당 커버리지가 등록한 `FQCN#method` testId로 표시된다.
- 검증 레벨: integration

### REQ-012 — 미등록 traceId는 raw traceId를 testId로 사용
- 유형: Functional
- 우선순위: Must
- 설명: 매핑이 없으면 입력 traceId를 그대로 testId로 사용한다(리포트에 raw traceId 노출).
- 수용기준:
  - Given 매핑 미등록 traceId의 커버리지, When 리포트를 생성하면, Then 그 커버리지가 raw traceId 문자열을 키/testId로 갖는다.
- 검증 레벨: E2E black-box

### REQ-013 — N:1 — 한 testId의 여러 traceId 병합
- 유형: Functional
- 우선순위: Should
- 설명: 한 테스트가 여러 traceId(다수 outbound 호출/재시도)를 만들면, 같은 testId로 등록된 모든 traceId store를 출력 시점에 병합한다.
- 수용기준:
  - Given 같은 testId로 등록된 traceId 2개의 store, When 병합/리포트하면, Then 두 traceId의 커버리지가 하나의 testId 결과로 합산된다.
- 검증 레벨: integration

### REQ-014 — testId를 FQCN#method로 정규화
- 유형: Functional
- 우선순위: Should
- 설명: 매핑 등록 시 testId를 `FQCN#method`로 정규화해, 어댑터별 형식 불일치(`getClassName` vs `getSimpleName`)를 제거한다.
- 수용기준:
  - Given 단순명/혼재 형식으로 들어온 testId, When 매핑에 등록하면, Then `FQCN#method`로 정규화되어 저장된다.
- 검증 레벨: unit

### C3 — 분산 per-test 커버리지

### REQ-015 — 서비스 간 per-test 커버리지 병합 리포트
- 유형: Functional
- 우선순위: Must
- 설명: 여러 서비스가 각자 raw traceId로 기록한 커버리지를, 러너의 `traceId→testId` 맵으로 중앙에서 병합해 testId별·서비스별 리포트를 만든다.
- 수용기준:
  - Given (Brave) order-web→reservation→(Tram/Kafka/CDC)→ledger 풀스택에서 한 테스트가 트리거한 traceId, When 중앙 병합하면, Then 그 testId 리포트에 reservation과 ledger(다운스트림) 양쪽 커버리지가 잡힌다.
  - Given (OTel) tainted-spring 8서비스에서 BFF→Kafka 경유 한 블랙박스 테스트, When 중앙 병합하면, Then 그 testId 리포트에 BFF와 다운스트림 서비스의 커버리지가 잡힌다.
- 검증 레벨: E2E black-box (legacy-tram 풀스택 + tainted-spring 8서비스) — 전제 GA-2(OTel)

### REQ-016 — trace-store flush 생명주기 (장기 실행 서비스)
- 유형: Functional
- 우선순위: Must
- 설명: 상시 가동 서비스에서 traceId-키 store를 JVM 종료에 의존하지 않고 flush한다 — idle reaper / 루트 scope close / 명시 control API 중 하나로 `<traceId>.exec`를 내린다.
- 수용기준:
  - Given 종료하지 않는 장기 실행 서비스, When 한 trace가 끝나고 flush 트리거 조건이 충족되면, Then 그 traceId의 `.exec`가 수집 가능 상태로 기록된다(JVM 종료 불필요).
- 검증 레벨: integration / E2E

### REQ-017 — late-write grace period (flush 이후 늦은 쓰기 비유실)
- 유형: Non-functional (reliability)
- 우선순위: Should
- 설명: flush/stop 이후 도착하는 비동기/다운스트림 쓰기를 grace 기간 동안 append/merge하거나 재flush해 유실을 방지한다.
- 수용기준:
  - Given store가 flush된 직후 같은 키로 도착한 늦은 커버리지 쓰기, When grace 기간 내라면, Then 그 쓰기가 최종 산출물에 반영된다.
- 검증 레벨: integration

### REQ-018 — 높은 trace 카디널리티에서 in-flight trace eviction 방지
- 유형: Non-functional (reliability)
- 우선순위: Should
- 설명: traceId 키는 요청마다 신규라 카디널리티가 높다. `maxStores` 정책이 진행 중(in-flight) trace를 mid-trace에 evict하지 않도록 트레이서 모드 전용 cap/idle-우선 eviction을 둔다.
- 수용기준:
  - Given 다수 동시 trace로 store 수가 cap에 근접, When 새 trace가 들어와도, Then 진행 중 trace store가 mid-trace에 evict되지 않고(또는 idle 우선 evict) `evictedInFlightTraces`로 관측된다.
- 검증 레벨: integration

### 관측성

### REQ-019 — 조용한 유실 가시화 metrics
- 유형: Non-functional
- 우선순위: Should
- 설명: `Metrics`에 `scopeHookInjectionFailures`, `unmappedTraceIds`, `fallbackActivations`(+가능하면 `evictedInFlightTraces`) 카운터를 추가해 폴백·유실을 가시화한다.
- 수용기준:
  - Given scope 훅 주입 실패/미매핑 traceId/폴백 발동, When 해당 상황이 발생하면, Then 대응 카운터가 증가하고 노출된다.
- 검증 레벨: unit / integration

### 범위 외 (Won't / out-of-scope)

### REQ-020 — [Won't] scope 훅 실패 시 Kafka/@Async용 messaging inbound activator
- 유형: Functional
- 우선순위: Won't (이번 범위 제외 — design spec §7에서 "별도 과제"로 명시)
- 설명: scope 훅이 실패한 폴백 상태에서 Kafka consumer/@Async 진입점 advice를 추가해 그 경로 커버리지를 살리는 것. 이번 범위에서는 metrics+로그로 한계만 노출.
- 검증 레벨: — (🔵 분모 제외)

### REQ-021 — [Won't] 운영(non-test) per-request 커버리지
- 유형: Functional
- 우선순위: Won't (design spec 비목표)
- 설명: 테스트 하네스 용도가 아닌 프로덕션 트래픽의 per-request 커버리지.
- 검증 레벨: — (🔵 분모 제외)

---

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-001 | 핫패스 무변경 | `HotPathInvariantTest#recordCoverageDoesNotCallTracer` | unit | 🔴 planned |
| REQ-002 | reflective/optional·Java8 | `NoTracerAttachIT` + build bytecode guard | integration | 🔴 planned |
| REQ-003 | best-effort no-throw | `TraceConsumeFailureIT#appUnaffectedOnHookError` | integration | 🔴 planned |
| REQ-004 | OTel in-process 소비 | `OtelSingleServiceE2E#syncRequestAttributed` | E2E | 🔴 planned |
| REQ-005 | Brave in-process 소비 | `BraveSingleServiceE2E#syncRequestAttributed` | E2E | 🔴 planned |
| REQ-006 | async 핸드오프 귀속 | `AsyncHandoffCoverageE2E#asyncWorkAttributedToTest` | E2E | 🔴 planned |
| REQ-007 | 트레이서 부재 무회귀 | 기존 스위트 + `TracerAbsentFallbackIT` | E2E/integration | 🔴 planned |
| REQ-008 | 모드 우선순위 no-op | `ModePrecedenceIT#servletDoesNotOverwriteTraceBinding` | integration | 🔴 planned |
| REQ-009 | cross-thread scope close | `TraceScopeBridgeTest#closeOnOtherThreadNoCorruption` | unit | 🔴 planned |
| REQ-010 | valid span만, invalid 폴백 | `TestIdSourceTest#invalidSpanFallsBackToLocal` | unit | 🔴 planned |
| REQ-011 | 매핑 등록 endpoint | `TraceMapEndpointIT#registeredMappingShownAsTestId` | integration | 🔴 planned |
| REQ-012 | 미등록 raw traceId | `UnmappedTraceE2E#rawTraceIdAsTestId` | E2E | 🔴 planned |
| REQ-013 | N:1 병합 | `TraceMergeTest#multipleTraceIdsOneTestId` | integration | 🔴 planned |
| REQ-014 | testId 정규화 | `TestIdNormalizationTest#toFqcnHashMethod` | unit | 🔴 planned |
| REQ-015 | 서비스 간 병합 리포트 | `LegacyTramDistributedE2E` + `TaintedSpringDistributedE2E` | E2E | 🔴 planned |
| REQ-016 | flush 생명주기 | `TraceStoreLifecycleIT#flushWithoutJvmExit` | integration/E2E | 🔴 planned |
| REQ-017 | late-write grace | `LateWriteGraceIT#lateWriteNotLost` | integration | 🔴 planned |
| REQ-018 | in-flight eviction 방지 | `TraceEvictionIT#inFlightNotEvicted` | integration | 🔴 planned |
| REQ-019 | 관측성 카운터 | `MetricsTest#traceCountersIncrement` | unit/integration | 🔴 planned |
| REQ-020 | [Won't] messaging activator | — | — | 🔵 out-of-scope |
| REQ-021 | [Won't] 운영 per-request | — | — | 🔵 out-of-scope |

Coverage: 0/19 green (0%) — target 100% (대상: Must 14 + 미연기 Should 5). 제외: Won't 2 (🔵 REQ-020, REQ-021). GA-1/2/3는 phase 게이트 spike(plan에서 추적), REQ 행 아님.
