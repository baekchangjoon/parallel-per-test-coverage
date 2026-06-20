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

## 빠른 시작 (권장)

> ⚠️ **먼저 읽으세요 — 현재는 로컬 설치가 필요합니다.** 아티팩트는 아직 Maven Central / Gradle Plugin
> Portal 에 **공개 배포되지 않았습니다**(공개 배포는 예정된 후속 과제). 따라서 아래 `io.pjacoco:…` /
> `id("io.pjacoco.gradle")` 좌표를 **그대로 복붙하면 resolve에 실패**합니다 — 버그가 아니라 미배포
> 상태입니다. 지금은 소스를 클론해 로컬에 설치한 뒤 쓰세요(한 번만):
>
> ```bash
> # 라이브러리 + agent(셰이드) + testkit + Gradle 플러그인 을 로컬 Maven 저장소에 설치
> ./gradlew :agent:publishToMavenLocal \
>   :testkit-core:publishToMavenLocal :testkit-junit5:publishToMavenLocal \
>   :testkit-junit4:publishToMavenLocal :testkit-restassured:publishToMavenLocal \
>   :gradle-plugin:publishToMavenLocal
> # Maven 플러그인 (위에서 설치한 agent 를 resolve)
> mvn -f maven-plugin/pom.xml install
> ```
>
> Gradle 소비자는 `settings.gradle.kts` 의 `pluginManagement { repositories { mavenLocal() } }` 로
> 플러그인을 mavenLocal 에서 받습니다(예: [`samples/gradle-sample`](samples/gradle-sample)).
>
> 그러면 아래 좌표가 `mavenLocal()` 에서 resolve됩니다. 자세한 절차·검증은
> [`docs/PUBLISHING.md`](docs/PUBLISHING.md), 공개 배포 로드맵은
> [배포·온보딩 요구사항명세](docs/superpowers/requirements/2026-06-20-distribution-onboarding-requirements.md)
> (REQ-D03 = 공개 배포 추적) 참고. 공개 배포가 완료되면 이 안내는 제거됩니다.

빌드 **플러그인 + 테스트킷**을 추가하는 방법입니다. 플러그인이 에이전트를 자동으로 받아 `-javaagent`로
연결하고, 테스트킷이 테스트별 경계(start/stop)와 `baggage: test.id=...` 전파를 담당합니다. 바로 복사해
돌려볼 수 있는 예제: [`samples/gradle-sample`](samples/gradle-sample) · [`samples/maven-sample`](samples/maven-sample)
([실행 방법](samples/README.md)).

**Gradle** (`build.gradle.kts`):

```kotlin
plugins { id("io.pjacoco.gradle") version "1.2.0" }

pjacoco {
    includes.set(listOf("com.example.*"))
    attachTo.set(listOf("integrationTest"))   // 이 테스트 태스크 JVM에 에이전트 + control-url 자동 주입
}
dependencies {
    testImplementation("io.pjacoco:pjacoco-testkit-junit5:1.2.0")
    testImplementation("io.pjacoco:pjacoco-testkit-restassured:1.2.0")
}
```

```java
@ExtendWith(io.pjacoco.testkit.junit5.PjacocoExtension.class)   // 테스트별 start/stop + test.id
class OwnerBlackBoxIT {
    @BeforeAll static void enable() { io.pjacoco.testkit.restassured.PjacocoRestAssured.enable(); } // baggage 자동
    // ... REST Assured 병렬 블랙박스 테스트 ...
}
```

> 별도 프로세스 서버라면 `attachTo`는 테스트 태스크에 두고(테스트킷이 control-url 을 받음), 서버 기동
> 명령에는 노출된 `pjacoco.agentJvmArg` 프로퍼티를 한 줄 추가하면 됩니다. JUnit 4는
> `io.pjacoco:pjacoco-testkit-junit4`의 `@Rule PjacocoRule`을 쓰세요.

**Maven** (`pom.xml`): `prepare-agent`가 `pjacoco.argLine`을 세팅하고 surefire가 이를 참조합니다.

```xml
<plugin>
  <groupId>io.pjacoco</groupId><artifactId>pjacoco-maven-plugin</artifactId><version>1.2.0</version>
  <executions><execution><goals><goal>prepare-agent</goal></goals></execution></executions>
  <configuration><includes><include>com.example.*</include></includes></configuration>
</plugin>
<plugin>
  <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
  <configuration><argLine>${pjacoco.argLine}</argLine></configuration>
</plugin>
```

