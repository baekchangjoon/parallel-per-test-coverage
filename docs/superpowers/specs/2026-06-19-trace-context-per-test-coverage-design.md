# Trace Context 소비 기반 per-test 커버리지 (in-process async + 분산)

- **작성일:** 2026-06-19
- **상태:** Draft (brainstorming 승인 완료, 3-model 리뷰 대기)
- **대상 레포:** `parallel-per-test-coverage` (`agent` 모듈 중심)
- **관련 환경:** `~/github_tainted-spring`(OTel·8서비스·Kafka), `~/github_graph-rag-test-generator/graph-rag/samples/legacy-tram`(Brave/Sleuth·Eventuate Tram·B3)

---

## 1. 배경과 문제

pjacoco는 SUT에 붙은 JaCoCo probe 버퍼를 **testId별로 분할**해 per-test 커버리지를 만든다. 현재 testId는 다음 경로로 들어온다:

- `ServletAdvice` — `HttpServlet.service()` 진입에서 `baggage` 헤더를 직접 파싱(`BaggageParser`, `test.id` 멤버)해 자체 `ThreadLocal`(`CoverageContext`)에 세팅.
- `RunLeafAdvice`(JUnit4 zero-touch) / `CoverageControl`(in-process API) — 테스트 스레드에 `ThreadLocal` 세팅.

핫패스 `CoverageBridge.recordCoverage`는 이 `ThreadLocal`을 **probe당 1회 read**해 `TestStore`에 기록한다(트레이서 조회 없음 — 의도된 저비용).

### 1.1 한계 (해결 대상)

현재 전파는 **inbound choke point에서 ThreadLocal 1회 set, exit에서 clear**가 전부다. 그래서:

- **async/스레드 핸드오프 유실** — 핸들러가 executor/`@Async`/별도 스레드로 넘기면 그 스레드엔 `ThreadLocal`이 없어 커버리지가 유실·오귀속된다. `RunLeafAdvice`의 Javadoc에 박제된 한계(`@Test(timeout)`/`@Rule Timeout`은 새 스레드 → "silently empty")가 그 증거다.
- **서비스 경계를 못 넘음** — testId가 한 프로세스 안에서만 산다. Kafka/HTTP/Tram으로 연결된 downstream 서비스의 코드는 같은 테스트로 귀속되지 않는다.

### 1.2 핵심 통찰 — "merge가 아니라 consume"

전파(어느 스레드가 어느 테스트에 속하는가, async·프로세스 경계 포함)는 분산 트레이서(OpenTelemetry, Brave/Spring Cloud Sleuth)가 **이미 잘 풀어 둔 문제**다. pjacoco가 이를 재구현(자체 executor instrument 등)하는 것은 낭비다. pjacoco는 트레이서가 만들어 전파한 **trace context를 라우팅 키로 소비**만 한다. 버퍼 분할(`TestStore`)은 그대로 pjacoco의 몫이다.

> 이 문서는 "OTel agent를 확장해 JaCoCo 버퍼를 baggage id로 복제"하는 *merge* 안을 명시적으로 기각한다. 버퍼 분할은 트레이서가 주지 않는 부분이라 어느 agent에 host하든 사라지지 않으며, 커버리지가 트레이서에 하드 결합되는 비용이 크다. 대신 트레이서의 trace context를 *소비*한다.

---

## 2. 목표 / 비목표

### 목표
- 한 서비스 내에서 트레이서가 전파하는 모든 스레드(동기 + async 핸드오프)의 커버리지를 같은 test로 귀속한다.
- Kafka/HTTP/Tram으로 연결된 여러 서비스에 걸친 per-test 커버리지를, trace context를 키로 모은다.
- Brave/Sleuth와 OpenTelemetry 두 백엔드를 지원한다.

### 비목표
- 트레이서 전파 자체의 재구현(executor/Thread instrument)을 하지 않는다 — 트레이서에 위임한다.
- 운영(non-test) 트래픽의 per-request 커버리지는 범위 밖(테스트 하네스 용도).
- 트레이서가 닿지 못하는 경로(raw `new Thread()` 등 미instrument 핸드오프)의 완전 커버리지는 보장하지 않는다 — 트레이서와 동일한 한계를 상속하며, degradation으로 처리한다.

---

## 3. 불변 제약 (NON-NEGOTIABLE)

