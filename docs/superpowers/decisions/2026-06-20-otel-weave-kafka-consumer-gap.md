# 발견 기록(C3 역전파 시드) — OTel scope weave가 실제 Spring Kafka consumer 경로에서 미발동

> 상태: **C3 첫 조사 REQ로 박제** · 날짜: 2026-06-20 · 범위: **C3(분산)**, C1 아님 · 출처: Task 15 OTel out-of-process E2E(tainted-spring)
> 결정 경위: out-of-process OTel E2E가 cross-service 갭 발견 → 비서 결정(record `26775a36`): 옵션 A(C1 완료, 갭은 C3 역전파) + 게이트(C1 단일-JVM 검증이 합성이 아님을 선확인) + 아티팩트 박제.

## 요약

tainted-spring(실제 MSA)에서 pjacoco agent + OTel javaagent v2.11.0로 out-of-process E2E 실행 결과:
- **diary(단일 JVM, 요청 스레드)**: traceId `1111…` per-trace exec에 13개 클래스 귀속 ✅ (단, baggage fallback 경로).
- **mindgraph(별도 JVM, Kafka consumer 스레드)**: 동일 traceId store는 생성됐으나 `classCount=0` — **OTel scope weave가 Kafka consumer 스레드에서 `CoverageContext`를 설정하지 못함** ❌.
- OTel 자체는 traceId를 consumer까지 정상 전파(`diary.created process` CONSUMER 스팬이 동일 traceId로 mindgraph에서 관측됨).

이는 **cross-JVM Kafka 핸드오프 = C3(분산)** 시나리오로 **C1 범위 밖**이다(C1 = 단일-서비스 async). 따라서 C1 완료를 막지 않으며, 본 갭은 **C3 착수 시 첫 번째로 닫아야 할 조사 항목**이다.

## C1 단일-JVM 검증이 합성이 아님(게이트 통과 — 비서 조건 ①)

비서 조건: in-process `otelWeaveE2e`가 *실제* shaded `ThreadLocalContextStorage.attach` 경로를 타는지 선확인. 합성이면 단일-JVM async 검증도 과대표집이고 그 자체가 C1 결함.

**확인 결과 — 통과:** `agent/src/otelE2e/java/com/example/otelsut/OtelSut.java`는
(1) 실제 OTel javaagent가 등록한 `GlobalOpenTelemetry.getTracer(...)`를 사용하고,
(2) `span.makeCurrent()`(= `io.opentelemetry.context.Context.makeCurrent()` → `ContextStorage.get().attach(context)`)로 요청 스레드에,
(3) `Context.current().wrap(runnable)`로 executor worker 스레드에 컨텍스트를 재부착한다.
`AsyncWorker.compute`(worker 스레드)의 커버리지가 traceId exec에 기록되려면 worker 스레드의 `CoverageContext`가 set돼야 하고, 그 **유일한** 메커니즘은 woven `ThreadLocalContextStorage.attach` → `TraceWeaveGateway.onOtelAttach` → `TraceScopeBridge.onScopeEnter`다(worker 스레드엔 baggage·다른 경로 없음). `otelWeaveE2e`의 REQ-006 단언이 GREEN이므로 **woven attach가 worker 스레드에서 실제로 발동**했고, 이는 실제 Spring 계측과 동일한 `makeCurrent()`/`wrap()` API 경로다 — 합성 아님. **C1 단일-JVM OTel async는 진짜 검증이며 본 cross-JVM 갭의 영향을 받지 않는다.**

## 재현(아티팩트 — 비서 조건 ②)

