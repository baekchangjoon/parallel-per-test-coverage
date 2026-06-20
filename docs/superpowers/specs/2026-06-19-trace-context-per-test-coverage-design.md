# Trace Context 소비 기반 per-test 커버리지 (in-process async + 분산)

- **작성일:** 2026-06-19
- **상태:** Approved (brainstorming 승인 + 3-vendor design 리뷰 반영 + 사용자 승인 완료) — 다음: 요구사항명세
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
2. **트레이서 의존은 optional, 런타임 하드 의존 0.** Java 8 호환. **런타임** 하드 의존이 없는 것이 불변이다(트레이서 부재 시 graceful 폴백). 트레이서를 볼 수 있는 classloader에 거주하는 코드(OTel extension, app-CL에 인라인되는 ByteBuddy 우븐 advice)는 `compileOnly` 의존으로 타입-세이프하게 컴파일해도 된다; bootstrap/agent-CL 거주 코드는 현 `ServletAdvice`의 reflective 패턴(`Class.forName` + `Method.invoke`)을 따른다. (`BaggageParser`는 순수 문자열 파서로 reflection을 쓰지 않으므로 이 항의 모델이 아니다.) **classloader 경계 주의:** pjacoco 핵심은 bootstrap/agent classloader에서 로드되는 반면 `brave.*`/`io.opentelemetry.*`는 app classloader에 있으므로, 트레이서 API 접근은 thread context classloader(또는 핸드에 든 객체의 classloader)를 통해 해소한다 — bootstrap에서 직접 `Class.forName`하면 `ClassNotFoundException`. → §9 GA-3.
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
- `TestStore` / `TestStoreRegistry` / `ClassProbes` — 키별 probe 버퍼. 키 의미를 "coverage key"로 일반화하되 **필드/타입은 무변경**(5.3 참조).
- 폴백 경로(트레이서-부재)로 **강등하되 삭제하지 않음**, 실제 클래스: `ServletInboundActivator`(javax+jakarta) + `ServletAdvice`, `JUnit4InboundActivator` + `RunLeafAdvice`, in-process `CoverageControl`/`InProcessBridge`, 그리고 `BaggageParser`(header 파싱). testkit의 두 경로(`Pjacoco` control-URL + `InheritableThreadLocal` vs trace-consumer)도 폴백 측에 유지.
- ⚠️ **모드 우선순위(중요):** `Bootstrap`은 `ServletInboundActivator`를 항상 설치하며 `ServletAdvice`는 매 `service()`마다 baggage `test.id`로 `CoverageContext`를 세팅한다. 트레이서 모드에서 `TraceScopeBridge`와 같은 스레드에서 충돌하면 **둘 중 하나가 다른 쪽 바인딩을 덮어쓴다.** 규칙: **`TraceScopeBridge`가 활성이면 inbound advice의 바인딩을 no-op**(또는 양쪽을 `TestIdSource` 단일 우선순위로 라우팅)한다. servlet scope enter/exit 대비 trace scope enter/exit 순서를 명시한다(§9 GA-3와 함께 spike).

### 5.2 신규 컴포넌트 (각 단일 책임, reflective/optional)

1. **`TestIdSource` (SPI)** — "현재 스레드의 coverage key는?"
   - `OtelTestIdSource` — `io.opentelemetry.api.trace.Span.current().getSpanContext()`에서 traceId를 reflective 추출. **반드시 `SpanContext.isValid()`가 true일 때만**(non-zero trace/span). invalid(no-op span, traceId=`0…0`)이면 local로 폴백 — zero-traceId를 키로 삼아 untraced 커버리지를 한 phantom store로 합치는 것을 방지. **traceId는 Baggage가 아니라 SpanContext에 있다** — Baggage(현 `test.id`)는 `LocalTestIdSource`의 선택적 폴백 소스로만.
   - `BraveTestIdSource` — `brave.Tracing.current().currentTraceContext().get()`의 `TraceContext.traceIdString()`에서 추출(null/invalid이면 폴백).
   - `LocalTestIdSource` — 기존 ThreadLocal/header 폴백.
   - resolve 우선순위: 살아있는 트레이서의 valid traceId가 있으면 그것, 없으면 local. classloader 경계는 §3-2/GA-3.