> 아티팩트 이름: 에이전트 `io.pjacoco:pjacoco-agent`, 테스트킷 `io.pjacoco:pjacoco-testkit[-junit5|-junit4|-restassured]`,
> Gradle 플러그인 id `io.pjacoco.gradle`, Maven 플러그인 `io.pjacoco:pjacoco-maven-plugin`. 공개 배포(Maven
> Central / Gradle Plugin Portal)는 **아직 미완료**(예정된 후속 과제, REQ-D03) — 지금은 위 안내대로 로컬
> 설치 후 쓰며, 절차는 [`docs/PUBLISHING.md`](docs/PUBLISHING.md) 참고.

## 인-프로세스 per-test 커버리지 (서블릿 경계 없이)

대상 코드가 **테스트 스레드에서 그대로 실행되는** 동기 인-JVM 테스트 — 순수 단위 테스트, MockMvc,
인-프로세스 통합 테스트 — 도 testId별 `.exec` 를 받을 수 있습니다. HTTP 요청이나 서블릿 경계가 없어도
되며, 각 테스트 메서드의 시작·종료가 곧 flush 경계입니다.

**전제 조건(이 경로):** 테스트 클래스패스에 JUnit 5(Jupiter) 또는 JUnit 4; 실행에 JDK 8+; 소스에서
직접 빌드하려면 JDK 17+.

**권장: 플러그인 + 테스트킷.** `io.pjacoco.gradle` 플러그인과 `pjacoco-testkit-junit5` 의존성을 추가하면,
JUnit 5 익스텐션이 스위트 전체에 자동 적용됩니다(애너테이션 불필요). Gradle 플러그인이 JUnit 5
익스텐션 자동 등록(autodetection)을 켜 주므로 `@ExtendWith` 가 필요 없습니다 — Maven에서는
`junit-platform.properties` 로 켜며, 아래 "Maven에서 JUnit 5 자동 등록" 에서 다룹니다. JUnit 4는
에이전트가 처리하므로 `@Rule` 도 필요 없습니다.

> 아티팩트는 아직 Maven Central / Gradle Plugin Portal 에 공개 배포되지 않았습니다(공개 배포 예정).
> 지금은 소스에서 빌드 후 `publishToMavenLocal` 로 로컬에 설치해 쓰세요 —
> [`docs/PUBLISHING.md`](docs/PUBLISHING.md) 참고.

```kotlin
plugins { id("io.pjacoco.gradle") version "1.2.0" }

pjacoco {
    attachTo.set(listOf("test"))          // 에이전트를 주입할 테스트 태스크 이름
    includes.set(listOf("com.example.*")) // 인-프로세스 경로는 control-url 불필요
}
dependencies {
    testImplementation("io.pjacoco:pjacoco-testkit-junit5:1.2.0")   // JUnit 5 자동 적용
    // JUnit 4는 에이전트만으로 동작 — 의존성·@Rule 불필요
}
```

**실행 / 결과 위치.** 에이전트를 붙인 테스트 태스크를 그대로 실행합니다(예: `./gradlew test`). 테스트별
파일은 출력 디렉터리(Gradle 기본값 `build/pjacoco/`)에 테스트당 하나씩 `<FQN>#<method>.exec`(+ 짝이 되는
`.json` 사이드카)로 떨어지고, 전체 실행을 합친 `aggregate.exec` 하나가 함께 쓰입니다.

**테스트킷을 직접 등록**하려면(자동 적용을 끈 경우 등) 익스텐션/룰을 명시합니다.

```java
@ExtendWith(io.pjacoco.testkit.junit5.PjacocoInProcessExtension.class)   // JUnit 5
class CalcTest { /* SUT를 직접 호출하는 인-JVM 테스트 */ }
```

```java
class CalcTest {   // JUnit 4
    @Rule public final io.pjacoco.testkit.junit4.PjacocoInProcessRule pjacoco =
            new io.pjacoco.testkit.junit4.PjacocoInProcessRule();
}
```

> 자동 적용을 끄려면: JUnit 5 익스텐션 자동 등록은 `pjacoco { autoDetectExtensions.set(false) }`,
> JUnit 4 에이전트 자동 처리는 `pjacoco { junit4Auto.set(false) }`. (Maven에는 JUnit 5 자동 등록을
> 켜고 끄는 플러그인 플래그가 없습니다 — `junit-platform.properties` 의
> `junit.jupiter.extensions.autodetection.enabled=true` 또는 surefire `systemPropertyVariables` 로
> 제어합니다. JUnit 4 자동 처리만 `<junit4Auto>false</junit4Auto>` 로 끕니다.)

### Maven에서 JUnit 5 자동 등록

Maven 플러그인은 `pjacoco.argLine` 만 설정하고 JUnit 플랫폼 설정은 건드리지 않으므로, 자동 적용을
원하면 둘 중 하나로 직접 켭니다.

- `src/test/resources/junit-platform.properties` 에 한 줄 추가:
  `junit.jupiter.extensions.autodetection.enabled=true`