- 환경: `~/github_tainted-spring/tainted-spring-platform`, 오버레이 `docker-compose.pjacoco-otel.yml`(diary/mindgraph에 `-javaagent:otel.jar -javaagent:pjacoco.jar=…,traceKeyAutoCreate=true`, mindgraph에 `OTEL_INSTRUMENTATION_KAFKA_ENABLED=true`). pjacoco jar = `ptc-trace-context/agent/build/libs/jacocoagent-parallel.jar`, OTel = `jacoco/opentelemetry-javaagent.jar`(2.11.0).
- 최소 서비스: zookeeper, kafka, postgres, redis, auth-user, diary(Java 23), mindgraph(Java 11).
- 부팅: `docker compose -f docker-compose.yml -f docker-compose.pjacoco-otel.yml up -d --build <서비스>`; 두 서비스 모두 `[pjacoco] agent installed`, `scopeHookInjectFail=0`(weave 설치 성공).
- 실행: diary `POST /internal/diaries`에 `traceparent: 00-1111…-…-01`; mindgraph 그래프 GET으로 Kafka 소비 완료 확인; control endpoint로 stop→flush.
- 증거 보존: `sdd/t15-otel-evidence/`(`diary-11111111.exec`=829B/13클래스, `mindgraph-11111111.exec`=56B/**0클래스**, CSV, 오버레이 yml). 전체 리포트: `sdd/task-15-otel-report.md`.

### 기대 vs 실제
- 기대: mindgraph `1111….exec`에 `com.tainted.mindgraph.event.DiaryCreatedConsumer`(및 consumer 스레드 실행 클래스) 커버리지가 traceId에 귀속.
- 실제: mindgraph store `classCount=0`, `fallbackActivations=0`, `scopeHookInjectFail=0` → weave 설치는 됐으나 consumer 스레드 `attach`에서 `TraceWeaveGateway` 미발동.

### 의심 원인(C3 조사 시작점)
1. **`AgentContextStorage` 래퍼 우회**(GA-1 OTel spike가 이미 지목): OTel javaagent의 `ContextStorageWrappersInstrumentation`이 `AgentContextStorage.wrap()`을 prepend. 실제 consumer `makeCurrent()` 경로에서 `ContextStorage.get()`이 반환하는 래핑 storage가 shaded `ThreadLocalContextStorage.attach`를 (호출 안 하거나) 다른 인스턴스로 우회할 가능성. (단, in-process 테스트에선 같은 래핑 하에서도 발동했으므로 환경/타이밍 차이 의심.)
2. **이미 로드된 shaded 클래스의 retransform 미적용**: mindgraph에서 OTel agent가 `ThreadLocalContextStorage`(2.11에서 enum 추정)를 pjacoco retransform 이전에 로드 → `RedefinitionStrategy.RETRANSFORMATION`이 silently 미적용(`scopeHookInjectFail=0`은 install() 무예외만 보장, advice 실제 적용은 미보장). PoolStrategy/locator가 mindgraph(Java 11)에서 OTel jar를 못 찾았을 가능성도 포함.
3. **Kafka consumer 컨텍스트 활성 경로 차이**: spring-kafka 계측이 `makeCurrent()` 대신 다른 storage/메커니즘을 쓸 가능성(낮음, 확인 필요).
4. 실패 테스트/시나리오명: Task 15 OTel E2E의 "mindgraph exec에 DiaryCreatedConsumer 커버리지 포함" 단언(현재 FAIL).

## C3 역전파 지시(요구사항명세 작성 시)

- C3 요구사항명세의 **첫 REQ**로 본 갭을 등재: "분산 환경(별도 JVM, OTel-instrumented Spring Kafka consumer)에서 producer 요청의 traceId로 consumer 코드 커버리지가 귀속된다." 수용 테스트 = 위 재현의 mindgraph 단언.
- **단일-JVM 잠재영향 확인 항목 포함**(비서): C3 조사 첫 단계에서 "원인 1/2가 단일-JVM @Async/executor 실제 배포(API 미포함 zero-touch)에서도 weave 미발동을 유발하는가"를 점검해 C1 단일-JVM 검증의 낙관 가정을 닫는다. (현재 in-process 게이트는 통과했으나, zero-touch 실서비스의 @Async 경로는 별도 확인 권장.)
- 설계 대안: design spec의 C3 방향("서비스가 raw traceId 기록 → 중앙 testId 병합")은 consumer 스레드에서 traceId만 확보하면 되므로, weave 외에 **OTel consumer 계측 지점(@KafkaListener 진입) 전용 inbound activator** 또는 **shaded `ContextStorage` 모든 구현체/`makeCurrent` 경로로 매칭 확장**을 후보로 평가.

## 비례성 메모

- Brave `legacy-tram` 단일-서비스 out-of-process E2E는 **Brave 단일-서비스 수용 벡터**이지 OTel weave의 out-of-process 품질 게이트가 아니다(비서). plan/문서에 그 구분을 명시해 착시를 방지한다.
- 즉흥 OTel weave 수정은 "범위확장 = STOP → REQ 추가 → 역전파" 규칙에 따라 **하지 않는다**(C3 plan에서 다룬다).