2. **`TraceScopeBridge` (백엔드당 1개)** — async 전파의 핵심. 트레이서가 어느 스레드든 trace context를 current로 만들 때 `CoverageContext`를 동기화한다. **주입 메커니즘은 트레이서별로 다르며, 명명한 후보는 GA-1로 검증·확정한다:**
   - **OTel** — ✅ **GA-1/GA-3 spike(Task 1,3)로 확정·green:** `ContextStorageProvider` SPI extension은 FAIL(`AgentContextStorage`가 래핑 storage로 위임 안 함, extension은 `ExtensionClassLoader`라 app 불가시)이므로, **채택 = OTel agent의 shaded `ContextStorage.attach()`(OTel 2.11.0에선 **bootstrap-loaded**) ByteBuddy weave** — request+executor 스레드 동일 traceId attach/detach green 입증. T10 필수 사항: 커스텀 `PoolStrategy` + OTel jar `ClassFileLocator.ForJarFile`(shaded supertype 해소), trace-id getter는 **public shaded `Span`/`SpanContext` 인터페이스**로 호출(concrete `SdkSpan` non-public → `IllegalAccessException`), retransform OK(`isModifiableClass=true`), shaded prefix는 OTel 버전별 핀(`hasSuperType` matcher 권장).
   - **Brave** — `ScopeDecorator`는 `CurrentTraceContext.Builder.addScopeDecorator()`로 **build 시점에만** 등록되고 build 후 인스턴스는 불변이라 사후 reflective 주입은 깨지기 쉽다. 후보 우선순위: (a) Spring `CurrentTraceContextCustomizer` 빈(단, Spring 통합 필요 — pure-agent 제약과 상충), (b) Sleuth가 만든 `CurrentTraceContext`의 scope 진입/탈출 메서드를 **ByteBuddy weave**(주 대안), (c) 빈 교체. spike가 (b)를 우선 시도.
   - 동작: scope enter → traceId resolve → `CoverageContext.set(<coverage key의 store>)`; scope exit → **이전 값 복원**. **복원 상태는 ThreadLocal 스택이 아니라 enter가 반환하는 scope 객체의 필드에 보관**(Brave `ThreadLocalCurrentTraceContext`의 revert 방식과 동일)하여, scope가 연 스레드와 닫는 스레드가 다를 때(예: CompletableFuture 체인) 닫는 스레드의 컨텍스트를 오염시키지 않는다.
   - 트레이서가 전파하는 모든 스레드(executor/`@Async`/Kafka consumer)에 커버리지가 자동으로 따라온다. **C3를 가능케 하는 조각.**

3. **`TestIdMappingRegistry`** — `traceId → testId(사람이 읽는 표준화된 "FQCN#method")` **N:1** 맵(한 testId가 여러 traceId를 가질 수 있음 — 테스트가 다수 outbound 호출/재시도를 내므로). 집계기는 같은 testId의 모든 per-traceId store를 출력 시점에 병합.
   - **등록 경로(C2 기본):** `ControlEndpoint`에 신규 엔드포인트 추가 — `POST /__coverage__/trace/map?traceId=<T>&testId=<FQCN%23method>`(기존엔 `/test/start`·`/test/stop`만 존재). 미등록 lookup은 입력 traceId를 그대로 반환(폴백).
   - **C3에선 per-service 등록 불필요:** 서비스는 traceId로만 기록하고, 러너가 `traceId→testId` 맵을 **중앙에 한 번** 보고하여 집계 시점에 적용(per-service fan-out 회피). 단일 서비스 편의용으로만 control-endpoint 등록을 쓴다.
   - **bounded:** traceId 카디널리티가 높으므로 매핑 저장소는 TTL 또는 LRU 상한을 둬 장기 실행 서비스의 OOM을 막는다.
   - **testId 형식 정규화:** 현 코드가 불일치한다 — `RunLeafAdvice`는 `Description.getClassName()`(FQCN), `PjacocoExtension`/`PjacocoInProcessRule`은 `getSimpleName()`/혼재. 매핑 등록 시 **FQCN#method로 정규화**를 강제한다.
   - in-process(트레이서 부재) 경로에서는 testId가 직접 들어오므로 매핑이 항등.

