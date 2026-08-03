# Micrometer Tracing / B3 지원 — 설계 spec (v2.1.0 단독 테마)

- 작성일: 2026-08-03
- 상태: **spike 게이트 통과** (2026-08-03, §3) — 설계 확정
- 출처 결정: 사용자 확정 3건 — ①spike-게이트 순서(검증 후 갭 구현) ②잘 알려진 baggage 헤더
  3종 기본 내장+문서화(신규 옵션 없음) ③v2.1.0 단독 테마
- 관련: C1~C3 trace-context 설계(`2026-06-19-trace-context-per-test-coverage-design.md`),
  요구사항명세 REQ-001..024

## 1. 배경과 문제

pjacoco의 testId 라우팅은 2층이다:

| 층 | 메커니즘 | 현재 지원 |
|---|---|---|
| baggage 경로(기본) | `ServletAdvice`가 HTTP 헤더에서 `test.id`를 직접 파싱 (라이브러리 의존 0) | **W3C `baggage` 헤더만** |
| 트레이서 경로(`traceKeyAutoCreate`) | scope weave로 traceId를 coverage key로 사용 | OTel(`ContextStorage`), Brave(`CurrentTraceContext`) |

Spring Boot 3 세대는 Sleuth가 아니라 **Micrometer Tracing**(파사드)을 쓰고, 실 트레이서는
`micrometer-tracing-bridge-brave`(→Brave, **B3 전파**) 또는 `bridge-otel`(→OTel)이다. pjacoco의
weave는 파사드가 아니라 그 아래층(brave.*, io.opentelemetry.*)을 후킹하므로 **이론상 이미
동작**하지만, 검증 벡터가 Boot 2/Sleuth(legacy-tram)뿐이라 Boot 3 + micrometer 조합은 미확인이다.

또한 Brave 계열의 baggage는 W3C `baggage` 헤더가 아니라 **필드별 개별 헤더**로 전파된다
(Brave `BaggagePropagation`: 헤더명 = 필드명 `test.id`; legacy Sleuth 호환: `baggage-test.id`).
현재 `ServletAdvice`는 이 헤더들을 읽지 않으므로, Micrometer/Brave/B3 스택의 앱 자체 전파에
`test.id`를 실어 보내는 구성이 pjacoco의 baggage 경로와 연결되지 않는다.

**개념 구분(중요):** B3가 운반하는 것은 traceId(트레이서 경로가 처리)다. `test.id`는 B3가 아니라
baggage 필드 헤더 규약이 운반한다. "B3 지원"은 이 두 층의 합이다.

## 2. 목표 / 비목표

**목표**
1. Boot 3 + `micrometer-tracing-bridge-brave` + B3 스택에서 트레이서 경로(동기·async·분산)가
   동작함을 검증하고, 안 되는 지점이 있으면 고친다. (spike 게이트)
2. baggage 경로가 Brave 계열 필드 헤더를 기본 인식: 인바운드 헤더 **3종 기본 내장** —
   `baggage`(W3C, 기존) + `test.id`(Brave/Micrometer 필드 헤더) + `baggage-test.id`(legacy
   Sleuth 접두 규약). 신규 에이전트 옵션 없음(사용자 결정 ②).
3. testkit 아웃바운드: W3C 형식 외에 **필드 헤더 형식** 선택지 제공 — SUT의 Micrometer/Brave
   전파(`management.tracing.baggage.remote-fields=test.id`)가 서비스 간에 test.id를 운반하게.
4. E2E 벡터 추가: Boot 3 + bridge-brave + B3 (동기 + async + baggage 필드 헤더).
5. spike가 발견한 결함 수정: `includes=*` 기본값의 JDK 동적 프록시 계측 → Boot 3 부팅 실패 (§7).

**비목표**
- W3C `tracestate`/`traceparent` 파싱(트레이서 경로가 처리), gRPC/WebFlux 인바운드(기존 비목표
  유지), baggage 필드명 커스터마이징(`test.id` 고정 — 기존과 동일), Micrometer Observation API
  자체 계측.