- 또는 surefire 설정에서 같은 값을 시스템 프로퍼티로 전달:
  `<systemPropertyVariables><junit.jupiter.extensions.autodetection.enabled>true</junit.jupiter.extensions.autodetection.enabled></systemPropertyVariables>`

## 전체 실행 집계 파일 (aggregate)

per-test `.exec` 들과 함께, JVM 종료 시 **전체 실행을 합친 `aggregate.exec` 하나**가 기본으로 같은
출력 디렉터리에 쓰입니다. 포맷은 바닐라 JaCoCo 와 동일하므로 `jacococli`·Sonar 가 그대로 읽습니다.

- 파일 이름 변경: Gradle `pjacoco { aggregateFile.set("all.exec") }` / Maven `<aggregateFile>all.exec</aggregateFile>`
  (에이전트 옵션 `aggregateFile=`). 기본 이름은 `aggregate.exec`.
- 끄기: Gradle `pjacoco { aggregate.set(false) }` / Maven `<aggregate>false</aggregate>` (에이전트 옵션 `aggregate=false`).
- 대상 JVM 하나당 집계 파일 하나가 나옵니다. 여러 샤드로 나눠 실행했다면 표준 도구로 합치세요:
  `java -jar jacococli.jar merge shard1/aggregate.exec shard2/aggregate.exec --destfile all.exec`.
- 집계 파일은 정상 종료 훅에서 기록됩니다. JVM을 강제 종료(`kill -9` 등)하면 건너뜁니다.

## 트레이서 trace context 소비 (비동기 per-test 커버리지)

트레이서(OpenTelemetry javaagent 또는 Brave/Spring Cloud Sleuth)가 부착된 서비스에서는, pjacoco가 트레이서의 scope 수명주기에 훅을 걸어 **현재 스레드의 valid traceId를 coverage key**로 사용합니다. 요청 스레드뿐 아니라 트레이서가 trace context를 전달하는 모든 스레드(`@Async`, executor, `@TransactionalEventListener` 등)의 커버리지가 동일 key store에 귀속됩니다 — 이것이 현행 baggage 경로에서 커버하지 못하던 **인-프로세스 async 귀속 갭의 해소**입니다.

**범위 (C1 — 단일 서비스):** 한 JVM 안에서 요청 스레드 + 동일 JVM 내 async 핸드오프까지 커버합니다. 서비스 간 분산 귀속(Kafka/HTTP를 건너는 다른 JVM의 커버리지를 동일 testId로 병합)은 미래 단계(C3)입니다.

**coverage key = raw traceId.** 현 단계에서 key는 트레이서의 traceId 문자열이며, `testId` 표시명과의 매핑은 다음 단계(C2)에서 추가됩니다. 트레이서가 없거나 현재 스레드에 valid trace context가 없으면 기존 baggage `test.id` / JUnit 자동 처리 경로로 폴백하며 동작은 현행과 동일합니다. **트레이서 런타임 하드 의존 없음** — 트레이서 라이브러리가 없는 환경에서도 에이전트는 정상 로드되고 기존 경로로 운용됩니다.

### trace-store 생명주기 (장기 실행 서비스)

상시 가동 서비스에서는 JVM을 종료할 수 없으므로, `traceKeyAutoCreate=true` 시 **idle reaper**가 자동 활성화됩니다. reaper는 백그라운드에서 `traceReaperIntervalMillis`(기본 10s) 주기로 동작하며, `traceIdleFlushMillis`(기본 30s) 동안 업데이트가 없는 traceId store를 `<traceId>.exec`로 flush한 뒤 메모리에서 evict합니다 — JVM 종료 없이 수집 가능 상태가 됩니다. flush 직후 도착하는 늦은 비동기 쓰기는 `traceLateWriteGraceMillis`(기본 10s) 동안 store가 유지되어 재flush됩니다. store 수가 `maxStores`에 근접하면 idle store가 in-flight(최근 활동) store보다 먼저 evict되며(`inFlightGuardMillis` 옵션으로 보호 강도 조정), 불가피한 in-flight eviction은 `evictedInFlightTraces` metrics 카운터로 관측할 수 있습니다.

### 활성화 방법

에이전트 옵션 `traceKeyAutoCreate=true`를 추가합니다. 이 옵션이 꺼져 있으면(기본) 트레이서 소비 경로가 설치되지 않아 기존 동작 그대로입니다.

**Brave / Spring Cloud Sleuth (단일 서비스):**

Brave는 앱 클래스패스에 이미 있으므로 별도 javaagent 없이 pjacoco만 붙이면 됩니다.

```bash
java -javaagent:jacocoagent-parallel.jar=destfile=coverage,port=6310,includes=com.example.*,traceKeyAutoCreate=true \
     -jar your-sleuth-app.jar
```

