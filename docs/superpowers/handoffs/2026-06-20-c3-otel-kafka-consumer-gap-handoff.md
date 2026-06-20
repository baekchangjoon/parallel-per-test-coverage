# 핸드오프 — C3 OTel weave Kafka-consumer 갭 해소 (2026-06-20)

> 작업: pjacoco가 OTel scope weave를 실제 분산(Kafka consumer) 경로에서 발동시키지 못하던 갭의 근인확정·수정.
> 결과: **PR #13 → main 머지 완료**(`ca3f32c`). CI 전체 green.

## 1. 한 줄 요약
OTel weave가 별도-JVM Kafka consumer 스레드에서 미발동(mindgraph `classCount=0`)하던 원인은 **`discoverOtelJar()`의 파일명 의존 버그 하나**였고, jar를 구조적으로 식별하도록 고쳐 cross-JVM consumer 커버리지 귀속을 실측으로 확인(classCount **0→14**)했다.

## 2. 근인 (단일 버그)
- `OtelScopeInboundActivator.discoverOtelJar()`가 `-javaagent:` 경로에 리터럴 `opentelemetry-javaagent`를 요구.
- 실제 배포(tainted-spring)는 `-javaagent:/opt/otel/otel.jar`로 마운트 → 매칭 실패 → `null` → `install()`이 **OTel scope weave 설치 전체를 조용히 skip**(`scopeHookInjectFail=0`은 정상 no-op return).
- **C1 OTel weave(REQ-004/006)의 잠재결함**(검증이 관용 파일명 `opentelemetry-javaagent.jar`로만 이뤄져 놓침). **C3 신규 기능이 아님.**

### 증거 정합성 (단일 근인이 전부 설명)
| 관측 | 설명 |
|---|---|
| mindgraph `classCount=0` | consumer 스레드엔 servlet choke-point가 없어 weave가 유일 의존인데 그게 skip |
| diary `classCount=13` | ServletAdvice의 `OtelTestIdSource` resolver가 weave 없이도 동작 → weave 미설치를 가림 |
| `scopeHookInjectFail=0` | `discoverOtelJar`=null은 정상 return (install 실패 아님) |
| 기존 `otelWeaveE2e`만 통과 | 그 테스트 jar 파일명이 `opentelemetry-javaagent.jar`라 매칭됨 |

### 기각된 가설 (decision 문서가 우려했던 것)
- AgentContextStorage 래퍼 우회 ❌ / enum retransform 미적용 ❌ / consumer 경로 차이 ❌ — 빌드·배포 모두 Java 11이고 `otelWeaveE2e`가 Java 11+OTel 2.11.0에서 통과하므로 retransform·버전 무관.
- **Kafka 전용 inbound activator 불필요** — 기존 weave가 consumer 스레드를 정상 커버.

## 3. 수정
- 파일명 의존 제거 → **jar 내부에 shaded `ThreadLocalContextStorage` 클래스가 있으면 OTel agent로 식별**(관용 파일명 fast-path 유지). pjacoco 자기 jar는 그 클래스가 없어 자연 제외.
- 변경 파일: `OtelScopeInboundActivator.java`(수정), `OtelScopeInboundActivatorTest.java`(신규), `OtelWeaveE2E.java`(회귀+flush 수정), docs 3종.

## 4. 검증 (전부 green)
- **단위** `OtelScopeInboundActivatorTest` — jar 식별 red→green (관용/비관용 파일명, 무관 agent 무시, none).
- **out-of-process E2E** `OtelWeaveE2E#otelWeave_firesWhenOtelAgentMountedWithNonConventionalFilename` — `otel.jar` 마운트에서도 weave 발동 (수정 임시 revert로 red 확인 후 green).
- **실측 cross-JVM** (tainted-spring, real OTel 2.11.0, Java 11) — mindgraph consumer 커버리지가 producer traceId에 귀속(classCount 0→14, `DiaryCreatedConsumer` 포함).
- 전체 regression + CI(gradle 11/17/21, samples, jdk8-compat, maven) green.

### 부차 수정 (선재 flakiness)
`OtelWeaveE2E`의 forked SUT는 비-daemon control endpoint로 자연종료 못 함 → 테스트 SIGKILL 전 per-trace store flush 누락(baseline green은 우연히 포트 점유로 endpoint가 못 떴기 때문). → SUT 죽이기 전 control endpoint(`POST /__coverage__/test/stop`)로 flush 보장, stdout sink는 thread-safe `StringBuffer`. (2벤더 리뷰 findings: race/중복println/stale javadoc 전부 반영.)

## 5. 문서
- `docs/superpowers/decisions/2026-06-20-otel-weave-kafka-consumer-gap.md` → **RESOLVED**.
- requirements 매트릭스 REQ-004/006 비고 갱신, README OTel javaagent 파일명 무관 안내.

## 6. 남은 작업 (다음 세션)
1. **C2 plan**(매핑: REQ-011~014) — `requirements-spec` → `writing-plans`.
2. **C3 요구사항명세/plan**(분산: REQ-015~018,023 + REQ-019 잔여). **주의: 본 Kafka 갭은 이미 닫힘** — C3의 진짜 출발점은 **cross-service testId 병합**(서비스별 raw traceId 기록 → 중앙 병합)이다.
3. 재현 환경: tainted-spring 오버레이 jar 경로가 깨져 있음(제거된 worktree) — 그쪽 `HANDOFF-from-pjacoco-c3-otel-kafka-2026-06-20.md` 참조.

## 7. 재현 환경 포인터
- tainted-spring `~/github_tainted-spring/tainted-spring-platform` — 오버레이 `docker-compose.pjacoco-otel.yml`, OTel 2.11.0 = `jacoco/opentelemetry-javaagent.jar`. Docker down 상태.
- otelWeaveE2e용 OTel agent jar는 `agent/build/otel-agent/opentelemetry-javaagent.jar`에 배치해야 실행(없으면 `assumeTrue` skip).
