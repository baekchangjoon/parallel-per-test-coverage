# 병렬 Per-Test 커버리지 에이전트 (jacocoagent-parallel) — 설계 스펙

- 작성일: 2026-06-13
- 상태: 설계 확정 → 구현 플랜 작성 예정
- 선행 문서: 병렬 Per-Test 커버리지 에이전트 브레인스토밍 정리 (Google Doc)
- 관계: 완성 시 TIA(Test Impact Analysis) 파이프라인의 JaCoCo 직렬 수집 레이어를 드롭인 교체

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
| 계측 메커니즘 | **JaCoCo 후킹** (D3, Datadog per-probe 브리지 패턴, Apache 2.0 레퍼런스) | |
| 인입 가로채기 | **플러그가능 SPI**, v1 = 서블릿 필터 구현 | |
| 미등록 testId | **엄격 모드 기본** (미기록) + 관대 모드 옵션(자동 등록) | |
| 인덱스 방식 | **사이드카 per-test** (락·공유상태 0, 크래시 안전) | |
| 재시도 | **덮어쓰기 (최신 시도 승)** | retryCount는 사이드카에 기록 |
| stop 시점 | **전제 모드** (하니스가 요청을 quiesce했다고 가정 후 즉시 flush) | drain 모드는 phase 2 |
| 산출물 jar 명 | **`jacocoagent-parallel.jar`** | JaCoCo 역할 토큰 `agent` 유지 + 변형 한정자 |

---

## 3. 아키텍처

**단일 자바 에이전트**가 `-javaagent:jacocoagent-parallel.jar`로 앱 JVM에 부착된다.
에이전트는 **jacoco-core를 내장(shade)** 하여 두 가지로 활용한다:

1. **클래스 계측** — 바닐라와 동일한 알고리즘으로 동일한 `classId`·프로브 ID 계산.
2. **`.exec` 직렬화** — `ExecutionDataWriter`로 출력.

→ 같은 코드로 같은 ID를 계산하므로 **바닐라 호환이 구조적으로 보장**된다.

별도의 바닐라 JaCoCo 에이전트를 띄우지 않는 단독(self-contained) 구성.

### 3.1 컴포넌트 (각 단일 책임)

| 컴포넌트 | 책임 | 의존 |
|---|---|---|
| `Bootstrap` (premain) | 클래스 변환기 등록, 제어 엔드포인트 기동, 옵션 파싱 | jacoco-core, Instrumentation |
| `ProbeRouter` (계측 브리지) | 프로브 발화를 가로채 `(classId, probeId)`를 *현재 활성 testId 스토어*에 기록. 바닐라 전역 배열은 건드리지 않음(추가형) | `CoverageContext`, `TestStoreRegistry` |
| `CoverageContext` (ThreadLocal) | 현 스레드의 활성 testId 보유. 없으면 untagged(미기록) | — |
| `TestStore` | 한 testId의 누적 커버리지 `ConcurrentHashMap<classId, boolean[]>` | — |
| `TestStoreRegistry` | testId → TestStore. start에서 생성, stop에서 flush·제거. 상한·TTL 가드 | `ExecWriter` |
| `InboundActivator` (SPI) | 인입 요청에서 **OTel Baggage**(`baggage` 헤더)의 `test.id` 추출 → `CoverageContext` 활성화/해제 | `CoverageContext` |
| `ServletFilterActivator` | `InboundActivator`의 v1 서블릿 구현 | Servlet API |
| `ControlEndpoint` | `POST /__coverage__/test/start\|stop`. loopback 기본 바인딩 | `TestStoreRegistry` |
| `ExecWriter` | TestStore → `<testId>.exec` + `<testId>.json` 사이드카 | jacoco-core |
| `Observability` | 구조화 로그 + 카운터 + 종료 요약 | — |

### 3.2 계측 메커니즘 — per-probe 브리지

프로브 발화를 가로채는 **per-probe 브리지(Datadog 방식)** 를 채택. 채택 근거:
작동하는 Apache 2.0 레퍼런스 존재(D3), JaCoCo 계측기를 깊게 수술하지 않음,
테스트 환경 10–30% 감속은 수용 범위.

> 더 가벼운 "메소드 진입당 컨텍스트 배열 선택"(custom probe array strategy) 방식은
> 이론상 빠르나 미검증·수술 깊음 → 오버헤드가 실측에서 문제될 때 phase 2+ 최적화로 보류.

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
나르고, `ServletFilterActivator`가 이를 읽는다.

```
1. 요청 도착, OTel Baggage 헤더: baggage: test.id=T1
2. ServletFilterActivator: baggage 헤더 파싱 → test.id 추출 → CoverageContext.set(T1)   [현 스레드]
3. 핸들러 실행 → 계측된 클래스의 프로브 발화
4. ProbeRouter: 발화마다 CoverageContext.get() 확인
     - T1 활성  → T1 스토어의 (classId→boolean[])에 기록
     - 없음     → 미기록 (untagged)
5. 응답 반환, finally: CoverageContext.clear()                      [스레드 누수 방지]
```

### 4.3 불변식