**OpenTelemetry javaagent (단일 서비스):**

OTel은 OTel javaagent를 **pjacoco보다 먼저** `-javaagent`로 나열해야 합니다. OTel javaagent가 `ContextStorage` weave를 pjacoco 로드 전에 설치해야 scope 훅이 올바르게 걸리기 때문입니다.

```bash
java -javaagent:opentelemetry-javaagent.jar \
     -javaagent:jacocoagent-parallel.jar=destfile=coverage,port=6310,includes=com.example.*,traceKeyAutoCreate=true \
     -jar your-otel-app.jar
```

> `traceKeyAutoCreate=true`가 없으면 traceId store 자동 생성과 scope weave 설치가 모두 비활성입니다. OTel 경로에서 OTel javaagent를 pjacoco 뒤에 나열하면 scope weave가 정상 작동하지 않을 수 있으므로 순서를 반드시 지키세요.
>
> OTel javaagent jar의 **파일명은 자유롭게 바꿔도 됩니다** (예: 컨테이너에서 `-javaagent:/opt/otel/otel.jar`로 마운트). pjacoco는 파일명이 아니라 jar 내부의 OTel shaded context-storage 클래스 존재로 javaagent를 식별합니다.

### traceId → testId 매핑 등록 (C2)

traceId를 사람이 읽는 `FQCN#method` testId에 대응시키려면 제어 엔드포인트를 사용합니다.

```bash
# traceId를 testId에 매핑 (테스트 시작 직후 러너/하네스에서 호출)
POST /__coverage__/trace/map?traceId=<32hex>&testId=<FQCN%23method>
```

- 매핑이 등록되면 리포트 시 해당 커버리지가 `FQCN#method` 이름의 `.exec`로 산출됩니다.
- **미등록 traceId**는 raw traceId 문자열을 그대로 testId로 사용합니다(리포트에 raw traceId 노출 — 의도된 폴백).
- 한 testId에 여러 traceId를 등록할 수 있습니다(N:1 — 재시도·다수 outbound 호출). 러너가 `TraceCoverageMerger`를 오프라인으로 실행하면 같은 testId의 모든 traceId `.exec`를 `<testId>.exec` 하나로 OR-merge합니다.
- JVM 종료 시 현재 매핑이 `<destfile>/trace-map.properties`로 덤프됩니다.
- `maxTraceMappings`(기본 100000) LRU 상한을 초과하면 오래된 항목이 자동 축출됩니다.

