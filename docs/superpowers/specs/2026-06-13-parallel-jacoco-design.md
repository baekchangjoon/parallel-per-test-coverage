# 병렬 Per-Test 커버리지 에이전트 (jacocoagent-parallel) — 설계 스펙

- 작성일: 2026-06-13 (개정: 메커니즘 스파이크 검증 반영)
- 상태: 설계 확정 + **M4 계측 메커니즘 스파이크 검증 완료** (`spike/` 참조)
- 선행 문서: 병렬 Per-Test 커버리지 에이전트 브레인스토밍 정리 (Google Doc)
- 관계: 완성 시 TIA(Test Impact Analysis) 파이프라인의 JaCoCo 직렬 수집 레이어를 드롭인 교체
- 레퍼런스: Datadog dd-trace-java `agent-ci-visibility` / `instrumentation/jacoco-0.8.9` (Apache 2.0) — 본 설계가 차용·검증한 패턴의 원본

---

## 1. 목표와 한 줄 정의

테스트 대상 앱 JVM에 부착되어, **바닐라 JaCoCo와 바이트 호환되는 `.exec` 산출물을
`testId`별로 분리 생성**하는 단일 자바 에이전트. 병렬로 실행되는 테스트들이 같은 앱에
요청을 뒤섞어 보내도, 각 테스트가 유발한 커버리지를 교차 오염 없이 분리한다.

**핵심 정정(브레인스토밍 대비):** 산출물 정밀도는 *파일 수준*이 아니라 **라인 수준
(= 바닐라 JaCoCo 동일)** 이다. `testId`별로 쪼개질 뿐, 각 `.exec`는 바닐라가 만든 것과
1:1로 동일하다.

---

## 2. 확정된 핵심 결정

| # | 결정 | 비고 |
|---|---|---|
| 사용 모델 | **Out-of-process** — 앱 JVM에 자바 에이전트 부착 | 앱엔 "테스트" 개념이 없음 → 인입 Baggage가 유일한 컨텍스트 소스 |
| v1 범위 | **동기 수직 슬라이스** | 비동기 전파는 phase 2 |
| 정밀도 | **라인 수준 = 바닐라 JaCoCo 동일**, testId별 분리 | |
| 출력 | testId별 `.exec` + 사이드카 `<testId>.json` + 조립형 `manifest.json` | |
| testId 소스 | 인입 요청의 **OTel Baggage** (W3C `baggage` 헤더, `baggage: test.id=...`) | OpenTelemetry Baggage 전파 규약 사용 — 표준 OTel SDK/계측이 자동 전파 |
| flush 경계 | **명시적 제어 엔드포인트** (test start/stop) | |
| 계측 메커니즘 | **jacoco-core 임베드 + ByteBuddy body-only advice**로 jacoco `ProbeInserter.insertProbe`에 additive 프로브 호출 삽입 (Datadog 패턴 차용·**스파이크 검증됨**) | self-contained — 별도 jacoco 에이전트 불요. 임베드라 내부 API 결합면 버전을 **우리가 통제** |
| 인입 가로채기 | **플러그가능 SPI**, v1 = 서블릿 필터 구현 | testId는 하니스가 매 요청 Baggage로 주입 (에이전트는 *읽기만*) |
| 미등록 testId | **엄격 모드 기본** (미기록) + 관대 모드 옵션(자동 등록) | |
| 인덱스 방식 | **사이드카 per-test** (락·공유상태 0, 크래시 안전) | |
| 재시도 | **덮어쓰기 (최신 시도 승)** | retryCount는 사이드카에 기록 |
| stop 시점 | **전제 모드** (하니스가 요청을 quiesce했다고 가정 후 즉시 flush) | drain 모드는 phase 2 |
| 산출물 jar 명 | **`jacocoagent-parallel.jar`** | JaCoCo 역할 토큰 `agent` 유지 + 변형 한정자 |

---