4. **`TraceCoverageMerger` (신규)** — ⚠️ 기존 `AggregateWriter`를 확장하지 **않는다**: `AggregateWriter`는 JVM 종료 시 JaCoCo whole-run `RuntimeData`를 단일 `.exec`로 덤프할 뿐 per-TestStore/cross-service 병합 개념이 없다. 신규 merger는 `ExecWriter`가 쓰는 per-store 스냅샷(`<key>.exec`/`.json`) 위에 선다. 입력: 각 서비스의 per-traceId `.exec` + 러너의 `traceId→testId` 맵. 출력: testId별·서비스별 병합 리포트. classId 충돌: JaCoCo classId는 클래스 바이트코드에 결정적이라 서로 다른 서비스가 같은 코드를 공유하지 않는 한 충돌하지 않음(공유 시 서비스 차원으로 분리). **C2에서 단일-서비스 형태(`io.pjacoco.agent.output.TraceCoverageMerger`)를 도입했으며, 서비스 축·drain-wait·중앙 수집 토폴로지는 C3에서 확장한다.** 병합은 `ExecFileLoader` + `ExecutionDataStore.put()` → `ExecutionData.merge()`(OR-merge; public `merge()` 없음 — `put()`이 동일 classId 시 내부적으로 `ExecutionData.merge()` 호출)로 구현. JSON sidecar 병합은 C2 명시적 non-goal. 러너/CI가 오프라인으로 호출하며, Bootstrap은 merge를 배선하지 않고 in-place merge는 금지. **⚠️ non-goal (C2): JSON sidecar 병합.**

### 5.3 "coverage key" 일반화 — 표현 명시

`TestStore`/registry는 키를 의미상 불투명 **coverage key**로 다루되, **기존 `String testId` 슬롯/필드/타입을 그대로 재사용한다**(rename 없음, JSON 스키마 무변경). 즉:
- 트레이서 모드 → `testId` 슬롯에 **traceId 문자열**이 그대로 들어간다(예: `.exec` 파일명·JSON `"testId"`에 `4bf92f3577…` 같은 raw traceId가 노출됨 — **의도된 중간 산출물**).
- local 모드(트레이서 부재; JUnit/servlet-header) → 슬롯에 **testId**(`FQCN#method`)가 그대로.

사람이 읽는 testId로의 변환(**display 매핑**)은 **병합/리포트 시점에만** 적용한다(scope enter의 **coverage-key lookup**과 구분 — §7). registry API는 다음 중 하나로 명시(구현 시 확정): 기존 `active/peek`를 불투명 키로 받게 일반화하거나 `forCoverageKey(String)` 신설 — 둘 다 create/lookup 의미를 문서화. 덕분에 핫패스·store 구조가 무변경이고, 서비스는 transport(HTTP/Kafka/Tram)를 몰라도 trace context만 소비하면 된다.

**strict 모드 상호작용(중요):** `TestStoreRegistry.active()`는 `autoRegister=false`(현 `AgentOptions` 기본)면 미등록 키에 null을 반환 → probe가 silent skip → 트레이서 모드에서 store가 안 생겨 C1/C3 목표와 모순. 따라서 **트레이서 모드는 `autoRegister=true`(또는 전용 `traceKeyAutoCreate` 플래그)를 요구**하거나, scope enter에서 `start(traceId)`를 선행한다. 블랙박스 하네스의 traceId 자동 생성과 기존 testId start/stop의 관계를 구현 시 문서화.

### 5.4 컴포넌트 경계

`TestIdSource`(누구냐) / `TraceScopeBridge`(언제 바인딩하냐) / `TestIdMappingRegistry`(키↔이름) / `TraceCoverageMerger`(서비스 간 병합) — 4개가 인터페이스로 분리되어 독립적으로 단위 테스트 가능하다.

### 5.5 Bootstrap 배선과 설치 순서

`Bootstrap.premain`은 app의 Spring/OTel SDK 기동 **이전**에 pjacoco를 배선한다. 따라서 scope 훅 주입은 설치 순서 의존이 있다 — (i) agent premain, (ii) OTel javaagent, (iii) Spring Sleuth auto-config의 상대 순서. OTel extension(SPI) 경로는 javaagent 확장 로딩 시점에 등록되고, Brave weave 경로는 클래스 로드 시점에 적용되므로 premain의 즉시 주입과 별개로 동작한다. 주입 실패는 감지하여(아래 metrics) **폴백 사다리**(§7)로 내려가며, GA-1 spike 결과가 프로덕션 배선에서 "scope 브리지 vs choke-point-only"를 게이트한다.

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