## 3. Spike 게이트 결과 (2026-08-03 실측 — Boot 3.3.5 + bridge-brave + B3)

> 환경: `traceKeyAutoCreate=true,autoRegister=true,includes=spike.*`. 전문:
> scratchpad `mm-spike/spike-results.md` (Drive/Evernote 업로드본).

| # | 질문 | 결과 | 근거 |
|---|---|---|---|
| S1 | Brave scope weave 발화(트레이스-키 store 생성) | **PASS** | plain 요청 → `<traceId>.exec` 생성, `scopeHookInjectFail=0`, weave 경고 없음 |
| S2 | @Async 핸드오프가 같은 traceId store로 귀속 | **PASS** | `ContextPropagatingTaskDecorator` 구성에서 **요청 트레이스 내 실행 probe 100% 귀속**. execinfo `9 of 11`은 probe 커버리지 비율이며, 미커버 2개는 라인 매핑 실측으로 규명됨(리뷰 지적 후 재검증): ①생성자(15-17행) — Spring 기동 시 트레이스 밖 실행이라 per-trace store 부재가 **올바름**, ②ternary false 분기(30행) — 미실행 코드. 귀속 실패 0. **성립 조건**: 컨텍스트 전파 decorator 필수 — 미구성 실행기는 성립 불가(문서화 대상) |
| S3 | 인바운드 `b3` single 헤더의 traceId 존중 | **PASS** | `b3: 80f198ee...` → 그 traceId가 store 키 |
| S4 | baggage 필드 헤더 wire 실측 | **PASS** | 인바운드 `test.id: SPIKE-T1`이 Brave baggage로 인식(`remote-fields=test.id`); 아웃바운드 wire = **`b3` single 1개 + 필드명 그대로 `test.id` 헤더** — §4.2 표 확정 |
| S5 | 트레이서 활성 상태의 W3C `baggage` 폴백 | **설계 사실 확인** | 트레이서 컨텍스트 존재 시 폴백 도달 불가(REQ-007 우선순위의 귀결, `fallbackActivations=0`). 회귀가 아닌 **의도된 우선순위** — 트레이서-활성 앱의 per-test 귀속은 C2 trace/map 담당. §4.1의 3종 확장은 트레이서 비활성(폴백) 경로에 적용 |

**결론: 트레이서 경로는 Micrometer/B3 스택에서 수정 없이 동작. 이 사이클의 구현은
baggage 필드 헤더 3종, testkit HeaderStyle, E2E 벡터, §7의 신규 결함 수정이다.**
S2의 "9/11"은 리뷰(3-벤더, critical) 후 라인 매핑 재검증으로 **귀속 실패가 아님이 확정**됐다
(생성자=트레이스 밖 실행, ternary 미실행 분기) — MM-E2E-1은 결정론적 단언으로 명세(§4.5).

## 4. 설계

### 4.1 인바운드 baggage 헤더 3종 (agent)

`ServletAdvice`의 폴백 단계에서 헤더를 순서대로 조회, **첫 번째 non-null 승리**:

1. `baggage` — **W3C 형식만 이 헤더에 적용**: `BaggageParser.testId()`로 `test.id=` 항목 파싱
   (기존 동작, 최우선)
2. `test.id` — **값 전체(trim 후)가 testId** (Brave `BaggagePropagation` 필드 헤더 — W3C 파서를
   거치지 않음)
3. `baggage-test.id` — 값 전체(trim 후)가 testId (legacy Sleuth 접두 규약)

- 파싱 규칙 분리(리뷰 반영): W3C 파싱은 1번 헤더에만. 2·3번은 trim 후 **비어 있지 않을 때만**
  매치로 취급 — 빈 값(`test.id:`)은 다음 헤더/미존재 경로로 폴스루(기존 `missingTestIdInbound`
  회계 보존).