1. **핫패스 무변경.** `CoverageBridge.recordCoverage`는 여전히 `CoverageContext`(ThreadLocal) **1-read** → `store.record()`. probe당 트레이서 조회를 추가하지 않는다. (단위 테스트로 가드)
2. **트레이서 의존은 전부 reflective/optional.** 컴파일·런타임 하드 의존 0. Java 8 호환. 현 `BaggageParser`/`ServletAdvice`의 reflective 철학을 그대로 따른다(클래스로더 안전).
3. **best-effort, 앱 교란 금지.** 모든 신규 경로는 `catch (Throwable)`로 swallow하며 SUT로 throw하지 않는다. 커버리지 손실은 허용, 앱 크래시는 불가.

---

## 4. 범위와 단계화

한 spec에 C1→C2→C3를 담되, 구현(plan)은 phase로 끊어 **C1을 먼저 검증**한다.

| 단계 | 내용 |
|---|---|
| **C1** | 트레이서 trace context를 testId 소스로 in-process 소비 + async 핸드오프 전파(scope 브리지). 트레이서 부재 시 기존 servlet/junit/header 경로로 폴백. |
| **C2** | `traceId ↔ testId` 매핑. 기본은 control endpoint 등록, 미등록이면 traceId 자체를 testId로 폴백. |
| **C3** | 분산 집계. 각 서비스는 raw traceId로 per-trace 커버리지를 남기고, 중앙 수집이 러너의 `traceId→testId` 맵으로 서비스 간 병합. |

백엔드: **Brave/Sleuth + OTel 둘 다 구현.** E2E 환경 — Brave=`legacy-tram`, OTel=`tainted-spring`.

---

## 5. 아키텍처

### 5.1 유지 (현행 그대로)

- `CoverageContext` — `ThreadLocal<TestStore>`. 핫패스가 읽는 단일 지점. **무변경.**
- `CoverageBridge.recordCoverage` — ThreadLocal 1-read → `store.record()`. **무변경.**
- `TestStore` / `TestStoreRegistry` / `ClassProbes` — 키별 probe 버퍼. 키를 "coverage key"로 일반화(5.3).
- `InboundActivator`(servlet/junit4) + `BaggageParser` — **트레이서-부재 폴백 경로**로 강등(삭제하지 않음).

### 5.2 신규 컴포넌트 (각 단일 책임, reflective/optional)

1. **`TestIdSource` (SPI)** — "현재 스레드의 coverage key는?"
   - `OtelTestIdSource` — `io.opentelemetry.api.trace.Span.current()` / `Baggage` 에서 traceId를 reflective 추출.
   - `BraveTestIdSource` — `brave.Tracing.current().currentTraceContext().get()` 에서 traceId를 reflective 추출.
   - `LocalTestIdSource` — 기존 ThreadLocal/header 폴백.
   - resolve 우선순위: 살아있는 트레이서의 traceId가 있으면 그것, 없으면 local.

2. **`TraceScopeBridge` (백엔드당 1개)** — async 전파의 핵심. 트레이서의 scope 수명주기에 훅을 걸어, 트레이서가 어느 스레드든 trace context를 current로 만들 때 `CoverageContext`를 동기화한다.
   - OTel: `io.opentelemetry.context.ContextStorage` wrapper(`ContextStorage.addWrapper`).
   - Brave: `brave.propagation.CurrentTraceContext`의 `ScopeDecorator`.
   - scope enter → `traceId` resolve → `CoverageContext.set(registry.forKey(traceId))`; scope exit → 이전 값 복원(스택).
   - 트레이서가 전파하는 모든 스레드(executor/`@Async`/Kafka consumer)에 커버리지가 자동으로 따라온다. **C3를 가능케 하는 조각.**
   - ⚠️ 주입 가능성은 **GA-1**(§9)로 검증.

3. **`TestIdMappingRegistry`** — `traceId → testId(사람이 읽는 "Class#method")` 맵.
   - control endpoint로 등록(C2 기본). lookup이 미등록이면 입력 traceId를 그대로 반환(폴백).
   - in-process(트레이서 부재) 경로에서는 testId가 직접 들어오므로 매핑이 항등.

4. **분산 집계기** — 기존 `AggregateWriter` 확장 또는 신규 merger. 각 서비스가 남긴 per-traceId `.exec` + 러너의 `traceId→testId` 맵 → testId별·서비스별 병합 리포트.

### 5.3 "coverage key" 일반화

`TestStore`/registry는 불투명 **coverage key**로 기록한다:
- 트레이서 모드 → key = **traceId**.
- local 모드(트레이서 부재; JUnit/servlet-header) → key = **testId**(현행 `Class#method`).

사람이 읽는 testId 매핑은 **출력/집계 시점에만** 적용한다. 덕분에 핫패스·store 구조가 무변경이며, 서비스는 transport(HTTP/Kafka/Tram)를 몰라도 trace context만 소비하면 된다.