### 6.4 trace-store 생명주기 (flush / eviction)

현 flush 경로는 `ControlEndpoint`의 `/test/stop`(→ `ExecWriter.write`) 또는 JVM 종료 시 partial dump뿐이며, **둘 다 testId 키 기준**이다. 트레이서 모드의 traceId-키 store에는 trace-scoped stop도, span-end 훅도, 타이머 flush도 없다 — 장기 실행 서비스(예: tainted-spring의 8서비스는 상시 가동)는 JVM 종료에 의존할 수 없다. 따라서 신규 정책을 둔다:

- **flush 트리거:** 다음 중 하나로 traceId store를 `<traceId>.exec`로 내린다 — (a) **idle 타이머(reaper)**: 일정 시간 무업데이트인 traceId store를 백그라운드로 flush+evict, (b) 루트 scope close 시점 flush(단, async 잔여 작업이 있으면 grace period), (c) 명시적 control API. C3 집계는 테스트 종료 후 **드레인 대기 타임아웃** 뒤 수집(legacy-tram의 CDC 지연을 이미 반영).
- **late-write grace period:** `/stop`/flush 이후에도 비동기/다운스트림 스레드가 같은 store에 기록할 수 있다. flush 직후 evict하면 그 쓰기는 유실된다. → flush 후 일정 grace 동안 store를 유지하고 늦은 쓰기를 append/merge하거나, evict 전 마지막 스냅샷을 재flush.
- **메모리/eviction:** `TestStoreRegistry`는 `maxStores`(기본 1000)로 오래된 store를 partial dump하며 evict한다. traceId 키는 카디널리티가 높아(요청마다 신규) **in-flight trace가 mid-trace에 evict될 위험**이 있다. → 트레이서 모드 전용 cap/정책(별도 cap 또는 idle-우선 eviction)과 metrics 경보를 둔다. `active()` 경로에서도 cap/idle 정리가 동작하도록 한다.

### 6.5 C3 수집 토폴로지

8개 서비스 JVM의 per-traceId `.exec`가 중앙 collector로 모이는 방법을 명시한다(tainted-spring 배포 정렬): 공유 볼륨/파일 경로, 또는 control-pull(서비스가 per-traceId `.exec`를 HTTP로 노출 → 러너가 수집), 또는 CI 아티팩트 업로드 중 택1. 수집 트리거(테스트 종료 hook vs 별도 CLI)와 CDC 드레인 타임아웃 기본값은 구현 시 확정(§10).

---

## 7. 에러 처리와 Degradation

**원칙:** 커버리지 손실은 허용, 앱 교란은 금지. 모든 신규 경로 best-effort.

**Degradation 사다리 (위 → 아래로 자동 폴백):**

| 상황 | 동작 |
|---|---|
| 트레이서 + scope 훅 주입 성공 | scope 브리지로 async 포함 완전 소비 (이상) |
| 트레이서 있으나 scope 훅 주입 실패 | choke-point read 폴백(아래) + 1회 로그 |
| 트레이서 부재 | 기존 servlet/junit/header 폴백 (현행 동작) |
| traceId 매핑 미등록 | traceId를 testId로 사용(리포트에 raw traceId 노출) |
| probe에서 store=null | 조용히 skip (현행) |

**choke-point read 폴백의 정직한 한계(surface별):** scope 훅이 실패하면 "진입점에서 traceId를 read해 ThreadLocal set"으로 내려가는데, **진입점이 있는 surface에서만** 동작한다 —
- HTTP servlet(`ServletAdvice`): `service()` 진입에서 `Span.current().getSpanContext().getTraceId()`를 reflective read(단 OTel javaagent advice가 pjacoco advice보다 먼저 실행돼 span을 set해야 함 — **두 agent의 ByteBuddy advice 순서 의존**, GA-3 spike 범위). 동기 경로만 커버, async 유실.
- JUnit `runLeaf`(`RunLeafAdvice`): 기존대로.
- **Kafka consumer / `@Async` 워커:** 현재 **진입점 advice가 없다** → scope 훅 실패 시 이들 경로는 **커버리지 0**. 이 한계를 숨기지 않고 metrics+로그로 노출하며, 필요 시 messaging activator 추가는 별도 과제.

