# Trace Context 소비 기반 per-test 커버리지 요구사항명세

> 출처(design spec): `docs/superpowers/specs/2026-06-19-trace-context-per-test-coverage-design.md`
> 완료 정의(DoD): 커버리지 대상 요구사항이 각각 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 🟢).
> 단계화: C1 → C2 → C3 (plan에서 phase로 분리, C1 먼저). 백엔드: Brave/Sleuth + OTel.
> GA-1/2/3(선결 검증 가정)은 제품 행위가 아니라 phase 착수 전 **spike 게이트**이므로 REQ 행이 아니라 plan의 검증 태스크로 둔다. 각 GA의 spike exit criteria는 writing-plans가 산출하는 plan 문서가 보유한다(전방 참조). 각 REQ의 검증 레벨 비고에 관련 GA를 전제로 인용.

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

### REQ-002 — 트레이서 의존 optional, 런타임 하드 의존 0, Java 8
- 유형: Non-functional
- 우선순위: Must
- 설명: agent 산출물은 트레이서 없이도 로드/동작하며 Java 8에서 빌드·실행된다. **런타임 하드 의존 0**이 불변이다. 어댑터/extension 코드는 트레이서를 볼 수 있는 classloader(OTel extension, 또는 app-CL에 인라인되는 ByteBuddy 우븐 advice)에 거주할 때 `compileOnly` 의존으로 타입-세이프하게 컴파일해도 되나, 트레이서 부재 시 graceful 폴백해야 한다. bootstrap/agent-CL에 거주하는 코드는 reflection으로 접근한다(GA-3).
- 수용기준:
  - (a) Given 트레이서 라이브러리가 클래스패스에 **없는** SUT, When agent를 attach하면, Then `ClassNotFoundException`/`NoClassDefFoundError` 없이 폴백 경로로 동작한다.
  - (b) Given shaded agent jar, When 의존성/바이트코드를 검사하면, Then 트레이서 **런타임** 하드 의존이 없고(추이 런타임 의존에 brave/otel 없음) 타깃 바이트코드가 Java 8이다.
- 검증 레벨: integration (no-tracer attach) + build guard

### REQ-003 — best-effort, SUT로 예외 전파 금지
- 유형: Non-functional
- 우선순위: Must
- 설명: 신규 경로(TestIdSource/TraceScopeBridge/매핑/flush)는 모든 예외를 swallow하며 애플리케이션 코드로 throw하지 않는다. 커버리지 손실은 허용, 앱 크래시는 불가. 카운터 귀속: **런타임 reflective 오류**(TestIdSource/scope 훅 실행 중)는 `swallowedExceptions`, **bootstrap scope 훅 주입 실패**는 `scopeHookInjectionFailures`(REQ-019)로 분리 집계.
- 수용기준:
  - Given TestIdSource/scope 훅이 런타임 reflective 오류를 던지는 상황, When SUT 요청을 처리하면, Then 애플리케이션 응답은 정상이고 예외가 SUT로 전파되지 않으며 `swallowedExceptions`가 증가한다.
- 검증 레벨: integration

### C1 — in-process trace context 소비 + async 전파

### REQ-004 — OTel trace context를 coverage key로 in-process 소비
- 유형: Functional
- 우선순위: Must
- 설명: OTel이 활성인 단일 서비스에서, 현재 스레드의 valid traceId를 coverage key로 사용해 해당 요청 실행 커버리지를 그 키의 store에 기록한다.
- 수용기준:
  - Given OTel javaagent + pjacoco OTel extension(`-Dotel.javaagent.extensions=…`)이 붙은 서비스, When 한 traceId로 동기 요청을 처리하면, Then 그 요청이 실행한 클래스 커버리지가 해당 traceId 키 store에 기록된다.
- 검증 레벨: E2E black-box (tainted-spring 단일 서비스) — 전제 GA-1(OTel)·GA-3, OTel extension 배포 필요

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
- 검증 레벨: E2E black-box (단일 서비스, Brave 또는 OTel) — red 재현: 현재 async 커버리지 0. 전제 GA-1(+ OTel 경로는 extension 배포)

### REQ-007 — 트레이서 부재 시 기존 동작 무회귀
- 유형: Functional
- 우선순위: Must
- 설명: 트레이서가 없으면 기존 servlet baggage 헤더/JUnit zero-touch/in-process 경로가 현행 그대로 동작한다.
- 수용기준:
  - Given 트레이서 없는 SUT, When 기존 servlet baggage / JUnit 경로로 per-test 커버리지를 수집하면, Then 현행과 동일한 per-testId 결과가 나온다(기존 E2E 회귀 green).