- 우선순위 근거: 기존 소비자 무회귀(W3C 우선), 그다음 현행 규약 → legacy 순. 우선순위 계약은
  단위 테스트(3-way 충돌 포함) + MM-E2E-2의 충돌 케이스 1건으로 검증.
- 구현 위치: `ServletAdvice` 폴백 블록 + `BaggageParser`에 정적 헤더 목록. 옵션 없음.
- 트레이서 경로가 활성일 때의 상호작용: 기존 규칙 유지 — 트레이서 컨텍스트가 있으면 그것이
  우선, 없을 때 baggage 폴백(REQ-007). 3종 확장은 폴백 단계 안의 변화라 우선순위 구조 불변.
- **store 생성 규칙(리뷰 반영, 설계 결정)**: `traceKeyAutoCreate=true`여도 **baggage-폴백 유래
  키는 auto-create 분기를 타지 않고** 기존 레지스트리 계약(strict=시작된 testId만 /
  autoRegister=생성)을 따른다. auto-create는 트레이서-유래 키(traceId) 전용 — 일반적 헤더명
  `test.id` 오탐으로 store가 무한 생성되는 면을 차단한다. (기존 동작 대비 미세 변화: 트레이서
  모드에서 폴백 키의 auto-create가 사라짐 — S5상 폴백 자체가 희귀 경로라 영향 최소, CHANGELOG 명시)
- 관측성: 카운터 3종 신설 — `testIdFromW3cBaggage` / `testIdFromFieldHeader` /
  `testIdFromLegacyFieldHeader`. **불변식: 기존 `fallbackActivations`는 3종 모두에서 계속 증가**
  (신규 카운터는 그 파티션) — summary 라인 소비자 무회귀, 단위 테스트로 고정.

### 4.2 헤더 규약 표 (S4 wire 실측으로 확정)

| 규약 | 인바운드 헤더 | 값 형식 | 출처 스택 |
|---|---|---|---|
| W3C Baggage | `baggage` | `test.id=<id>[,...]` | OTel javaagent, testkit 기본 |
| Brave/Micrometer 필드 | `test.id` | `<id>` | Boot 3 + bridge-brave `remote-fields` |
| legacy Sleuth | `baggage-test.id` | `<id>` | Boot 2/Sleuth 잔존 스택 |

### 4.3 testkit 아웃바운드 형식 선택지

- `Pjacoco`(core)에 필드-헤더 값 접근자 추가: 기존 `baggageHeaderValue()`(W3C) 유지 +
  `fieldHeaderName()`/`fieldHeaderValue()`(= `test.id` / `<id>`).
- `HeaderStyle` enum은 **testkit-core**에 둔다(향후 다른 어댑터 공용, 서드파티 의존 0·Java 8).
- `PjacocoRestAssured.enable(HeaderStyle)` — `W3C_BAGGAGE` / `FIELD` / `BOTH`. **기존 no-arg
  `enable()`은 그대로 유지**되어 `enable(HeaderStyle.W3C_BAGGAGE)`로 위임(소스·바이너리 호환,
  2.1.0 minor 정합). per-request 부착 API `baggageFilter()`에도 대칭 오버로드
  `baggageFilter(HeaderStyle)`를 추가한다(기존 공개 API의 기능 협소화 방지 — 리뷰 반영).
- 사용 시나리오: SUT가 Micrometer/Brave 스택이고 `remote-fields=test.id`로 앱 자체 전파를 쓸 때
  `FIELD`(또는 `BOTH`)를 켜면, 하니스→SUT-A→SUT-B 체인에서 앱 전파가 test.id를 운반.

### 4.4 MicrometerTestIdSource — 리뷰로 제거됨