## 3. 아키텍처

**단일 자바 에이전트**가 `-javaagent:jacocoagent-parallel.jar`로 앱 JVM에 부착된다.
에이전트는 **jacoco-core를 내장(shade)** 하여 세 가지로 활용한다:

1. **클래스 계측** — jacoco-core의 `Instrumenter`로 직접 계측. 바닐라와 동일한 알고리즘으로
   동일한 `classId`(CRC64)·프로브 스킴 계산.
2. **per-test 라우팅** — 계측 시점에 jacoco의 `ProbeInserter.insertProbe`에 **ByteBuddy
   body-only advice**를 입혀, jacoco 자신의 `probes[id]=true` 직후 **추가(additive)** 호출
   `CoverageBridge.recordCoverage(Class, classId, probeId)`를 함께 삽입한다. jacoco 전역
   배열은 무손상 → 우리 로직이 죽어도 일반 jacoco 동작은 그대로.
3. **`.exec` 직렬화** — `ExecutionDataWriter`로 출력.

→ 계측·classId·프로브 스킴을 모두 jacoco-core가 담당하므로 **바닐라 호환이 구조적으로 보장**되고,
**스파이크에서 프로브 배열이 vanilla와 byte 단위로 동일함을 실증**했다(`spike/`).

별도의 바닐라 JaCoCo 에이전트를 띄우지 않는 단독(self-contained) 구성. 계측된 클래스의
`$jacocoInit`이 참조할 jacoco `IRuntime`(예: `LoggerRuntime`)을 에이전트가 내부적으로
1개 기동해 둔다(전역 배열용 — 우리는 그 데이터를 쓰지 않지만 계측 코드가 요구함).

> **임베드의 핵심 이점 — 버전 통제.** 우리는 jacoco-core를 *우리가 골라 shade*하므로,
> 후킹이 의존하는 내부 클래스/필드(`ProbeInserter.insertProbe`·`mv`·`arrayStrategy`,
> `ClassFieldProbeArrayStrategy.className`·`classId`, `ClassInstrumenter.visitTotalProbeCount`)의
> 버전이 **우리 통제 하**에 있다. Datadog은 사용자가 붙인 임의 버전의 *relocate된* jacoco
> 에이전트를 후킹해야 해서 obfuscate된 패키지명 매칭·MethodHandle 우회가 필요했지만, 우리는
> **깨끗한 `org.jacoco.core.internal.instr.*` 패키지를 직접 캐스팅**으로 후킹한다(스파이크 검증).
> → 브레인스토밍 Q1(버전 취약성·최상위 리스크)이 대폭 완화된다(아래 §6).

### 3.1 컴포넌트 (각 단일 책임)