- 검증 레벨: E2E black-box / integration (기존 스위트 회귀)

### REQ-008 — 모드 우선순위: trace 바인딩을 inbound advice가 덮지 않음 (통합 resolver)
- 유형: Functional
- 우선순위: Must
- 설명: `TraceScopeBridge`가 활성인 동안 `ServletAdvice`/`RunLeafAdvice`가 trace 바인딩을 덮어쓰지 않는다. 단, **servlet의 baggage 헤더 파싱은 유지**(트레이서 invalid 시 local 폴백 소스로 필요)하고, **advice exit가 trace가 set한 컨텍스트를 clear하지 않는다.** 구현은 blunt no-op이 아니라 `TestIdSource` 단일 우선순위 resolver(valid traceId 우선, 없으면 baggage/local)로 라우팅한다. "TraceScopeBridge active" 감지 방식과 scope enter/exit↔servlet enter/exit 순서는 GA-3에서 확정.
- 수용기준:
  - Given 트레이서 + servlet 경로가 같은 스레드에서 도는 요청, When 요청을 처리하면, Then coverage key는 valid traceId 기준이며 servlet baggage 바인딩/exit clear가 그것을 덮거나 지우지 않는다.
  - Given TraceScopeBridge 활성 + JUnit zero-touch(`RunLeafAdvice`)가 같은 스레드, When 테스트가 돌면, Then coverage key는 trace 기준을 유지하고 RunLeafAdvice 바인딩이 덮어쓰지 않는다.
- 검증 레벨: integration

### REQ-009 — cross-thread scope close 시 컨텍스트 무오염
- 유형: Non-functional (correctness)
- 우선순위: Must
- 설명: scope를 연 스레드와 닫는 스레드가 다를 때(CompletableFuture 등), 닫는 스레드의 `CoverageContext`를 오염시키지 않는다(복원 상태는 scope 객체 필드 보관).
- 수용기준:
  - Given 스레드 A에서 scope를 열고 스레드 B에서 close, When B가 자신의 coverage key로 기록 중이면, Then B의 컨텍스트가 A의 복원으로 오염되지 않는다.
- 검증 레벨: unit

### REQ-010 — valid trace context만 키로 사용, invalid는 local 폴백 (백엔드별)
- 유형: Functional
- 우선순위: Must
- 설명: TestIdSource는 valid한 trace context일 때만 traceId를 키로 쓰고, invalid이면 local로 폴백해 zero-trace phantom store를 만들지 않는다. 판정은 백엔드별로 다르다.
- 수용기준:
  - (OTel) Given 활성 span이 없거나 `SpanContext.isValid()`가 false(`0…0`)인 스레드, When `OtelTestIdSource`가 키를 resolve하면, Then traceId 키를 만들지 않고 local로 폴백한다.
  - (Brave) Given `currentTraceContext().get()`이 null이거나 `traceIdString()`이 invalid인 스레드, When `BraveTestIdSource`가 키를 resolve하면, Then local로 폴백한다.
- 검증 레벨: unit

### REQ-022 — 트레이서 모드 store 자동 생성 (strict 모드 비호환 해소)
- 유형: Functional
- 우선순위: Must
- 설명: `TestStoreRegistry.active()`는 `autoRegister=false`(현 `AgentOptions` 기본)면 미등록 키에 null을 반환해 probe가 silent skip된다. 트레이서 모드는 traceId 키 store를 **자동 생성**해야 한다 — `autoRegister=true`, 전용 `traceKeyAutoCreate` 플래그, 또는 scope enter 시 `start(traceId)` 선행 중 하나로. 미적용 시 REQ-004~006이 무신호로 전부 실패한다.
- 수용기준:
  - Given 기본(strict) 설정의 트레이서 모드 agent에서 명시 `start()` 없이, When 새 traceId로 probe가 발생하면, Then 커버리지가 silent drop되지 않고 기록된다.
- 검증 레벨: integration

### C2 — traceId ↔ testId 매핑