3-벤더 리뷰(Gemini critical)에서 구현 불가 판정: Micrometer Tracing은 OTel(`Span.current()`)·
Brave(`Tracing.current()`)와 달리 **전역 정적 접근자가 없는 DI 기반 파사드**라, 에이전트가 정적
컨텍스트에서 Tracer 인스턴스를 얻을 수 없다. 실 트레이서는 bridge-brave/bridge-otel 둘뿐이고
양쪽 모두 네이티브 층 소스(Brave/OTel)가 이미 커버하므로 파사드-층 보험은 불필요하다. 이 결정으로
`TestIdSource` 배선 지점 2곳(ServletAdvice.traceSources + Bootstrap의 TraceScopeBridge resolver)
문제도 함께 소멸한다.

### 4.5 E2E 벡터 (외부 수용 테스트 명세)

**JDK 게이트(리뷰 critical 반영)**: Boot 3.3은 Java 17 최소인데 agent 모듈은 Java 8 호환 +
CI 매트릭스에 JDK 11 레그가 있다. 따라서 MM E2E는 agent 소스셋 승격이 **아니라** spike와 같은
**독립 Gradle 빌드**(`e2e-mm-boot3/`, 자체 settings + `toolchain 17` 고정)로 두고, **전용 CI 잡**
(JDK 17 단일)에서 실행한다 — 기존 gradle 매트릭스 잡은 무변경. (참고: 인용했던 `otelWeaveE2e`
태스크는 CI에 배선돼 있지 않음이 리뷰에서 확인됨 — 재사용할 선례가 아니라 신규 배선이다.)

- **MM-E2E-1 (동기+async)**: Boot 3.3 + bridge-brave + B3, `traceKeyAutoCreate=true` —
  /sync·/async 요청 후 traceId-키 store 생성 + **async 작업 메서드에서 요청 중 실제 실행되는
  라인들의 probe가 100% 같은 traceId store에 존재**(결정론적 단언: SUT의 async 메서드를 분기
  없는 직선 코드로 두거나, 실행 라인 집합을 명시해 그 라인들의 커버를 단언. 생성자·미실행
  분기는 단언 대상 아님 — spike 라인 매핑으로 확정된 규칙).
- **MM-E2E-2 (baggage 필드 헤더)**: 같은 앱, 트레이서 경로 꺼짐 — `test.id: T1` /
  `baggage-test.id: T2` 각각 요청 → `T1.exec`/`T2.exec` + W3C `baggage` 무회귀 + **3-way 충돌
  요청 1건(`baggage`+`test.id`+`baggage-test.id` 동시, 서로 다른 값 → W3C 승리)** +
  `fallbackActivations` 파티션 불변식 단언.
- **MM-E2E-3 (분산)**: 하니스 `FIELD` 스타일 → SUT-A → (앱 전파: `b3` + `test.id`
  헤더, S4 실측 확정) → SUT-B 2-hop에서 두 서비스 exec가 같은 testId로 수집·병합.
  **자식 JVM 2개로 구현(Docker 불요 — plan 리뷰 역전파)**, 항상 실행.
- **MM-E2E-4 (§7 프록시 결함)**: (a) 단위 — `jdk.proxy*`/`com.sun.proxy.*` 클래스가 계측
  제외됨을 확인, (b) e2e — Boot 3 앱이 `includes=*` 기본값으로 **부팅 성공**(발견 방법 인코딩:
  spike에서는 부팅 실패했음).
- 완료 정의: 신규 REQ 매트릭스(요구사항명세에서 부여) 100% green + 기존 전 스위트 무회귀.

### 4.6 트레이서-활성 앱에서의 test.id (S5 귀결 문서화)

트레이서 경로가 켜진 앱에서는 어떤 baggage 헤더도 store 키가 되지 않는다(키 = traceId).
test.id를 테스트로 귀속하려면 기존 C2 trace/map 매핑을 쓴다 — 이 사이클 변경 없음. 선택
확장("Brave baggage test.id 존재 시 자동 매핑 등록")은 매핑의 명시성과 C2 계약 유지를 위해
비목표로 남긴다(리뷰 이견 시 재론).

## 5. 호환성·리스크

