# 발견 기록 → 해결 — OTel scope weave가 실제 Spring Kafka consumer 경로에서 미발동

> 상태: **해결됨(RESOLVED)** · 최초기록 2026-06-20 · 근인확정·수정 2026-06-20 · 범위: **단일 버그수정**(C3 신규기능 아님) · 출처: Task 15 OTel out-of-process E2E(tainted-spring)
> 결정 경위: out-of-process OTel E2E가 cross-service 갭 발견 → 비서 결정(record `26775a36`): 옵션 A(C1 완료, 갭은 C3 역전파) + 게이트(C1 단일-JVM 검증이 합성이 아님을 선확인) + 아티팩트 박제.
>
> **갱신(systematic-debugging, 2026-06-20):** 근인은 **단일 버그** — `OtelScopeInboundActivator.discoverOtelJar()`가 javaagent 인자 경로에 리터럴 `opentelemetry-javaagent`를 요구해, 실제 배포의 `-javaagent:/opt/otel/otel.jar`(비관용 파일명)에서 매칭 실패 → null → OTel weave install **전체 skip**(`scopeHookInjectFail` 미증가, 정상 no-op return). 이는 **C3(분산) 신규기능이 아니라 C1 OTel weave(REQ-004/006)의 잠재결함**(파일명 가정)이었다. 수정 후 cross-JVM Kafka consumer 커버리지가 정상 귀속(mindgraph classCount **0→14**, `DiaryCreatedConsumer` 포함). 아래 "근인 확정 및 해결" 참조.

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

> **정정:** line 위 "weave 설치는 됐으나 …미발동"은 최초 관찰의 오해였다. 실제로는 **weave 설치 자체가 skip**됐다(아래 근인 참조). `scopeHookInjectFail=0`은 설치 성공이 아니라 "OTel jar 미발견 → 정상 no-op return"을 의미했다.

### 의심 원인(최초) — systematic-debugging 결과 **전부 기각**
1. ~~**`AgentContextStorage` 래퍼 우회**~~ — **기각.** weave가 설치되기만 하면 spring-kafka consumer의 `makeCurrent()→shaded ThreadLocalContextStorage.attach` 경로가 woven advice를 정상 발동(수정 후 mindgraph classCount 0→14). 래퍼는 우회 요인이 아니었다.
2. ~~**retransform 미적용(enum)**~~ — **기각.** 빌드/배포 JVM 모두 **Java 11**(Corretto 11.0.20); `otelWeaveE2e`가 Java 11 + OTel 2.11.0에서 이미 통과 → retransform은 정상 동작. enum 여부도 무관.
3. ~~**Kafka consumer 경로 차이**~~ — **기각.** consumer 스레드도 동일한 `makeCurrent()` 경로를 탄다(수정 후 정상 귀속).
4. ✅ **진짜 원인은 4번 괄호의 "PoolStrategy/locator가 OTel jar를 못 찾음"이었다 — 단, locator가 아니라 그 앞단계인 `discoverOtelJar()` 자체.** 아래 참조.

### 근인 확정 및 해결
- **근인:** `OtelScopeInboundActivator.discoverOtelJar()`가 `-javaagent:` 인자 경로에 리터럴 문자열 `opentelemetry-javaagent`가 있어야 OTel agent로 인식. 실제 배포(tainted-spring)는 jar를 `-javaagent:/opt/otel/otel.jar`로 마운트하므로 매칭 실패 → `null` → `install()`이 Step 4(weave) **이전에 조용히 return**. `otelWeaveE2e`만 통과한 이유는 그 테스트가 jar를 `opentelemetry-javaagent.jar`로 둬서 매칭됐기 때문.
- **증거 정합성(단일 근인이 모두 설명):** mindgraph classCount=0(weave skip + consumer choke-point 없음) · diary classCount=13(ServletAdvice의 `OtelTestIdSource` resolver가 weave 없이도 동작 → weave 미설치를 가림) · `scopeHookInjectFail=0`(null은 정상 return) · `fallbackActivations=0`(mindgraph는 ServletAdvice를 안 거침).
- **수정:** 파일명 의존 제거 — jar 내부에 shaded `ThreadLocalContextStorage` 클래스가 있으면 OTel agent로 식별(관용 파일명은 fast-path 유지). pjacoco 자기 jar는 그 클래스가 없어 자연 제외.
- **검증:** ① 단위 `OtelScopeInboundActivatorTest`(red→green) ② out-of-process `OtelWeaveE2E.otelWeave_firesWhenOtelAgentMountedWithNonConventionalFilename`(otel.jar 파일명, red→green) ③ **실제 cross-JVM 재현**(tainted-spring, 수정 jar): mindgraph `1111….exec` classCount **0→14**, `com.tainted.mindgraph.event.DiaryCreatedConsumer`·`GraphService`·`RuleBasedGraphExtractor` 등 consumer-스레드 클래스 귀속.

## 범위 판정 — C3 신규기능 아님(역전파 불필요)

- 본 갭은 **C1 OTel weave(REQ-004/006)의 잠재결함**(검증이 `opentelemetry-javaagent.jar` 파일명으로만 이뤄져 놓침)이었고, **단일 버그 수정**으로 닫혔다. 따라서 별도 C3 REQ 신설/역전파 없이 본 수정 PR로 종결한다.
- **"OTel consumer 계측 지점(@KafkaListener) 전용 inbound activator"는 불필요**로 판정 — 기존 scope weave가 consumer 스레드를 정상 커버한다(별도 choke-point 추가는 over-engineering).
- 남는 C3(분산) 작업은 본 갭과 무관한 **cross-service testId 병합**(서비스별 raw traceId 기록 → 중앙 병합)이며, 그것이 C3 요구사항명세의 출발점이다.

## 비례성 메모

- Brave `legacy-tram` 단일-서비스 out-of-process E2E는 **Brave 단일-서비스 수용 벡터**이지 OTel weave의 out-of-process 품질 게이트가 아니다(비서). plan/문서에 그 구분을 명시해 착시를 방지한다.
- ~~즉흥 OTel weave 수정은 …하지 않는다(C3 plan에서 다룬다).~~ → **갱신:** 근인이 C3 신규기능이 아닌 **C1 weave의 단일 버그**로 판명돼, 사용자 지시(재현→근인확정→수정)에 따라 즉시 수정하고 본 PR로 종결한다(범위확장 아님). 회귀는 위 ①②③ 테스트로 영구 가드.
- **교훈:** OTel weave 검증을 `opentelemetry-javaagent.jar` 파일명으로만 한 탓에 실배포 파일명(`otel.jar`)에서의 결함을 놓쳤다. 이후 OTel 통합 검증은 비관용 파일명 마운트를 반드시 포함한다(`otelWeave_firesWhenOtelAgentMountedWithNonConventionalFilename`).