### REQ-011 — control endpoint로 traceId→testId 매핑 등록 (bounded)
- 유형: Functional
- 우선순위: Must
- 설명: `POST /__coverage__/trace/map?traceId=<T>&testId=<FQCN#method>`로 매핑을 등록하면 리포트/병합 시 그 testId로 표시된다. traceId 카디널리티가 높으므로 매핑 저장소는 **bounded**(TTL 또는 LRU 상한)여서 장기 실행 서비스에서 OOM을 막는다. (C3에서 매핑은 주로 러너 중앙에 있고 per-service 등록은 단일 서비스 편의용 — REQ-015 참조.)
- 수용기준:
  - Given 매핑이 등록된 traceId의 커버리지, When 리포트를 생성하면, Then 해당 커버리지가 등록한 `FQCN#method` testId로 표시된다.
  - Given 상한을 초과하는 매핑 등록이 지속되는 장기 실행 서비스, When 시간이 지나도, Then 매핑 저장소 크기가 상한 내로 유지된다(TTL/LRU eviction).
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
- 검증 레벨: E2E black-box — 전제: Brave 경로 = GA-1(Brave) + legacy-tram trace 생존(R1 입증); OTel 경로 = GA-1(OTel, extension) + GA-2(Kafka 홉). REQ-023(수집) 선행.

### REQ-023 — C3 per-service .exec 수집 + drain 대기
- 유형: Functional
- 우선순위: Must
- 설명: N개 서비스 JVM의 per-traceId `.exec`가 중앙 collector로 모이는 메커니즘을 baseline 하나 이상 제공한다(공유 볼륨 / control-pull HTTP / CI 아티팩트 중 plan에서 택1). 병합은 비동기(Tram/CDC/Kafka) 지연을 고려해 **drain-wait 타임아웃** 이후 실행한다.
- 수용기준:
  - Given 다운스트림 커버리지가 비동기로 늦게 도착하는 분산 실행, When drain-wait 타임아웃 후 수집·병합하면, Then 다운스트림 서비스의 `.exec`가 누락 없이 중앙 리포트에 포함된다.
- 검증 레벨: integration (최고 실현가능 — drain-wait 늦은-도착 흡수는 주입 Sleeper로 결정론 검증; Docker CDC 지연은 분산 E2E에서 부수 실측). [C3b 역전파: 원 'E2E black-box'에서 강등]

### REQ-016 — trace-store flush 생명주기 (장기 실행 서비스)
- 유형: Functional
- 우선순위: Must
- 설명: 상시 가동 서비스에서 traceId-키 store를 JVM 종료에 의존하지 않고 flush한다. **기본 트리거는 주기적 idle reaper**(일정 시간 무업데이트 store를 백그라운드 flush+evict) — per-request마다 즉시 디스크 flush(루트 scope close 트리거)는 고처리량에서 I/O 포화를 일으키므로 기본값이 아니다(필요 시 옵션). 명시 control API flush도 제공.
- 수용기준:
  - Given 종료하지 않는 장기 실행 서비스에서 한 trace가 끝나고 idle reaper 간격이 경과, When reaper가 동작하면, Then 그 traceId의 `.exec`가 수집 가능 상태로 기록된다(JVM 종료 불필요).
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
- 설명: traceId 키는 요청마다 신규라 카디널리티가 높다. **현 `TestStoreRegistry.enforceCap()`은 oldest-start-time 기준 evict라 활발히 기록 중인 in-flight trace도 evict될 수 있다 — 이를 idle-우선(또는 in-flight 보호) eviction으로 바꾸는 신규 동작이다.** 트레이서 모드 전용 cap/정책을 둔다.
- 수용기준:
  - Given 다수 동시 trace로 store 수가 cap에 근접, When 새 trace가 들어와도, Then idle store가 in-flight보다 먼저 evict되고(또는 in-flight 미evict), 불가피한 in-flight evict는 `evictedInFlightTraces`로 관측된다.
- 검증 레벨: integration

### Degradation

### REQ-024 — scope 훅 주입 실패 시 동기 경로 choke-point 폴백
- 유형: Functional
- 우선순위: Should
- 설명: GA-1 실패(scope 훅 주입 불가) 시, servlet 진입점에서 traceId를 read해 동기 HTTP 경로 커버리지를 올바른 traceId 키로 귀속한다(async/Kafka/@Async는 미커버 — 한계 노출). 무신호 0-커버리지로 떨어지지 않음을 보장.
- 수용기준:
  - Given OTel/Brave 존재하나 scope 훅 주입이 비활성, When 동기 servlet 요청이 발생하면, Then 그 요청 커버리지가 servlet 진입에서 read한 traceId 키에 나타난다(0이 아님).