| 컴포넌트 | 책임 | 의존 |
|---|---|---|
| `Bootstrap` (premain) | 옵션 파싱, jacoco `IRuntime` 기동, `ProbeInstrumentation`·인입 SPI 변환기 등록, 제어 엔드포인트 기동, 전역 메타 파일 작성, 종료 훅 | jacoco-core, Instrumentation |
| `ProbeInstrumentation` | (a) jacoco `Instrumenter`로 앱 클래스를 계측하는 `ClassFileTransformer`, (b) jacoco `ProbeInserter.insertProbe`/`visitMaxs`·`ClassInstrumenter.visitTotalProbeCount`에 **ByteBuddy body-only advice** 설치 | jacoco-core, byte-buddy, `CoverageBridge` |
| `CoverageBridge` (계측 브리지) | 계측 클래스가 프로브마다 호출하는 정적 `recordCoverage(Class clazz, long classId, int probeId)`(**핫패스**) — 활성 testId 스토어에 기록. 프로브 수는 `setTotalProbeCount(className, count)`로 계측 시점 캡처. additive | `CoverageContext`, `TestStoreRegistry` |
| `CoverageContext` (ThreadLocal) | 현 스레드의 활성 **`TestStore` 참조** 보유(요청 활성화 시 해소). 없으면 untagged(미기록) | — |
| `TestStore` | 한 testId의 누적 커버리지 `ConcurrentHashMap<classId, boolean[]>` (+ classId→className) | — |
| `TestStoreRegistry` | testId → TestStore. start에서 생성, stop에서 flush·제거. **상한 가드**(시간 기반 TTL 축출은 phase 2) | `ExecWriter` |
| `InboundActivator` (SPI) | 인입 요청에서 **OTel Baggage**(`baggage` 헤더)의 `test.id` 추출 → 레지스트리 조회 → `CoverageContext` 활성화/해제 | `CoverageContext`, `TestStoreRegistry` |
| `ServletInboundActivator` | `InboundActivator`의 v1 서블릿 구현. **단일 choke point**(예: `HttpServlet#service` 1곳)만 advise — 계층 전체에 걸어 enter/exit가 중첩되면 안쪽 exit가 컨텍스트를 조기 clear할 수 있음(S6). 재진입 가드 또는 최상위 1곳 한정 | Servlet API, byte-buddy |
| `ControlEndpoint` | `POST /__coverage__/test/start\|stop`. loopback 기본 바인딩 | `TestStoreRegistry` |
| `ExecWriter` | TestStore → `<testId>.exec` + `<testId>.json` 사이드카 | jacoco-core |
| `Observability` | 구조화 로그 + 카운터 + 종료 요약 | — |

### 3.2 계측 메커니즘 — 계측 시점 후킹 (Datadog 패턴, 검증됨)

**정정(이전 초안의 "프로브 발화 후킹"은 부정확):** 런타임 프로브 발화를 가로채는 게 아니라,
**계측 시점**에 jacoco가 프로브 바이트코드를 *심는* 지점(`ProbeInserter.insertProbe(id)`)에
advice를 걸어, jacoco의 `probes[id]=true` 직후 **추가 INVOKESTATIC**
`CoverageBridge.recordCoverage(Class, classId, probeId)`를 같이 심는다. 즉 계측 결과물에 한
줄이 더 박히고, 런타임엔 프로브가 발화할 때마다 그 추가 호출이 같이 실행된다.

검증된 구성요소(스파이크 `spike/`, jacoco-core 0.8.12):

1. `ProbeInserter.insertProbe(int id)` **OnMethodExit** advice → `mv`(위임 MethodVisitor)·
   `arrayStrategy`(리플렉션으로 `className`·`classId`)를 읽어 `recordCoverage` 호출 삽입.
2. `ProbeInserter.visitMaxs(int,int)` **OnMethodEnter** advice → `maxStack += 2`
   (jacoco가 이미 +3, 우리 호출은 `Class`+`long`+`int`=4슬롯이라 +2면 충분).
3. `ClassInstrumenter.visitTotalProbeCount(int count)` advice → `setTotalProbeCount(className, count)`
   로 클래스별 총 프로브 수 캡처(발화 시점엔 알 수 없으므로 계측 시점에 따로 잡음).
4. **Java <1.5(클래스 major<49) 스킵** — 스택에 타입 상수를 푸시할 수 없어 계측 불가.

> **인터페이스 (동결, 스파이크 검증):**
> `CoverageBridge.recordCoverage(Class<?> clazz, long classId, int probeId)` (핫패스)
> 와 `CoverageBridge.setTotalProbeCount(String className, int count)` (계측 시점).
> — 이전 초안의 `record(classId, className, probeId, probeCount)` 4-인자는 **폐기**(발화
> 시점에 probeCount를 알 수 없다는 사실과 모순이었음).