### 5.4 컴포넌트 경계

`TestIdSource`(누구냐) / `TraceScopeBridge`(언제 바인딩하냐) / `TestIdMappingRegistry`(키↔이름) / 집계기(서비스 간 병합) — 4개가 인터페이스로 분리되어 독립적으로 단위 테스트 가능하다.

---

## 6. 데이터 흐름

### 6.1 C1 — 한 서비스 내 (트레이서 있음)

```
요청/메시지 진입 → 트레이서가 trace context 생성·current 설정
  → TraceScopeBridge.onScopeEnter: traceId 추출 → CoverageContext.set(store[traceId])
  → [핸들러 실행: 동기 코드 + executor/@Async 핸드오프]
      → 트레이서가 자식 스레드에 context 전파 → 그 스레드도 onScopeEnter → 같은 store 바인딩  ★갭 닫힘
  → probe 실행마다 CoverageBridge: ThreadLocal 1-read → store[traceId].record()
  → scope exit: CoverageContext 이전 값 복원
```
트레이서 부재 시: 기존 servlet/junit advice가 ThreadLocal set/clear (현행 폴백).

### 6.2 C2 — 매핑

테스트 시작 시 러너가 traceId(고정 주입 또는 응답에서 회수)와 testId를 `TestIdMappingRegistry`에 등록한다. 미등록이면 traceId가 곧 testId.

### 6.3 C3 — 분산 (예: tainted-spring, BFF→Kafka→8서비스)

```
블랙박스 테스트 → BFF 요청(traceId T 시작) → OTel javaagent가 HTTP+Kafka로 T 전파
  → 각 서비스 pjacoco: TraceScopeBridge가 T로 store 바인딩 → per-T 커버리지 기록
  → 각 서비스가 traceId T 별 .exec 출력 (서비스는 testId 모름)
테스트 종료 → 러너가 {T → "Class#method"} 맵을 중앙에 보고
중앙 집계기 → 8서비스의 per-T .exec를 testId로 merge → testId별·서비스별 리포트
```

핵심: 서비스는 transport를 몰라도 trace context만 소비. async도 트레이서 전파에 올라타므로 별도 weaving 불필요.

---

## 7. 에러 처리와 Degradation

**원칙:** 커버리지 손실은 허용, 앱 교란은 금지. 모든 신규 경로 best-effort.

**Degradation 사다리 (위 → 아래로 자동 폴백):**

| 상황 | 동작 |
|---|---|
| 트레이서 + scope 훅 주입 성공 | scope 브리지로 async 포함 완전 소비 (이상) |
| 트레이서 있으나 scope 훅 주입 실패 | choke-point에서 traceId read만(동기 OK, async 유실) + 1회 로그 |
| 트레이서 부재 | 기존 servlet/junit/header 폴백 (현행 동작) |
| traceId 매핑 미등록 | traceId를 testId로 사용(리포트에 raw traceId 노출) |
| probe에서 store=null | 조용히 skip (현행) |

**구체 실패 모드:**
- **scope 훅 주입 불가** — 트레이서 버전이 wrapper/decorator API를 안 주거나 사후 주입 불가 → C1 spike(GA-1)에서 조기 발견 → choke-point read로 폴백.
- **중첩 scope / scope 누수** — enter에서 이전 store를 스택에 저장, exit에서 정확히 복원. 누수 방어로 핸들러 종료 시 강제 clear.
- **traceId 재사용 오염** — 러너가 테스트당 distinct traceId 보장(매핑 등록 모델이 자연히 강제).
- **CDC/Kafka 비동기 지연** — 메시지가 테스트 종료 후 도착(Tram outbox→CDC). 집계기는 테스트 종료 후 드레인 대기(타임아웃) 후 수집. legacy-tram E2E가 이미 이 지연을 다룬다.
- **핫패스 오염 금지** — 매핑 lookup/scope 훅은 진입 시점에만. 핫패스는 여전히 ThreadLocal 1-read.

**관측성:** 기존 `Metrics` 확장 — scope 훅 주입 성공/실패, 미매핑 traceId 수, 폴백 발동을 카운트해 "조용한 유실"을 가시화.

---

## 8. 테스트 전략

**더블 루프:** E2E 먼저(red) → 안쪽 단위 TDD(red→green→refactor) → 전체 green. phase별 E2E는 요구사항명세에서 도출해 REQ-ID로 태깅한다.