> **행위 변화 (C2):** `PjacocoExtension`(JUnit 5) 및 `PjacocoRule`(JUnit 4) 블랙박스 어댑터가 이제 testId를 `FQCN#method`(완전정규화 클래스명#메서드명)로 산출합니다 — 이전 SimpleClassName 형식과 달라질 수 있으므로 기존 `.exec` 파일명 패턴을 사용하는 스크립트는 확인하세요.

### 서비스 간 분산 병합 (C3)

여러 서비스에 걸친 한 테스트의 커버리지를 모으려면, 각 서비스가 **공유 볼륨**의 자기 하위 디렉터리에 per-traceId `.exec`를 쓰게 하고(에이전트 옵션 `destfile=/shared/<service>` + `traceKeyAutoCreate=true`), 상시 가동 서비스는 idle reaper가 JVM 종료 없이 flush합니다(위 "trace-store 생명주기"). 테스트 종료 후 러너가 중앙 `trace-map.properties`(`traceId=FQCN#method`)를 한 번 작성하고, 다음 CLI로 **drain-wait 수집·병합**합니다:

```bash
java -cp <pjacoco-agent.jar> io.pjacoco.agent.output.TraceMergeMain \
  --shared /shared --map trace-map.properties --report ./report --drain-wait-ms 15000
```

- `--shared <dir>`: 하위 디렉터리(`/shared/<service>/`)를 서비스로 발견해 `--drain-wait-ms`(기본 15000ms)만큼 비동기 다운스트림(Tram/CDC/Kafka) flush를 기다린 뒤 수집합니다. 명시 입력은 `--service-dir <name>=<dir>`(반복; drain-wait 없이 즉시).
- 결과: `report/<service>/<testId>.exec` — **서비스별·testId별** 병합 커버리지(서비스 디렉터리 분리로 JaCoCo classId 충돌 회피). 미등록 traceId는 raw traceId를 testId로 폴백합니다.
- 분산 실행 시 각 서비스의 reaper idle 임계를 drain-wait보다 짧게(예: `traceIdleFlushMillis=5000`) 두어 수집 시점에 `.exec`가 준비되도록 하세요.
- **실증 E2E (양 트레이서 벡터, 2026-06-20 live):**
  - **Brave** — [`agent/e2e/legacy-tram-distributed-coverage.sh`](agent/e2e/legacy-tram-distributed-coverage.sh): legacy-tram 3-서비스(order-web→reservation→Kafka/CDC→ledger). downstream ledger(Kafka/CDC async) 커버리지가 동일 testId로 귀속(classCount 6/6/2).
  - **OpenTelemetry** — [`agent/e2e/tainted-spring-distributed-coverage.sh`](agent/e2e/tainted-spring-distributed-coverage.sh): tainted-spring(diary→Kafka `diary.created`→mindgraph `DiaryCreatedConsumer`, 별도 JVM). OTel javaagent 2.11.0 + pjacoco 이중 주입, W3C `traceparent` 전파. downstream mindgraph(Kafka consumer) 커버리지가 동일 testId로 귀속(842B/1072B).
  - 두 스크립트는 `assumeTrue`(Docker + 환경 경로) 게이트의 JUnit `{LegacyTram,TaintedSpring}DistributedE2E`로도 실행되며, 환경 부재 시 skip됩니다.

## 에이전트 직접 사용 (저수준)

플러그인 없이 에이전트 jar를 직접 `-javaagent`로 다룰 수도 있습니다.

먼저 jar를 준비합니다 — [Releases](../../releases/latest)에서 받거나 직접 빌드합니다:

```bash
# 특정 버전 받기 (버전은 Releases 페이지에서 확인)
wget https://github.com/baekchangjoon/parallel-per-test-coverage/releases/download/v1.0.0/jacocoagent-parallel-1.0.0.jar
# 또는 gh CLI로 최신 릴리스에서 받기
gh release download --repo baekchangjoon/parallel-per-test-coverage --pattern 'jacocoagent-parallel-*.jar'
# 또는 직접 빌드 (JDK 17+ 필요; 산출물은 Java 8 호환)
JAVA_HOME=<jdk17+> ./gradlew :agent:shadowJar    # → agent/build/libs/jacocoagent-parallel.jar
```

대상 앱에 부착하고, 테스트 하니스에서 제어 엔드포인트를 호출합니다:

```bash
# 1) 대상 앱에 부착
java -javaagent:jacocoagent-parallel.jar=destfile=coverage,port=6310,includes=com.example.* \
     -jar your-app.jar

# 2) 테스트 시작
curl -XPOST 'http://127.0.0.1:6310/__coverage__/test/start?testId=T1&shardId=s1'
# 요청마다 baggage 헤더로 testId 전파 (OTel Baggage 규약)
curl -H 'baggage: test.id=T1' 'http://app/api/...'
# 테스트 종료 → coverage/T1.exec + coverage/T1.json flush
curl -XPOST 'http://127.0.0.1:6310/__coverage__/test/stop?testId=T1&result=passed'

# 3) 표준 jacoco 도구로 리포트
java -jar jacococli.jar report coverage/T1.exec --classfiles app/classes --html out/T1
```

### 에이전트 옵션

| 옵션 | 의미 | 기본 |
|---|---|---|
| `destfile` | 출력 **디렉터리**(per-test 파일 다수) | `coverage` |
| `includes`/`excludes` | 계측 대상(WildcardMatcher, jacoco와 동일). 기본 `*`는 **앱 클래스만** 계측하며 JDK 런타임 클래스(bootstrap·platform 로더: `java.*`/`sun.*`/`jdk.*`/`com.sun.*` 등)는 자동 제외한다 — 이들을 계측하면 premain이 네이티브 JPLIS 어서션으로 크래시하기 때문(jacoco `inclbootstrapclasses=false`와 동일). | `*` / `` |
| `inclbootstrapclasses` | JDK 런타임 클래스(bootstrap·platform 로더)도 계측 대상에 포함(opt-in). 기본 `false` — JDK 커버리지가 꼭 필요하고 위험을 감수할 때만 켠다. | `false` |
| `port`/`address` | 제어 엔드포인트 바인딩 | `6310` / `127.0.0.1`(loopback) |
| `autoRegister` | start 없이 도착한 testId도 기록(기본은 strict: 미기록) | `false` |
| `aggregate` | 종료 시 전체 실행 집계 `.exec` 작성 | `true` |
| `aggregateFile` | 집계 파일 이름 또는 절대 경로 | `aggregate.exec` |
| `junit4Auto` | JUnit 4 인-프로세스 테스트를 에이전트가 자동 처리 | `true` |
| `traceKeyAutoCreate` | 트레이서(OTel/Brave) trace context를 coverage 키로 소비; 미등록 traceId 키 store 자동 생성 + Brave/OTel scope weave 설치 (비동기 per-test 귀속) | `false` |
| `maxTraceMappings` | `POST /__coverage__/trace/map` 매핑 저장소의 LRU 상한. 장기 실행 서비스 OOM 방지. | `100000` |
| `traceReaperIntervalMillis` | idle reaper 실행 간격(ms). `traceKeyAutoCreate=true` 시 백그라운드 스레드가 이 주기로 idle store를 flush+evict한다. | `10000` |
| `traceIdleFlushMillis` | traceId store를 idle로 판정하는 무업데이트 시간(ms). 이 시간 동안 업데이트가 없으면 reaper가 flush+evict한다. | `30000` |
| `traceLateWriteGraceMillis` | flush 후 store를 유지하는 grace 기간(ms). flush 직후에도 이 시간 내 늦은 쓰기가 도착하면 재flush해 유실을 방지한다. | `10000` |
| `inFlightGuardMillis` | eviction 시 최근 `inFlightGuardMillis` 이내에 업데이트된 store를 in-flight로 간주해 보호한다. 불가피한 in-flight eviction은 `evictedInFlightTraces` 카운터로 관측된다. | `traceIdleFlushMillis` 값 (기본 30s) |
| `commitSha` | manifest 헤더에 기록(또는 env `PJACOCO_COMMIT`) | — |

## 산출물

```
coverage/
  T1.exec        # 바닐라 JaCoCo 동일 포맷 (jacococli/Sonar/TIA 그대로 사용)
  T1.json        # 사이드카: testId·result·classCount·retryCount·shardId·status·durationMs · incompleteAttribution/droppedProbes/attribution(손실 시) …
  manifest.json  # 전역 헤더: schemaVersion·jacocoVersion·commitSha·precision (premain 1회)
```

## 예시: 병렬 블랙박스 테스트의 per-test 커버리지 — spring-petclinic (Spring Boot 4 / jakarta)

[spring-petclinic](https://github.com/baekchangjoon/spring-petclinic)의 `@Tag("blackbox")` out-of-process
REST Assured 스위트(병렬 실행)에 이 에이전트를 붙여 **테스트케이스별 `.exec`** 를 얻는 전체 절차입니다.
두 레포가 한 부모 디렉터리 아래 형제로 클론돼 있다고 가정합니다.

```bash
# 두 클론을 모두 담고 있는 디렉터리에서 실행.

# 1) 커버리지 에이전트 빌드 (Gradle 실행에 JDK 17+; jar 산출물은 Java 8 호환).
( cd parallel-per-test-coverage && JAVA_HOME=<jdk17+> ./gradlew :agent:shadowJar )
#   → parallel-per-test-coverage/agent/build/libs/jacocoagent-parallel.jar

# 2) SUT(spring-petclinic)를 현재 소스로 빌드한 뒤, 에이전트를 붙여 기동.
#    includes = 앱 패키지. Spring Boot 4 의 jakarta.servlet / Tomcat 11 스택 지원.
( cd spring-petclinic && ./gradlew bootJar )
java -javaagent:"$PWD/parallel-per-test-coverage/agent/build/libs/jacocoagent-parallel.jar=destfile=/tmp/petclinic-coverage,port=6310,includes=org.springframework.samples.petclinic.*" \
     -jar "$PWD/spring-petclinic/build/libs/spring-petclinic-4.0.0-SNAPSHOT.jar"

# 3) (다른 셸에서) 병렬 블랙박스 스위트를 per-test 커버리지 라우팅과 함께 실행.
#    -Dpjacoco.control-url 이 있을 때만 활성화됨(없으면 평소와 동일).
( cd spring-petclinic && ./gradlew blackboxTest \
    -Dpetclinic.base-url=http://localhost:8080 \
    -Dpjacoco.control-url=http://127.0.0.1:6310 )
#   → /tmp/petclinic-coverage/<클래스#메서드>.exec   (테스트케이스당 vanilla-JaCoCo .exec 1개)

# 4) 임의 테스트의 .exec 를 표준 jacoco 도구로 리포트.
#    jacococli = org.jacoco:org.jacoco.cli:0.8.12:nodeps (Maven Central에서 받기).
java -jar jacococli.jar report "/tmp/petclinic-coverage/OwnerApiBlackBoxIT#getOwnerById.exec" \
    --classfiles spring-petclinic/build/classes/java/main \
    --sourcefiles spring-petclinic/src/main/java \
    --html /tmp/cov-OwnerGetById
```

> 앱 쪽 `test.id` 전파는 petclinic의 JUnit 5 익스텐션 `PerTestCoverageExtension` 이 담당합니다: 각 테스트
> 전후로 제어 엔드포인트를 호출하고, 모든 요청에 `baggage: test.id=<클래스#메서드>` 헤더를 붙입니다.
> `ConcurrencyBlackBoxIT` 가 띄우는 스레드도 `InheritableThreadLocal` 로 같은 `test.id` 를 상속합니다.

## 테스트 & 검증

CI(`ci.yml`)가 PR·main에서 아래를 실행합니다.

| 계층 | 내용 |
|---|---|
| **단위** (`test`) | 컴포넌트별 in-process 테스트 |
| **인-프로세스 통합** (`integrationTest`) | `GoldenEquivalenceIT`(vanilla byte-동일), `ProbeRoutingIT` |
| **E2E** (`e2eTest`) | 실제 `-javaagent` + 내장 Jetty + HTTP 블랙박스로 스펙 인수(격리·사이드카·manifest·엄격·untagged·재시도·동시성) |
| **뮤테이션** (`scripts/mutation-e2e.sh`) | 에이전트 SUT에 9종 mutant 주입 → e2e KILLED/SURVIVED 측정 |
| **버전 카나리** (`jacoco-canary.yml`) | jacoco **0.8.11/0.8.12/0.8.13** 매트릭스로 후킹 호환성 |
| **커버리지** | `jacocoTestReport`로 에이전트 self-coverage 측정 + CI 요약·아티팩트 |

CI는 **JDK 11/17/21 매트릭스**로 에이전트 전체 스위트(+ `-PcondyRelease`로 Condy 경로)를 돌리고, 별도 잡에서
JDK 8 testkit 호환·`samples/gradle-sample`(REST Assured 병렬 + JUnit 4)·`samples/maven-sample`을
검증합니다. testkit·플러그인 모듈은 각자 단위/기능 테스트를 가집니다.

```bash
# 에이전트 전체 스위트 (멀티모듈: 태스크에 :agent: 접두)
JAVA_HOME=<jdk17+> ./gradlew :agent:test :agent:integrationTest :agent:e2eTest \
                              :agent:e2eJakartaTest :agent:e2eCondyTest :agent:jacocoTestReport
JAVA_HOME=<jdk17+> ./gradlew test            # 전 모듈 단위/기능 테스트 (testkit·플러그인 포함)
JAVA_HOME=<jdk17+> ./gradlew -p samples/gradle-sample test   # 플러그인+testkit 엔드투엔드 샘플
JAVA_HOME=<jdk17+> scripts/mutation-e2e.sh   # 뮤테이션 캠페인
```

## 프로젝트 구조 (Gradle 멀티모듈)

```
agent/                  io.pjacoco:pjacoco-agent             # -javaagent (Bootstrap, ProbeInstrumentation,
                                                             #   CoverageBridge, ControlEndpoint, inbound SPI …)
testkit-core/           io.pjacoco:pjacoco-testkit           # 테스트킷 코어 (제어 API, 의존성 0, Java 8)
testkit-junit5/         io.pjacoco:pjacoco-testkit-junit5    # PjacocoExtension
testkit-junit4/         io.pjacoco:pjacoco-testkit-junit4    # PjacocoRule
testkit-restassured/    io.pjacoco:pjacoco-testkit-restassured  # baggage 필터
gradle-plugin/          id "io.pjacoco.gradle"               # 에이전트 resolve + -javaagent 와이어링
maven-plugin/           io.pjacoco:pjacoco-maven-plugin      # prepare-agent (Maven 빌드)
samples/                gradle-sample · maven-sample         # 바로 돌려보는 엔드투엔드 예제
spike/                  # M4 계측 메커니즘 검증 PoC (독립 빌드)
scripts/mutation-e2e.sh # 뮤테이션 캠페인 하니스
docs/                   # 설계 스펙 · 배포 가이드 · 리서치 보고서
.github/workflows/      # ci.yml(매트릭스) · release.yml(원클릭 릴리스) · jacoco-canary.yml
```

## 설계 문서

- 트레이서 trace context 소비 설계 스펙: [`docs/superpowers/specs/2026-06-19-trace-context-per-test-coverage-design.md`](docs/superpowers/specs/2026-06-19-trace-context-per-test-coverage-design.md)
- 트레이서 trace context 소비 요구사항명세: [`docs/superpowers/requirements/2026-06-19-trace-context-per-test-coverage-requirements.md`](docs/superpowers/requirements/2026-06-19-trace-context-per-test-coverage-requirements.md)
- C1 구현 계획: [`docs/superpowers/plans/2026-06-19-trace-context-per-test-coverage-c1.md`](docs/superpowers/plans/2026-06-19-trace-context-per-test-coverage-c1.md)
- 결정 기록 — deactivate/clear 의미론: [`docs/superpowers/decisions/2026-06-19-deactivate-clear-semantics.md`](docs/superpowers/decisions/2026-06-19-deactivate-clear-semantics.md)
- 발견 기록 — OTel Kafka consumer 갭 → C3: [`docs/superpowers/decisions/2026-06-20-otel-weave-kafka-consumer-gap.md`](docs/superpowers/decisions/2026-06-20-otel-weave-kafka-consumer-gap.md)
- 배포·사용성 설계(testkit + 플러그인): [`docs/superpowers/specs/2026-06-16-pjacoco-ergonomics-design.md`](docs/superpowers/specs/2026-06-16-pjacoco-ergonomics-design.md)
- 에이전트 설계 스펙(v1): [`docs/superpowers/specs/2026-06-13-parallel-jacoco-design.md`](docs/superpowers/specs/2026-06-13-parallel-jacoco-design.md)
- 배포 가이드(발행·시크릿): [`docs/PUBLISHING.md`](docs/PUBLISHING.md)
- 서드파티 라이선스: [`THIRD-PARTY-LICENSES.md`](THIRD-PARTY-LICENSES.md)
- M4 메커니즘 조사·검증: [`docs/research/m4-mechanism/`](docs/research/m4-mechanism/)
- E2E 뮤테이션 보고서: [`docs/research/e2e-mutation-report.md`](docs/research/e2e-mutation-report.md)

## 범위

**v1 포함**: 동기 서블릿 스택과 동기 인-JVM 테스트(순수 단위·MockMvc·인-프로세스 통합), 라인 수준
바닐라 동등 `.exec`/testId, Baggage 라우팅, 제어 엔드포인트, 전체 실행 집계 파일, 실패 격리·메모리
상한·관측성, jacoco 옵션 미러링.

**인-프로세스 경로의 제약**:
- 비동기·스레드풀로 넘긴 작업의 커버리지는 그 테스트로 귀속되지 않습니다 — **baggage 경로(`test.id` 헤더 / JUnit 자동 처리)를 사용할 때**. 트레이서(OTel/Brave)가 활성이고 `traceKeyAutoCreate=true`이면 단일 서비스 내 async 핸드오프는 귀속됩니다([트레이서 trace context 소비](#트레이서-trace-context-소비-비동기-per-test-커버리지) 참고).
- `@Test(timeout)` / `@Rule Timeout` 은 별도 스레드에서 실행됩니다(JUnit 4 에이전트 자동 처리에서는
  해당 테스트의 `.exec` 가 비어 나올 수 있음).
- JUnit 5 파라미터화/반복 테스트는 한 testId 를 공유하므로, 마지막 실행분만 남습니다.
- 한 테스트 태스크에서 인-프로세스와 서블릿 경로를 섞으면, 태스크를 분리하거나 `autoDetectExtensions`
  / `junit4Auto` 옵트아웃으로 한쪽을 끄세요.
- JUnit 4 에이전트 자동 처리 경로의 테스트가 테스트 스레드에서 동기 인-프로세스 서블릿 호출을 하면,
  서블릿 활성화가 종료 시 해당 테스트의 per-test 컨텍스트를 지워 그 이후 부분은 귀속되지 않습니다 —
  그런 스위트는 `junit4Auto=false` 로 두거나 서블릿(블랙박스) 경로를 별도 태스크로 분리하세요.

**phase 2 (비목표)**: 서비스 간(분산) 트레이서 컨텍스트 전파(다른 JVM — Kafka/HTTP를 건너는 downstream 서비스의 커버리지 병합, C3 예정), 리액티브(WebFlux)·gRPC, drain 모드, 시간 기반
TTL 축출, JMX, 백엔드 업로드.

> 완성 시 [TIA(Test Impact Analysis)](docs/superpowers/specs/2026-06-13-parallel-jacoco-design.md)
> 파이프라인의 JaCoCo 직렬 수집 레이어를 드롭인 교체하는 것이 목표입니다.

## 레퍼런스

- [Datadog `dd-trace-java`](https://github.com/DataDog/dd-trace-java) (Apache 2.0) — 차용·검증한 후킹 패턴의 원본
- [JaCoCo](https://www.jacoco.org/jacoco/) — 임베드한 커버리지 엔진

## 라이선스

프로젝트 자체 코드는 [MIT](LICENSE) © 2026 baekchangjoon.

> **배포 에이전트 jar 고지**: `-javaagent` jar(`io.pjacoco:pjacoco-agent`)에는 **JaCoCo core(EPL-2.0)** 와
> **Byte Buddy(Apache-2.0)** 가 `io.pjacoco.shaded.*` 로 relocate되어 임베드됩니다. 각 컴포넌트는 자신의
> 라이선스로 유지되며(MIT로 재라이선스되지 않음), jar 내부에 원 고지(`about.html`·`META-INF/NOTICE` 등)가
> 보존됩니다. 전체 내역은 [`THIRD-PARTY-LICENSES.md`](THIRD-PARTY-LICENSES.md) 참고. (Datadog `dd-trace-java`는
> **패턴만 차용**했고 코드는 사용하지 않았습니다.)