**오버헤드(정정):** 이건 본질적으로 *per-probe* 비용 — 프로브 발화마다 INVOKESTATIC + `ThreadLocal.get()`
+ 맵 조회 + 배열 쓰기가 jacoco의 1ns 프로브 위에 더해진다. 이전 초안의 "10–30%"는 *근거 없는
낙관*이었다(브레인스토밍의 더 가벼운 per-method 추정치를 잘못 차용). **실측 벤치마크 전까지
수치를 단정하지 않는다.** 테스트 환경 한정이라 절대 처리량이 병목은 아니지만, phase 2에서
벤치마크로 실수치를 확정하고, 필요 시 "메소드 진입당 컨텍스트 배열 선택"(custom probe array
strategy)으로 per-method 비용으로 낮추는 최적화를 검토한다.

---

## 4. 데이터 흐름

### 4.1 테스트 생명주기 (제어 평면)

```
하니스 setup    → POST /__coverage__/test/start?testId=T1[&commitSha=...&shardId=...]
                   (T1 스토어 레지스트리에 생성, 비어 있음)
   ... T1이 앱에 여러 API 요청 ...
하니스 teardown → POST /__coverage__/test/stop?testId=T1[&result=passed]
                   (T1 스토어 스냅샷 → T1.exec + T1.json, 레지스트리에서 제거)
```

### 4.2 요청 생명주기 (데이터 평면)

testId는 **OpenTelemetry Baggage**(W3C `baggage` 헤더)로 전파된다. 테스트 하니스가
요청 컨텍스트에 `test.id`를 Baggage로 심으면 표준 OTel 전파가 인입 요청 헤더까지 실어
나르고, `ServletInboundActivator`가 이를 읽는다.

```
1. 요청 도착, OTel Baggage 헤더: baggage: test.id=T1
2. ServletInboundActivator: baggage에서 test.id 추출 → 레지스트리 1회 조회로 TestStore 획득
     - 엄격: 미등록이면 컨텍스트 미설정 + 거부 카운트 1회(요청 단위)
     - 등록됨 → CoverageContext.set(store)   [현 스레드, store 참조 자체를 담음]
3. 핸들러 실행 → 계측된 클래스의 프로브 발화
4. CoverageBridge.recordCoverage(Class, classId, probeId): [발화마다, 핫패스]
     - store = CoverageContext.get(); null이면 즉시 반환 (untagged/미등록)
     - else store.record(classId, className, probeId, totalProbeCount)
5. 응답 반환, finally: CoverageContext.clear()                      [스레드 누수 방지]
```

### 4.3 불변식

- **스토어 등록(start)과 컨텍스트 활성화(요청)는 분리.** 엄격 모드: start 안 된 testId가
  요청에 실려 오면 미기록 + 경고 로그. 관대 모드(옵션): 본 즉시 lazy-create.
- **엄격 판정·거부 카운팅은 요청 활성화 시점 1회**(프로브마다 X). 인입 advice가 레지스트리를
  1회 조회해 `TestStore`를 얻어 `CoverageContext`에 *store 참조*를 담는다. 핫패스
  `recordCoverage`는 `CoverageContext.get()`이 null이면 즉시 반환 — 레지스트리 조회·거부
  카운팅을 **하지 않는다**. → 이전 초안이 프로브마다 `active()`를 불러 거부 카운터가 "프로브
  히트 수"가 되던 결함(S2) 해소.
- **CoverageContext는 반드시 finally에서 clear** — 톰캣 워커 스레드 재사용 오염 방지
  (스파이크에서 untagged 생성자 미기록으로 동작 확인).
- **untagged 트래픽**(헬스체크·워밍업·baggage 없는 요청)은 per-test에 미기록.

---

## 5. 출력물 포맷

```
coverage/
  T1.exec          ← testId=T1 라인 수준 커버리지, 바닐라 동일 포맷
  T1.json          ← T1 사이드카 메타데이터
  T2.exec
  T2.json
  manifest.json    ← 사이드카들로부터 조립한 전체 인덱스
```