**구체 실패 모드:**
- **scope 훅 주입 불가** — GA-1에서 조기 발견 → 위 surface별 choke-point read로 폴백(Kafka/@Async는 미커버).
- **cross-thread scope close** — scope를 연 스레드와 닫는 스레드가 다를 수 있음(CompletableFuture 등). 복원 상태를 **scope 객체 필드**에 보관해 닫는 스레드 오염 방지(§5.2-2). 단위 테스트: A에서 열고 B에서 닫아 B 컨텍스트 무오염 단언.
- **traceId 재사용 오염** — 러너가 테스트당 distinct traceId 보장(매핑 등록 모델이 자연히 강제).
- **late-write / flush 경합 & eviction** — §6.4 생명주기 정책으로 처리(grace period, idle reaper, 트레이서 모드 cap).
- **CDC/Kafka 비동기 지연** — 메시지가 테스트 종료 후 도착(Tram outbox→CDC). 집계기는 드레인 대기(타임아웃) 후 수집. legacy-tram E2E가 이미 이 지연을 다룬다.
- **핫패스 오염 금지** — **coverage-key lookup(scope enter)·display 매핑(병합 시점)** 모두 핫패스 밖. 핫패스는 여전히 ThreadLocal 1-read.

**관측성:** `io.pjacoco.agent.observability.Metrics`에 신규 카운터 추가(현재 `testsCompleted`/`partialDumps`/`swallowedExceptions`/`rejectedUnregistered`/`retriesOverwritten`에 더해): `scopeHookInjectionFailures`, `unmappedTraceIds`, `fallbackActivations`(+ 가능하면 `evictedInFlightTraces`). "조용한 유실"을 가시화.

---

## 8. 테스트 전략

**더블 루프:** E2E 먼저(red) → 안쪽 단위 TDD(red→green→refactor) → 전체 green. phase별 E2E는 요구사항명세에서 도출해 REQ-ID로 태깅한다.

### 8.1 단위 (안쪽 루프)
- `TestIdSource` 구현별 — mock된 OTel/Brave context에서 traceId 추출, 부재 시 local 폴백.
- `TraceScopeBridge` — scope enter/exit 시 `CoverageContext` set/복원, 중첩 scope, **cross-thread close**(A에서 열고 B에서 닫아 B 무오염) 단언.
- `TestIdMappingRegistry` — 등록/lookup/미등록 폴백.
- 집계기 — per-traceId `.exec` N개 + 맵 → testId 병합 정확성.
- **핫패스 불변 가드** — `recordCoverage`가 여전히 ThreadLocal 1-read(트레이서 호출 0)임을 단언.

### 8.2 E2E (바깥 루프) — phase별 acceptance

- **C1 (먼저 검증):** GA-1 spike 통과 후, "executor/`@Async`로 넘긴 작업의 커버리지가 같은 testId에 귀속" E2E. 환경: 단일 서비스(legacy-tram의 reservation 또는 tainted 1개). *red 재현: 현재는 async 작업 커버리지 0.*
- **C2:** 매핑 등록 시 리포트가 `Class#method`로, 미등록 시 raw traceId로 나오는 것 검증.
- **C3 (Brave):** `legacy-tram` 풀스택 — order-web→reservation→(Tram/Kafka/CDC)→ledger. 한 테스트의 traceId가 ledger까지 따라가 3서비스 병합 리포트에 잡힘. (R1이 trace 생존을 이미 입증 → 본 작업은 *커버리지* 귀속으로 확장.)
- **C3 (OTel):** `tainted-spring` 8서비스 — `JAVA_TOOL_OPTIONS`에 OTel javaagent + pjacoco 주입, 블랙박스 테스트 1건이 BFF→Kafka 경유 다수 서비스에 per-test로 귀속. (GA-2 전제.)