- 인바운드 헤더 3종은 **읽기 전용 확장** — 기존 소비자 행위 불변(W3C 우선). 충돌 시나리오:
  한 요청에 `baggage`와 `test.id`가 서로 다른 값이면 W3C 승리 — 문서에 명시.
- `test.id`라는 일반적 헤더명 오탐 리스크(리뷰로 재조정): 위험 트리거는 lenient/autoRegister만이
  아니라 **`traceKeyAutoCreate=true`**(이 기능의 대상 모드)가 핵심 — 기존 코드는 이 플래그가
  켜지면 폴백 키도 auto-create했다. §4.1의 설계 결정(baggage-유래 키는 auto-create 제외)이 이
  리스크의 구조적 완화이며, 잔여 리스크(autoRegister 모드에서 임의 헤더 값으로 store 생성)는
  문서 고지.
- §7 프록시 기본 제외의 호환성: JPMS 이전 JVM(Java 8/11)에서 `com.sun.proxy.*`를 오류 없이
  계측하던 앱은 커버리지 분모가 미세하게 줄어든다 — 동적 프록시는 생성 코드라 커버리지 대상으로
  무의미하다는 근거와 함께 CHANGELOG·릴리스 노트에 명시(§6).
- testkit 기본값 불변(W3C) — `FIELD`는 옵트인이라 무회귀.
- Micrometer 파사드 API 변화 리스크: 리플렉션 + never-throw로 기존 트레이서 소스와 동일한
  완충. jacoco-canary처럼 버전 매트릭스까지는 두지 않음(후순위 보험 소스이므로 — 비례성).

## 6. 산출물

- agent: `ServletAdvice`/`BaggageParser` 헤더 3종 + store 생성 규칙(§4.1), Metrics 카운터 3종,
  프록시 pre-check 제외(§7)
- testkit-core: `HeaderStyle` + 필드 헤더 접근자 / testkit-restassured: `enable(HeaderStyle)` 오버로드
- E2E: MM-E2E-1~4 (독립 빌드 `e2e-mm-boot3/` + 전용 JDK 17 CI 잡 신규 배선)
- 문서: README ko/en 헤더 규약 표(§4.2)·HeaderStyle·async decorator 전제(S2), 릴리스 노트에
  §5의 두 행위 변화(폴백 키 auto-create 제외, 프록시 기본 제외) 명시
- 버전: v2.1.0 (기능 추가, 무파괴)

## 7. Spike 부수 발견 — 이 사이클에 포함할 결함 수정

`includes=*` 기본값일 때 JDK 동적 프록시(`jdk.proxy3.*`)까지 계측되어 `$jacocoInit`
`IllegalAccessError`로 **Boot 3 앱 부팅 자체가 실패**한다(JPMS 모듈 경계). Micrometer 특정이
아닌 일반 결함이지만, **이 사이클(2.1.0)에 포함하는 근거를 명시한다(단독 테마 결정의 예외)**:
①Boot 3 + 기본 `includes=*` 조합이 이 spike에서 처음 발견됐고 알려진 영향 사용자가 아직 없다,
②lockstep 정책상 2.0.1 패치도 전체 릴리스 사이클 비용이 동일한데 2.1.0이 곧 나온다,
③이 결함이 고쳐지지 않으면 이 테마의 대상 사용자(Boot 3)가 애초에 부팅을 못 한다 — 즉 테마의
전제 조건이다.

수정: 계측 필터에서 `jdk.proxy*`/`com.sun.proxy.*`를 **무조건 pre-check로 제외** — 기존
자기-제외(`io.pjacoco.`/`org.jacoco.`/`net.bytebuddy.`)와 같은 층위이며, **사용자 `excludes=`
설정과 병합되지 않아** 사용자가 excludes를 지정해도 재활성화되지 않는다. 검증은 MM-E2E-4(§4.5).
기존 instrumentFailures 신호로는 잡히지 않는 부류(계측은 성공, 런타임에 터짐)라는 점을 테스트
주석에 명시.