**인덱스 = 사이드카 per-test + 시작 시점 전역 헤더.** 각 stop은 자기 `<testId>.exec`/`<testId>.json`만
쓴다 → 공유 상태 0, 락 0, 크래시 안전. 전역 메타(`commitSha` 등)는 **premain 시점에 `manifest.json`을
헤더만 1회 작성**(아래 §5.2)하여 영속화한다 — stop 경합 없음. 완전한 인덱스(헤더 + 전체 테스트 목록)가
필요하면 소비처(TIA)가 `manifest.json` 헤더 + `<testId>.json` 사이드카들을 스캔해 조립한다.

> **정정(S5):** 이전 초안은 commitSha를 manifest 헤더에 둔다면서 v1에서 manifest를 만들지 않아
> commitSha가 어디에도 안 남는 모순이 있었다. → **premain에서 헤더 파일을 즉시 쓴다**로 해소
> (`__bootstrap_commit__` 같은 가짜-테스트 우회는 폐기).

### 5.1 사이드카 `<testId>.json` 스키마

```json
{
  "testId": "T1",
  "exec": "T1.exec",
  "precision": "line",
  "startedAt": "2026-06-13T...",
  "stoppedAt": "2026-06-13T...",
  "durationMs": 1234,
  "result": "passed",       // stop 호출의 선택 파라미터 (없으면 생략)
  "classCount": 42,
  "retryCount": 0,          // 같은 testId start 재호출 시 증가
  "shardId": "shard-3",     // 테스트 분할/샤드 식별 (없으면 생략)
  "status": "complete"      // complete | partial(에이전트 종료 시 강제 flush)
}
```

### 5.2 전역 메타 (`manifest.json` 헤더 — premain 시점 1회 작성)

```json
{
  "schemaVersion": 1,
  "jacocoVersion": "0.8.12",
  "commitSha": "...",        // premain: env PJACOCO_COMMIT 또는 에이전트 옵션 commitSha
  "precision": "line"
}
```

테스트 목록은 이 파일에 누적하지 않는다(stop 경합 회피). 완전한 인덱스는 소비처가 헤더 +
`<testId>.json` 사이드카들을 스캔해 조립한다.

- `commitSha`: TIA가 "어느 코드 기준 커버리지냐"를 알아야 하므로 전역 1개. **premain에서 즉시 헤더에 기록.**
- `result`(pass/fail): 에이전트는 성패를 모름 → stop 호출이 선택적으로 실어줌.
- `status=partial`: stop 없이 에이전트가 죽으면 종료 훅이 미flush 스토어를 덤프하되 `partial` 표시.

---

## 6. JaCoCo 에이전트 옵션 호환

계측 범위 옵션은 1:1 미러링, 단일 세션 출력 옵션은 per-test 모델로 의도적 대체.

| JaCoCo 옵션 | v1 처리 | 이유 |
|---|---|---|
| `includes` / `excludes` | 그대로 패스스루 | jacoco-core 계측에 위임, 동일 동작 |
| `exclclassloader` | 그대로 | 동일 |
| `inclbootstrapclasses` | 그대로 | 동일 |
| `inclnolocationclasses` | 그대로 | 동일 |
| `classdumpdir` | 그대로 | 디버그용 동일 |
| `destfile` | 재해석 → 출력 **디렉터리** | 단일 파일 아닌 `<testId>.exec` 다수 |
| `sessionid` | 무시/대체 | testId가 세션 식별자 역할 |
| `append` | N/A | per-test 파일이라 개념 없음 |
| `dumponexit` | 대체 | flush는 test/stop이 함. (종료 시 미flush 스토어 안전망 덤프는 유지) |
| `output`=tcpserver/tcpclient | 대체 | 제어 엔드포인트가 그 역할 |
| `address` / `port` | 재정의 | 제어 엔드포인트 바인딩용 |
| `jmx` | 보류 (phase 2+) | 필요 시 제어 채널 대안 |