### 8.3 완료 정의
대상 REQ(Must + 미연기 Should) 100% green + phase별 E2E 통과 + 핫패스 불변 가드 통과. **REQ-ID 표 자체는 본 design spec이 아니라 다음 단계의 동반 문서(`requirements-spec` 스킬이 산출하는 요구사항명세)가 보유**한다 — 본 §8의 E2E는 거기서 REQ-ID로 태깅된다(전방 참조).

---

## 9. 선결 검증 가정 (Gating Assumptions, GA-ID)

설계가 참으로 가정하지만 코드로 아직 입증되지 않은 전제. 각 항목은 해당 phase 착수 전 spike로 검증하며, 거짓일 경우 §7 degradation 사다리로 폴백한다.

- **GA-1 (C1 게이트): scope 훅 주입 가능성.** spike로 확정 — 결과 반영:
  - **Brave: ✅ PASS (Task 2).** 불변 `ThreadLocalCurrentTraceContext`의 `newScope/maybeScope` + `Scope.close()` ByteBuddy weave로 sync+@Async 동일 traceId enter/exit 관측. 채택.
  - **OTel: ✅ PASS (Task 1+3).** SPI extension FAIL → shaded `ContextStorage.attach()` ByteBuddy weave가 green(request+executor 동일 traceId). PoolStrategy+OTel-jar locator, public shaded 인터페이스 호출 필수.
  - 거짓이면: scope 브리지 불가 → §7 surface별 choke-point read 폴백(Kafka/@Async 미커버).
- **GA-3 (classloader & 순서): ✅ PASS (Task 3).** 공유 `CoverageContext`는 **bootstrap classloader** 배치(양 backend 우븐 advice에서 도달, round-trip 입증). 2-agent CLI 순서 무관(OTel-first/pjacoco-first 모두 green), retransform 가능, choke-point가 valid span 관측.
- **GA-2 (C3-OTel 전제):** OTel javaagent가 `tainted-spring`의 Kafka 홉에서 trace context를 자동 전파한다(HTTP뿐 아니라 Kafka record header 주입/추출).
  - 참고 반례: Eventuate Tram(Brave)은 자동 전파가 안 돼 전용 `eventuate-tram-spring-cloud-sleuth-tram-starter`가 필요했다(legacy-tram R1에서 확인). "자동 전파"를 당연시하지 않는다.
  - 거짓이면: Kafka 경계용 명시 전파(인터셉터/헤더)로 폴백. (C3 게이트 — C1 범위 밖, C3 plan에서 검증.)

---

## 10. 미해결 질문 (구현 중 확정)

- `TestIdMappingRegistry` 등록을 어느 testkit 어댑터(JUnit5/4/RestAssured)에서, 어떤 메서드 형태로 주입할지(엔드포인트 계약은 §5.2-3에 명시; 어댑터 측 API만 미정).
- ~~`TraceCoverageMerger` 출력 포맷의 서비스 차원 표현(기존 `.exec`/`.json` 스키마에 service 축을 어떻게 더할지)과 `ExecutionDataStore.merge()` 적용 가부.~~ → **해소 (C2):** `ExecFileLoader` OR-merge(`ExecutionDataStore.put()` → `ExecutionData.merge()`)로 구현 완료; 서비스 차원 표현은 C3 과제.
- §6.5 수집 토폴로지 택1 확정과 CDC 드레인 타임아웃·idle-reaper·grace period 기본값.
- registry 키 일반화 형태(기존 `active/peek` 일반화 vs `forCoverageKey` 신설) 및 트레이서 모드 auto-create 플래그명.

---

## 11. 참고 좌표

- 현행 코드: `agent/src/main/java/io/pjacoco/agent/{context/CoverageContext,probe/CoverageBridge,api/CoverageControl,store/TestStore,inbound/...}.java`
- Brave E2E: `~/github_graph-rag-test-generator/graph-rag/samples/legacy-tram`(Boot 2.7.18, Sleuth 3.1.9, Tram 0.35.0, `eventuate-tram-spring-cloud-sleuth-tram-starter:0.5.0`, CDC 0.17.0, Java 8).
- OTel E2E: `~/github_tainted-spring/tainted-spring-platform`(8서비스 `com.tainted.*`, Kafka 7.6.1, `JAVA_TOOL_OPTIONS` 슬롯, 현재 stock JaCoCo tcpserver).
