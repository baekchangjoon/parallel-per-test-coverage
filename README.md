# parallel-per-test-coverage

> 🇰🇷 **한국어** (현재 문서) · [English](README.en.md)

<p align="center">
  <a href="https://github.com/baekchangjoon/parallel-per-test-coverage/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/baekchangjoon/parallel-per-test-coverage/actions/workflows/ci.yml/badge.svg"></a>
  <a href="https://github.com/baekchangjoon/parallel-per-test-coverage/actions/workflows/jacoco-canary.yml"><img alt="JaCoCo version canary" src="https://github.com/baekchangjoon/parallel-per-test-coverage/actions/workflows/jacoco-canary.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/License-MIT-yellow.svg"></a>
  <img alt="Java" src="https://img.shields.io/badge/Java-8%2B-orange">
  <img alt="JaCoCo" src="https://img.shields.io/badge/JaCoCo-0.8.11%E2%80%930.8.13-brightgreen">
  <a href="README.en.md"><img alt="Docs" src="https://img.shields.io/badge/Docs-KO%20%7C%20EN-green"></a>
</p>

**병렬로 실행되는 테스트가 같은 서버에 요청을 뒤섞어 보내도, 각 테스트가 유발한 코드 커버리지를
교차 오염 없이 분리**하는 Java 에이전트입니다. 산출물은 **바닐라 JaCoCo와 바이트 호환되는 `.exec`**
이며, `testId`별로 하나씩 나옵니다 — 기존 JaCoCo·Sonar·`jacococli` 생태계를 그대로 재사용할 수 있습니다.

## 왜 필요한가

JaCoCo는 **per-test 분리를 위해 설계되지 않았습니다.** 런타임 데이터는 단일 전역
`ExecutionDataStore`(classId 키)에 쌓이고, `sessionId`는 dump 시 찍히는 *라벨*일 뿐
세션별 파티셔닝 코드가 없습니다. 그래서 `sessionId`로 병렬 테스트를 나누려는 시도는 사용 오류가
아니라 **설계상 불가능**합니다. 프로브(`$jacocoData` boolean[])는 "동기화 없는 단순 쓰기 + 최소
오버헤드"라는 의도적 설계라, 컨텍스트(테스트/요청) 인지가 끼어들 자리가 없습니다.

이 프로젝트는 **JaCoCo를 고치지 않고 바깥에서** 푼다 — Datadog `dd-trace-java`가 증명한 패턴을
차용·검증해, 계측 시점에 프로브 삽입 지점을 후킹하고 **추가(additive)** 호출로 per-test 스토어에
복제 기록합니다. (자세한 분석: [`docs/research/m4-mechanism/`](docs/research/m4-mechanism/))

## 동작 원리

테스트 대상 앱 JVM에 `-javaagent`로 부착되는 **단일 자체완결 에이전트**입니다. testId는 인입 요청의
**OpenTelemetry Baggage**(`baggage: test.id=...`)로 들어오고, 명시적 **제어 엔드포인트**가 flush 경계를
정의합니다.

```
테스트 하니스                         대상 앱 JVM  (-javaagent:jacocoagent-parallel.jar)
   │                                   ┌──────────────────────────────────────────────┐
   │ setup:                            │ premain: jacoco-core 임베드 + ByteBuddy advice │
   │   POST /__coverage__/test/start ──┼─► TestStoreRegistry: T1 스토어 생성             │
   │       ?testId=T1                  │                                                │
   │                                   │                                                │
   │ 요청들 (병렬):                     │  ServletAdvice: baggage → CoverageContext=T1   │
   │   GET /api/...                    │       (요청별 ThreadLocal 스토어 선택)          │
   │   baggage: test.id=T1          ───┼─►  계측 클래스의 프로브 발화                     │
   │                                   │     → CoverageBridge.recordCoverage(...)        │
   │                                   │     → T1 스토어에 기록 (바닐라 전역 배열은 무손상) │
   │ teardown:                         │                                                │
   │   POST /__coverage__/test/stop ───┼─► flush → coverage/T1.exec  +  T1.json          │
   │       ?testId=T1                  └──────────────────────────────────────────────┘
```

- **계측**: jacoco-core의 `Instrumenter`로 직접 계측 → 바닐라와 동일한 `classId`(CRC64)·프로브 스킴.
- **라우팅**: jacoco가 심은 프로브 직후에 `CoverageBridge.recordCoverage(Class, classId, probeId)`를
  **함께 심어**, 현재 스레드에 활성화된 testId 스토어에 복제 기록. JaCoCo 전역 배열은 그대로 → 우리
  로직이 죽어도 일반 동작은 보존(additive).
- **격리**: 컨텍스트는 ThreadLocal `TestStore` 참조 → 병렬 워커 스레드 간 교차 오염 0.
- **출력**: testId당 `.exec` + 사이드카 `.json`(메타) + premain 시점 `manifest.json` 헤더(전역 메타).

> **핵심 검증**: 한 번의 계측·실행에서 per-test 프로브 배열이 jacoco 전역 배열과 **byte 단위로 동일**
> 함을 확인했습니다(`GoldenEquivalenceIT`). 2스레드 동시 실행 격리도 입증(`spike/`, e2e).

## 빠른 시작

```bash
# 1) 에이전트 jar 빌드 (JDK 17+ 필요 — Gradle 실행용; 산출물은 Java 8 호환)
JAVA_HOME=<jdk17+> ./gradlew shadowJar
#   → build/libs/jacocoagent-parallel.jar

# 2) 대상 앱에 부착
java -javaagent:jacocoagent-parallel.jar=destfile=coverage,port=6310,includes=com.example.* \
     -jar your-app.jar
```