- 검증 레벨: integration

### 관측성

### REQ-019 — 조용한 유실 가시화 metrics
- 유형: Non-functional
- 우선순위: Should
- 설명: `io.pjacoco.agent.observability.Metrics`에 신규 카운터 `scopeHookInjectionFailures`, `unmappedTraceIds`, `fallbackActivations`(+ REQ-018의 `evictedInFlightTraces`)를 추가해 폴백·유실을 가시화한다(기존: `testsCompleted`/`partialDumps`/`swallowedExceptions`/`rejectedUnregistered`/`retriesOverwritten`).
- 수용기준:
  - Given scope 훅 주입이 1회 실패, When 발생 후 metrics를 읽으면, Then `scopeHookInjectionFailures == 1`.
  - Given 미매핑 traceId로 리포트, When 발생하면, Then `unmappedTraceIds`가 증가한다.
  - Given 폴백 경로 발동, When 발생하면, Then `fallbackActivations`가 증가한다.
- 검증 레벨: unit / integration

### 범위 외 (Won't / out-of-scope)

### REQ-020 — [Won't] scope 훅 실패 시 Kafka/@Async용 messaging inbound activator
- 유형: Functional
- 우선순위: Won't (이번 범위 제외 — design spec §7 "별도 과제")
- 설명: scope 훅 실패 폴백 상태에서 Kafka consumer/@Async 진입점 advice를 추가해 그 경로 커버리지를 살리는 것. 이번엔 metrics+로그로 한계만 노출(REQ-024).
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
| REQ-001 | 핫패스 무변경 | `HotPathInvariantTest#recordCoverageReadsThreadLocalOnceAndRecordsOnce` | unit | 🟢 green |
| REQ-002 | optional·런타임 하드의존0·Java8 | `ShadedJarNoTracerDepTest#{shadedJarContainsNoBraveClasses, shadedJarContainsNoOpenTelemetryClasses, agentClassBytecodeTargetsJava8}` + `NoTracerAttachIT#{otelSourceReturnsNullWhenOtelAbsent, braveSourceReturnsNullWhenNoCurrentTracing, resolverReturnsNullWhenNoTracerContextActive}` | unit + integration | 🟢 green |
| REQ-003 | best-effort no-throw | `TraceConsumeFailureIT#{throwingTracerSourceDoesNotBreakServletActivation, nullStoreScopeHookIsHandledGracefully, installFailureIncrementsObservableMetric}` | integration | 🟢 green |
| REQ-004 | OTel in-process 소비 | `OtelWeaveE2E#otelWeave_requestAndAsyncThreadsCoveredUnderSameTrace` (RequestHandler attribution — REQ-004 assertion). 실제 OTel javaagent(v2.11.0) 사용, forked JVM에서 검증. 추가 가드: `#otelWeave_firesWhenOtelAgentMountedWithNonConventionalFilename`(비관용 파일명 `otel.jar` 마운트에서도 weave 발동) + 단위 `OtelScopeInboundActivatorTest`(jar 식별) | E2E + unit | 🟢 green |
| REQ-005 | Brave in-process 소비 | `BraveScopeWeaveIT#braveScope_sync_and_async_drivesCoverageContext` (sync assertion). 추가 증거: legacy-tram out-of-process, weave-driven | integration | 🟢 green |
| REQ-006 | async 핸드오프 귀속 | `BraveScopeWeaveIT#braveScope_sync_and_async_drivesCoverageContext` (async worker assertion) + `OtelWeaveE2E#otelWeave_requestAndAsyncThreadsCoveredUnderSameTrace` (AsyncWorker async assertion). 비고: REQ-006은 단일 서비스 내 async를 규정. cross-JVM Kafka-consumer async은 별도 발견이었고 `discoverOtelJar` 파일명 버그가 근인으로 확정·수정됨(OTel-weave-kafka-consumer-gap 결정 문서 → **RESOLVED**); 실측 결과 mindgraph consumer-스레드 커버리지가 traceId에 귀속(classCount 0→14) | integration + E2E | 🟢 green |
| REQ-007 | 트레이서 부재 무회귀 | `NoTracerAttachIT#{otelSourceReturnsNullWhenOtelAbsent, braveSourceReturnsNullWhenNoCurrentTracing, resolverReturnsNullWhenNoTracerContextActive}` + `TracerAbsentFallbackIT#{noTracerBaggageFallbackBindsStoreAndIncrementsCounter, noBaggageNoTracerDoesNotBindAndDoesNotIncrement}` | integration | 🟢 green |
| REQ-008 | 모드 우선순위(통합 resolver) | `CoverageKeyResolverTest#{resolvePrefersFirstNonNull, resolveNullWhenAllEmpty, throwingSourceIsSkipped}` + `ServletAdviceTest#{activatesResolvedStoreFromBaggage, strictUnregisteredLeavesContextUnset, noHeaderOrNonHttpIgnored}`. 참조: `docs/superpowers/decisions/2026-06-19-deactivate-clear-semantics.md` | unit | 🟢 green |
| REQ-009 | cross-thread scope close | `TraceScopeBridgeTest#closeOnOtherThreadDoesNotCorrupt` | unit | 🟢 green |
| REQ-010 | valid trace만, invalid 폴백(백엔드별) | `OtelTestIdSourceTest#{invalidSpanFallsBackToNull, validSpanReturnsTraceId, throwingSeamFallsBackToNull}` + `BraveTestIdSourceTest#{nullContextFallsBack, validContextReturnsTraceId, emptyStringFallsBack, throwingSeamFallsBack}` | unit | 🟢 green |
| REQ-011 | 매핑 등록 endpoint(bounded) | `TraceMapEndpointIT#{registeredShown, boundedEvictionThroughEndpoint}` — registeredShown: 등록→merge→`<FQCN#method>.exec` 존재까지 full-path 검증; boundedEvictionThroughEndpoint: HTTP 연속 POST cap=2 초과로 LRU eviction 확인 | integration | 🟢 green |
| REQ-012 | 미등록 raw traceId | `UnmappedTraceReportIT#rawTraceIdAsTestId` + `TraceCoverageMergerTest#unmappedTraceFallsBackToRawAndCounts` — 분산 인프라는 C3 범위라 최고 실현가능 레벨은 integration(transport-독립 리포트 레이어 검증) | integration (최고 실현가능) | 🟢 green |
| REQ-013 | N:1 병합 | `TraceCoverageMergerTest#multipleTraceIdsOneTestId` (+ `aggregateExecAndDirsAreExcluded` 부가 증거) | integration | 🟢 green |
| REQ-014 | testId 정규화 | `TestIdNormalizationTest#toFqcnHashMethod` (unit) + `PjacocoExtensionTest`/`PjacocoRuleTest` (어댑터 FQCN 정렬 확인) | unit + integration | 🟢 green |
| REQ-015 | 서비스 간 병합 리포트 | `DistributedCoverageMergerTest#{perServicePerTestIdReport, multipleTraceIdsOneTestIdWithinServiceMerged, unmappedTraceIdFallsBackToRawPerService}`(서비스 축 병합 메커니즘) + `TraceMergeMainTest`(CLI). 전파 전제 입증: GA-1(C1) + GA-2(C1 cross-JVM, ga-spike-results) + R1(legacy-tram trace 생존). **조립된 분산 Docker E2E**(`LegacyTramDistributedE2E`/`TaintedSpringDistributedE2E`)는 외부 다중-서비스 스택 기동 필요 → 전용 환경 실행 잔여 | integration(메커니즘) + E2E(잔여) | 🟡 partial |
| REQ-016 | flush 생명주기(idle reaper) | `TraceStoreLifecycleIT#idleReaperFlushWithoutJvmExit` + `TraceStoreReaperTest#{idleStoreFlushedWithoutJvmExit, idleStoreEvictedAfterGrace}` — 주입 clock으로 결정론, Docker 다중서비스는 C3b. 비고: 기존 control stop(`/test/stop`)은 즉시 flush 수단으로 유지; design §6.4 (b) scope-close flush는 C3a 범위 외 | integration | 🟢 green |
| REQ-017 | late-write grace | `TraceStoreReaperTest#lateWriteWithinGraceIsReflushedNotLost` | integration | 🟢 green |
| REQ-018 | in-flight eviction 방지 | `TestStoreRegistryEvictionTest#{idleEvictedBeforeInFlight, unavoidableInFlightEvictionIsCounted}` — 비고: 비-트레이서 모드에서 lastActivityMillis = startedAtMillis → idle-first == oldest-start, 무회귀 | integration | 🟢 green |
| REQ-019 | 관측성 카운터 | `MetricsTest#{scopeHookInjectionFailuresStartsAtZero, fallbackActivationsStartsAtZero, summaryIncludesNewCounters, installFailureIncrementsCounterAndDoesNotPropagate, unmappedTraceIdsStartsAtZeroAndCounts, evictedInFlightTracesStartsAtZeroAndCounts}`. C1+C2+C3a 전 카운터 완료 | unit/integration | 🟢 green |
| REQ-022 | 트레이서 모드 store 자동생성 | `TestStoreRegistryTest#{forCoverageKeyAutoCreatesWhenEnabled, forCoverageKeyStrictReturnsNullWhenDisabled}` | unit | 🟢 green |
| REQ-023 | C3 수집 + drain 대기 | `DistributedCollectIT#downstreamCollectedAfterDrain`(drain-wait 중 늦은 다운스트림 `.exec` 무유실 수집, 주입 Sleeper 결정론) | integration(최고 실현가능; Docker CDC는 분산 E2E에서 부수 실측) | 🟢 green |
| REQ-024 | scope-fail 동기 폴백 | GA-1 PASS → scope weave가 주 경로; choke-point 폴백 미발동, C3에서 분산 안전망으로 재평가. | integration | 🔵 deferred |
| REQ-020 | [Won't] messaging activator | — | — | 🔵 out-of-scope |
| REQ-021 | [Won't] 운영 per-request | — | — | 🔵 out-of-scope |

**C1 완료 상태 (DoD):**
- C1 Must REQ-001~010, REQ-022: **12/12 🟢** (분모 12, 분자 12)
- REQ-019 (미연기 Should): **🟢** — 전 카운터 완료: C1(scopeHookInjectionFailures + fallbackActivations) + C2(unmappedTraceIds) + C3a(evictedInFlightTraces)
- REQ-024 (Should): **🔵 deferred** — GA-1 PASS로 choke-point 폴백 경로 불필요, C3 재평가 → 분모 제외
- **C1 Must 커버리지: 12/12 (100%)**

**C2 완료 상태 (DoD):**
- C2 Must REQ-011, REQ-012: **2/2 🟢**
- C2 Should REQ-013, REQ-014: **2/2 🟢**
- **C2 Must+Should 커버리지: 4/4 (100%)** — REQ-019 추가 전진(unmappedTraceIds C2 완료)

**C3a 완료 상태 (DoD):**
- REQ-016 (Must) 🟢: idle reaper flush (JVM 종료 불필요)
- REQ-017 (Should) 🟢: late-write grace period (flush 후 늦은 쓰기 비유실)
- REQ-018 (Should) 🟢: idle-우선 eviction + evictedInFlightTraces 카운터
- REQ-019 (Should) 🟢: C1+C2+C3a 전 카운터 완료 (evictedInFlightTraces C3a에서 추가)
- **C3a Must+Should(대상) 커버리지: 4/4 (100%)**

Coverage(전체): C1 Must 12/12 🟢; C2 Must+Should 4/4 🟢; C3a REQ-016/017/018/019 4/4 🟢; **C3b: REQ-023 🟢(integration, 최고 실현가능), REQ-015 🟡 partial**(서비스 축 병합·수집·CLI 메커니즘 green + 전파 전제 GA-1/GA-2/R1 입증; 조립된 분산 Docker E2E는 전용 환경 실행 잔여). 제외: Won't 2 (🔵 REQ-020, REQ-021), deferred 1 (🔵 REQ-024).

**C3b 완료 상태:** REQ-023 🟢. REQ-015 🟡 partial — `DistributedCoverageMerger`(서비스 축)·`DistributedCollector`(drain-wait)·`TraceMergeMain`(CLI)가 in-process로 cross-service 병합/수집을 입증하고, trace 전파는 GA-1(C1 단일 서비스)·GA-2(C1 cross-JVM mindgraph)·R1(legacy-tram)로 입증됨. **남은 것은 이 조각들을 실제 다중-서비스 Docker 스택에서 조립한 black-box E2E**(`LegacyTram/TaintedSpringDistributedE2E`, `assumeTrue` env 게이트) — Docker·외부 환경(legacy-tram/tainted-spring) present로 실행 가능하나 전용 실행 단계. 이 E2E green 시 REQ-015 → 🟢로 매트릭스 100% 완성.

(Must: 001,002,003,004,005,006,007,008,009,010,011,012,015,016,022,023 = 16. Should: 013,014,017,018,019,024 = 6. C1 Must 대상: 001,002,003,004,005,006,007,008,009,010,022 = 11 + REQ-022 = 12.)