> Maven 좌표는 **`org.jacoco` groupId를 쓰지 않는다** (JaCoCo 프로젝트 소유). 자체 groupId 사용.
> 우리가 의존·shade하는 jacoco-core 버전은 **빌드에 고정**(예: 0.8.12). 후킹이 의존하는 내부
> API가 이 고정 버전에 묶이므로, 버전 취약성은 *우리가 jacoco-core를 올릴 때만* 발생한다(§3 임베드 이점).

---

## 7. 에러 처리 · 동시성 · 실패 격리

### 7.1 동시성

- **같은 testId 동시 요청** (브레인스토밍 Q3): `TestStore`는 `ConcurrentHashMap<classId, boolean[]>`.
  프로브 쓰기는 `bit=true`라 benign race(바닐라와 동일 성질). class별 `boolean[]` 최초 생성만
  원자적이면 안전 → v1에서 해소, 별도 락 불필요.
- **stop 중 in-flight 요청**: flush는 **스냅샷 복사 후 직렬화**, 스토어는 레지스트리에서 제거 →
  이후 늦은 쓰기는 버려짐(미기록).

### 7.2 stop 시점 = 전제 모드

stop이 오면 "그 테스트의 요청은 이미 다 끝났다"고 가정하고 즉시 스냅샷·flush.
동기 통합테스트는 `요청 → 응답 await → 다음`이라 이 가정이 성립.
fire-and-forget/비동기 후처리를 기다리는 **drain 모드는 phase 2**.

### 7.3 재시도 의미론

같은 testId로 start가 다시 오면 = 재시도: **스토어 리셋(이전 시도 폐기), 출력 덮어쓰기(최신 승),
사이드카 `retryCount` 증가.**

### 7.4 실패 격리 (불변식)

- **우리 코드는 절대 대상 앱을 죽이지 않는다.** `CoverageBridge.recordCoverage` 경로의 모든 예외는
  catch·로그 후 삼킨다 — 커버리지 손실이 앱 크래시보다 낫다. 추가형 설계라 우리가 꺼져도
  앱 로직은 무관.
- **메모리 가드**: stop 안 오는 스토어 누수 방지 — **레지스트리 상한 가드**(v1). 상한 초과 시
  가장 오래된 미flush 스토어를 `partial`로 덤프. **시간 기반 TTL 축출은 phase 2**(상한이 v1의
  크래시 안전 필수, TTL은 보강).

### 7.5 제어 엔드포인트 보안

제어 HTTP 리스너는 **기본 loopback 바인딩**(테스트 제어 외부 노출 금지).
`address`/`port`로 재정의 가능.

### 7.6 관측성 (로깅) — "조용한 실패" 금지

다음을 구조화 로그 + 카운터로 남긴다:

- `CoverageBridge`에서 삼킨 예외 (스택, classId) — **rate-limited / 집계 카운트**로 폭주 방지.
- 레지스트리 상한 초과 축출 (어느 testId가 `partial`로 덤프됐는지). (시간 기반 TTL은 phase 2)
- 엄격 모드에서 미등록 testId 요청 거부 (어느 testId).
- 재시도 덮어쓰기 (testId, 새 retryCount).
- stop 없이 종료 시 강제 덤프된 스토어 수.
- **에이전트 종료 시 요약 1줄**: 처리한 테스트 수 / partial 수 / 삼킨 예외 수 / 거부 수.

---

## 8. 테스트 전략

> **스파이크 선검증(`spike/`):** 아래 키스톤·병렬 격리 두 가지는 이미 스파이크에서 통과했다.
> 정식 테스트는 이 스파이크 패턴을 에이전트 코드로 옮긴 것이다.

- **키스톤 — 바닐라 동등성 골든 테스트**: 단일 testId로 클래스 실행 → 우리 브리지가 모은 프로브
  배열이 **같은 실행에서 jacoco 전역 배열과 byte 단위로 동일**함을 단언(스파이크 통과:
  `assertArrayEquals(vanillaProbes, ourProbes)`). 더해 `Analyzer`로 커버 라인 집합 일치도 확인.