테스트 하니스에서:

```bash
# 테스트 시작
curl -XPOST 'http://127.0.0.1:6310/__coverage__/test/start?testId=T1&shardId=s1'

# 요청마다 baggage 헤더로 testId 전파 (OTel Baggage 규약)
curl -H 'baggage: test.id=T1' 'http://app/api/...'

# 테스트 종료 → coverage/T1.exec + coverage/T1.json flush
curl -XPOST 'http://127.0.0.1:6310/__coverage__/test/stop?testId=T1&result=passed'

# 이후 표준 jacoco 도구로 리포트
java -jar jacococli.jar report coverage/T1.exec --classfiles app/classes --html out/T1
```

### 에이전트 옵션

| 옵션 | 의미 | 기본 |
|---|---|---|
| `destfile` | 출력 **디렉터리**(per-test 파일 다수) | `coverage` |
| `includes`/`excludes` | 계측 대상(WildcardMatcher, jacoco와 동일) | `*` / `` |
| `port`/`address` | 제어 엔드포인트 바인딩 | `6310` / `127.0.0.1`(loopback) |
| `lenient` | 미등록 testId 자동 등록(기본은 엄격: 미기록) | `false` |
| `commitSha` | manifest 헤더에 기록(또는 env `PJACOCO_COMMIT`) | — |

## 산출물

```
coverage/
  T1.exec        # 바닐라 JaCoCo 동일 포맷 (jacococli/Sonar/TIA 그대로 사용)
  T1.json        # 사이드카: testId·result·classCount·retryCount·shardId·status·durationMs …
  manifest.json  # 전역 헤더: schemaVersion·jacocoVersion·commitSha·precision (premain 1회)
```

## 테스트 & 검증

이 저장소는 자체 검증을 매우 엄격히 합니다. CI(`ci.yml`)가 PR·main에서 전부 실행합니다.

| 계층 | 내용 |
|---|---|
| **단위** (`test`) | 컴포넌트별 in-process 테스트 |
| **인-프로세스 통합** (`integrationTest`) | `GoldenEquivalenceIT`(vanilla byte-동일), `ProbeRoutingIT` |
| **E2E** (`e2eTest`) | 실제 `-javaagent` + 내장 Jetty + HTTP 블랙박스로 스펙 인수(격리·사이드카·manifest·엄격·untagged·재시도·동시성) |
| **뮤테이션** (`scripts/mutation-e2e.sh`) | 에이전트 SUT에 9종 mutant 주입 → e2e KILLED/SURVIVED 측정. **9/9 KILLED** (e2e가 실제 회귀를 잡음을 입증) |
| **버전 카나리** (`jacoco-canary.yml`) | jacoco **0.8.11/0.8.12/0.8.13** 매트릭스로 후킹 호환성 |
| **커버리지** | `jacocoTestReport`로 에이전트 self-coverage 측정 + CI 요약·아티팩트 |

```bash
JAVA_HOME=<jdk17+> ./gradlew test integrationTest e2eTest jacocoTestReport   # 전체
JAVA_HOME=<jdk17+> scripts/mutation-e2e.sh                                    # 뮤테이션 캠페인
```

## 프로젝트 구조

```
src/main/java/io/pjacoco/agent/   # 에이전트 (Bootstrap, ProbeInstrumentation, CoverageBridge,
                                  #          TestStore(Registry), ControlEndpoint, inbound SPI …)
spike/                            # M4 계측 메커니즘 검증 PoC
scripts/mutation-e2e.sh           # 뮤테이션 캠페인 하니스
docs/superpowers/specs/           # 설계 스펙
docs/superpowers/plans/           # 구현 계획 (TDD)
docs/research/                    # dd-trace-java 분석 · 스파이크 검증 · e2e 뮤테이션 보고서
.github/workflows/                # ci.yml · jacoco-canary.yml
```

## 설계 문서

- 스펙: [`docs/superpowers/specs/2026-06-13-parallel-jacoco-design.md`](docs/superpowers/specs/2026-06-13-parallel-jacoco-design.md)
- 구현 계획: [`docs/superpowers/plans/2026-06-13-jacocoagent-parallel-v1.md`](docs/superpowers/plans/2026-06-13-jacocoagent-parallel-v1.md)
- M4 메커니즘 조사·검증: [`docs/research/m4-mechanism/`](docs/research/m4-mechanism/)
- E2E 뮤테이션 보고서: [`docs/research/e2e-mutation-report.md`](docs/research/e2e-mutation-report.md)

## 범위

**v1 포함**: 동기 서블릿 스택, 라인 수준 바닐라 동등 `.exec`/testId, Baggage 라우팅, 제어 엔드포인트,
실패 격리·메모리 상한·관측성, jacoco 옵션 미러링.

**phase 2 (비목표)**: 비동기·스레드풀 컨텍스트 전파, 리액티브(WebFlux)·gRPC, drain 모드, 시간 기반
TTL 축출, JMX, 백엔드 업로드.

> 완성 시 [TIA(Test Impact Analysis)](docs/superpowers/specs/2026-06-13-parallel-jacoco-design.md)
> 파이프라인의 JaCoCo 직렬 수집 레이어를 드롭인 교체하는 것이 목표입니다.

## 레퍼런스

- [Datadog `dd-trace-java`](https://github.com/DataDog/dd-trace-java) (Apache 2.0) — 차용·검증한 후킹 패턴의 원본
- [JaCoCo](https://www.jacoco.org/jacoco/) — 임베드한 커버리지 엔진

## 라이선스

[MIT](LICENSE) © 2026 baekchangjoon