### 8.1 단위 (안쪽 루프)
- `TestIdSource` 구현별 — mock된 OTel/Brave context에서 traceId 추출, 부재 시 local 폴백.
- `TraceScopeBridge` — scope enter/exit 시 `CoverageContext` set/복원, 중첩 scope 스택, 누수 방어.
- `TestIdMappingRegistry` — 등록/lookup/미등록 폴백.
- 집계기 — per-traceId `.exec` N개 + 맵 → testId 병합 정확성.
- **핫패스 불변 가드** — `recordCoverage`가 여전히 ThreadLocal 1-read(트레이서 호출 0)임을 단언.

### 8.2 E2E (바깥 루프) — phase별 acceptance

- **C1 (먼저 검증):** GA-1 spike 통과 후, "executor/`@Async`로 넘긴 작업의 커버리지가 같은 testId에 귀속" E2E. 환경: 단일 서비스(legacy-tram의 reservation 또는 tainted 1개). *red 재현: 현재는 async 작업 커버리지 0.*
- **C2:** 매핑 등록 시 리포트가 `Class#method`로, 미등록 시 raw traceId로 나오는 것 검증.
- **C3 (Brave):** `legacy-tram` 풀스택 — order-web→reservation→(Tram/Kafka/CDC)→ledger. 한 테스트의 traceId가 ledger까지 따라가 3서비스 병합 리포트에 잡힘. (R1이 trace 생존을 이미 입증 → 본 작업은 *커버리지* 귀속으로 확장.)
- **C3 (OTel):** `tainted-spring` 8서비스 — `JAVA_TOOL_OPTIONS`에 OTel javaagent + pjacoco 주입, 블랙박스 테스트 1건이 BFF→Kafka 경유 다수 서비스에 per-test로 귀속. (GA-2 전제.)

### 8.3 완료 정의
대상 REQ(Must + 미연기 Should) 100% green + phase별 E2E 통과 + 핫패스 불변 가드 통과.

---

## 9. 선결 검증 가정 (Gating Assumptions, GA-ID)

설계가 참으로 가정하지만 코드로 아직 입증되지 않은 전제. 각 항목은 해당 phase 착수 전 spike로 검증하며, 거짓일 경우 §7 degradation 사다리로 폴백한다.

- **GA-1 (C1 게이트):** OTel `ContextStorage` wrapper / Brave `ScopeDecorator`를 우리가 쓰는 버전에서, 그리고 **앱이 이미 구성한 트레이서에 사후(reflective)로** 주입할 수 있다. (특히 Sleuth가 자동 구성한 Brave `CurrentTraceContext`에 데코레이터를 나중에 추가하는 것이 핵심 불확실성.)
  - 거짓이면: scope 브리지 불가 → choke-point read 폴백, async 갭은 weaving한 transport만큼만 닫힘.
- **GA-2 (C3-OTel 전제):** OTel javaagent가 `tainted-spring`의 Kafka 홉에서 trace context를 자동 전파한다(HTTP뿐 아니라 Kafka record header 주입/추출).
  - 참고 반례: Eventuate Tram(Brave)은 자동 전파가 안 돼 전용 `eventuate-tram-spring-cloud-sleuth-tram-starter`가 필요했다(legacy-tram R1에서 확인). "자동 전파"를 당연시하지 않는다.
  - 거짓이면: Kafka 경계용 명시 전파(인터셉터/헤더)로 폴백.

---

## 10. 미해결 질문 (구현 중 확정)

- `TestIdMappingRegistry` 등록을 어느 testkit 어댑터(JUnit5/4/RestAssured)에서 주입할지의 정확한 API 형태.
- 분산 집계기의 출력 포맷(기존 per-test `.exec`/`.json` 스키마 재사용 범위 + 서비스 차원 추가 방식).
- 중앙 수집의 트리거(테스트 종료 hook vs 별도 CLI 단계)와 CDC 드레인 타임아웃 기본값.

---

## 11. 참고 좌표

- 현행 코드: `agent/src/main/java/io/pjacoco/agent/{context/CoverageContext,probe/CoverageBridge,api/CoverageControl,store/TestStore,inbound/...}.java`
- Brave E2E: `~/github_graph-rag-test-generator/graph-rag/samples/legacy-tram`(Boot 2.7.18, Sleuth 3.1.9, Tram 0.35.0, `eventuate-tram-spring-cloud-sleuth-tram-starter:0.5.0`, CDC 0.17.0, Java 8).
- OTel E2E: `~/github_tainted-spring/tainted-spring-platform`(8서비스 `com.tainted.*`, Kafka 7.6.1, `JAVA_TOOL_OPTIONS` 슬롯, 현재 stock JaCoCo tcpserver).