- **스토어 등록(start)과 컨텍스트 활성화(요청)는 분리.** 엄격 모드: start 안 된 testId가
  요청에 실려 오면 미기록 + 경고 로그. 관대 모드(옵션): 본 즉시 lazy-create.
- **CoverageContext는 반드시 finally에서 clear** — 톰캣 워커 스레드 재사용 오염 방지.
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

**인덱스 = 사이드카 per-test.** 각 stop은 자기 `<testId>.exec`/`<testId>.json`만 쓴다
→ 공유 상태 0, 락 0, 크래시 안전. `manifest.json`은 필요 시(또는 `finalize` 호출 시)
사이드카를 모아 생성.

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

### 5.2 전역 메타 (manifest.json 헤더, 에이전트 1회 실행 단위)

```json
{
  "schemaVersion": 1,
  "jacocoVersion": "0.8.12",
  "commitSha": "...",        // start 파라미터 또는 env PJACOCO_COMMIT
  "precision": "line",
  "tests": [ /* 사이드카들 */ ]
}
```

- `commitSha`: TIA가 "어느 코드 기준 커버리지냐"를 알아야 하므로 전역 1개.
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

- **우리 코드는 절대 대상 앱을 죽이지 않는다.** `ProbeRouter` 기록 경로의 모든 예외는
  catch·로그 후 삼킨다 — 커버리지 손실이 앱 크래시보다 낫다. 추가형 설계라 우리가 꺼져도
  앱 로직은 무관.
- **메모리 가드**: stop 안 오는 스토어 누수 방지 — 레지스트리 상한 + TTL 축출. 상한 초과 시
  가장 오래된 미flush 스토어를 `partial`로 덤프.

### 7.5 제어 엔드포인트 보안

제어 HTTP 리스너는 **기본 loopback 바인딩**(테스트 제어 외부 노출 금지).
`address`/`port`로 재정의 가능.

### 7.6 관측성 (로깅) — "조용한 실패" 금지

다음을 구조화 로그 + 카운터로 남긴다:

- `ProbeRouter`에서 삼킨 예외 (스택, classId) — **rate-limited / 집계 카운트**로 폭주 방지.
- 레지스트리 상한 초과·TTL 축출 (어느 testId가 `partial`로 덤프됐는지).
- 엄격 모드에서 미등록 testId 요청 거부 (어느 testId).
- 재시도 덮어쓰기 (testId, 새 retryCount).
- stop 없이 종료 시 강제 덤프된 스토어 수.
- **에이전트 종료 시 요약 1줄**: 처리한 테스트 수 / partial 수 / 삼킨 예외 수 / 거부 수.

---

## 8. 테스트 전략

- **키스톤 — 바닐라 동등성 골든 테스트**: 단일 testId 순차 실행 → 우리 에이전트로 `T1.exec`.
  동일 시나리오를 바닐라 `jacocoagent.jar`로 → `jacoco.exec`. **커버된 프로브 집합 동일 단언.**
  "바닐라 동일" 주장을 증명하는 핵심.
- **병렬 격리 테스트**(존재 이유): 2+ 테스트 동시 실행, 요청 뒤섞기 → 각 `<testId>.exec`가
  자기 커버리지만 담는지(교차 오염 0) 단언.
- **스레드 재사용 오염 테스트**: 톰캣 워커 재사용 흉내 → `CoverageContext.clear` 검증.
- **엄격/관대 모드 · 재시도 덮어쓰기** 각각 테스트.
- **실패 격리 테스트**: 프로브 경로 예외 주입 → 앱 정상 + 로그/카운터 기록 확인.
- **JaCoCo 버전 카나리**(브레인스토밍 Q1·최상위 리스크): 여러 JaCoCo 버전 매트릭스에서
  골든 동등성 테스트 재실행 CI → 새 버전이 후킹을 깨면 조기 감지.

---

## 9. v1 범위 경계

### 포함 (v1)

- 단일 자체완결 에이전트(`jacocoagent-parallel.jar`), jacoco-core 내장
- 동기 서블릿 스택 (인입 SPI + `ServletFilterActivator`)
- 라인 수준 바닐라 동등 `.exec` (testId별)
- 인입 Baggage `test.id`
- 제어 엔드포인트 start/stop (엄격 기본 + 관대 옵션), 전제 모드 flush
- 사이드카 `<testId>.json` + 조립형 `manifest.json` (retryCount·shardId 포함)
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
| Q1 JaCoCo 버전 호환 카나리 | **v1 해소** (§8 버전 카나리) |
| Q2 파일→라인 점진 확장 | 무관화 — v1이 라인 수준. 라인→메소드 하향은 phase 2 옵션(Q5) |
| Q3 동시 같은 testId 경쟁 | **v1 해소** (§7.1 ConcurrentHashMap + benign race) |
| Q4 컨텍스트 전파 OTel vs ThreadLocal | **phase 2 이월** |
| Q5 메소드 수준 하향 오분류율 | **phase 2 이월** |
| Q6 산출물 포맷 .exec vs 자체 | **v1 해소** — .exec + 사이드카 인덱스 |