- **병렬 격리 테스트**(존재 이유): 2스레드가 같은 계측 클래스에서 서로 다른 분기를 동시(×1000회)
  실행 → 각자 다른 커버리지 집합(교차 오염 0)을 단언(스파이크 통과: `negLines=[7,8]` vs
  `posLines=[7,10,13]`). 정식 IT는 부착된 에이전트 + Jetty로 `.exec` 파일을 검증.
- **스레드 재사용 오염 테스트**: 톰캣 워커 재사용 흉내 → `CoverageContext.clear` 검증.
- **엄격/관대 모드 · 재시도 덮어쓰기** 각각 테스트.
- **실패 격리 테스트**: 프로브 경로 예외 주입 → 앱 정상 + 로그/카운터 기록 확인.
- **JaCoCo 버전 카나리**(브레인스토밍 Q1): **임베드 jacoco-core 버전을 올릴 때만** 도는
  매트릭스 CI에서 골든 동등성을 재실행 → 내부 API 변화로 후킹이 깨지면 조기 감지. (버전을
  우리가 통제하므로 이 리스크는 상시가 아니라 *업그레이드 PR 한정*이다.)

---

## 9. v1 범위 경계

### 포함 (v1)

- 단일 자체완결 에이전트(`jacocoagent-parallel.jar`), jacoco-core 내장 + ByteBuddy advice 후킹
- 동기 서블릿 스택 (인입 SPI + `ServletInboundActivator`)
- 라인 수준 바닐라 동등 `.exec` (testId별) — **스파이크 검증된 메커니즘**
- 인입 Baggage `test.id` (하니스가 주입, 에이전트는 읽기)
- 제어 엔드포인트 start/stop (엄격 기본 + 관대 옵션), 전제 모드 flush
- 사이드카 `<testId>.json` (retryCount·shardId 포함) + premain 시점 `manifest.json` 헤더(전역 메타)
- JaCoCo 계측 옵션 패스스루 / 출력·세션 옵션 대체
- 실패 격리 + 메모리 가드 + 관측성

### 제외 (후속 phase — 비목표로 명기)

- 비동기·스레드풀·`@Async` 컨텍스트 전파 → **phase 2** (OTel Context vs ThreadLocal+executor 결정 포함, Q4)
- 리액티브(WebFlux/Netty)·gRPC SPI 구현
- drain 모드 (in-flight 대기)
- 재시도 시도별 보존
- 라인→메소드 정밀도 하향 옵션 (Q5)
- JMX 제어 채널
- 백엔드 업로드 (파일 기반 유지 — Datadog SaaS 결합 회피가 설계 의도)

---

## 10. 브레인스토밍 미해결 질문 처리 현황

| 질문 | 상태 |
|---|---|
| Q1 JaCoCo 버전 호환 카나리 | **v1 해소 + 대폭 완화** — jacoco-core를 임베드(버전 우리 통제)하므로 상시 리스크가 아니라 *업그레이드 PR 한정*. 카나리는 업그레이드 시 골든 동등성 재실행(§8) |
| Q2 파일→라인 점진 확장 | 무관화 — v1이 라인 수준. 라인→메소드 하향은 phase 2 옵션(Q5) |
| Q3 동시 같은 testId 경쟁 | **v1 해소 + 스파이크 검증** (§7.1 ConcurrentHashMap + benign race; 2스레드×1000회 격리 통과) |
| Q4 컨텍스트 전파 OTel vs ThreadLocal | **phase 2 이월** |
| Q5 메소드 수준 하향 오분류율 | **phase 2 이월** |
| Q6 산출물 포맷 .exec vs 자체 | **v1 해소 + 스파이크 검증** — .exec + 사이드카 인덱스, vanilla byte-동일 |
