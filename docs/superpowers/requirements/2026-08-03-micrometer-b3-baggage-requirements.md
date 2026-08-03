# Micrometer Tracing / B3 지원 요구사항명세 (v2.1.0)
> 출처(design spec): docs/superpowers/specs/2026-08-03-micrometer-b3-baggage-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항이 모두 ≥1개의 통과 수용 테스트를 가짐(대상 매트릭스
> 전부 green) + 기존 전 스위트(전 모듈 test·integrationTest·e2e 3종·샘플 E2E) 무회귀.

## 요구사항 목록

### REQ-MM-001 — 인바운드 `test.id` 필드 헤더 인식
- 유형: Functional / 우선순위: Must
- 설명: 트레이서 비활성(폴백) 경로에서 Brave/Micrometer 필드 헤더를 testId로 인식한다.
- 수용기준:
  - Given 트레이서 경로가 꺼진 SUT와 시작된 테스트 `T1`, When `test.id: T1` 헤더로 요청,
    Then 해당 요청의 커버리지가 `T1.exec`로 산출된다.
- 검증 레벨: E2E black-box (MM-E2E-2)

### REQ-MM-002 — 인바운드 `baggage-test.id` legacy 헤더 인식
- 유형: Functional / 우선순위: Must
- 수용기준:
  - Given 같은 조건과 시작된 테스트 `T2`, When `baggage-test.id: T2` 헤더로 요청,
    Then 커버리지가 `T2.exec`로 산출된다.
- 검증 레벨: E2E black-box (MM-E2E-2)

### REQ-MM-003 — W3C `baggage` 헤더 무회귀 + 파서 분리
- 유형: Functional / 우선순위: Must
- 설명: W3C 파싱(`test.id=` 항목 추출)은 `baggage` 헤더에만 적용되고 기존 동작이 유지된다.
  필드 헤더 2종은 W3C 파서를 거치지 않고 값 전체(trim)를 testId로 쓴다.
- 수용기준:
  - Given 시작된 테스트 `W1`, When `baggage: test.id=W1` 헤더로 요청, Then `W1.exec` 산출
    (기존과 동일).
  - Given `test.id: a=b` 헤더, When 요청, Then testId는 문자열 `a=b` 전체다(W3C 파싱 미적용).
- 검증 레벨: E2E black-box (MM-E2E-2) + integration

### REQ-MM-004 — 헤더 우선순위 계약
- 유형: Functional / 우선순위: Must
- 설명: 한 요청에 복수 헤더가 오면 `baggage`(W3C) > `test.id` > `baggage-test.id` 순으로
  첫 번째 유효 값이 승리한다.
- 수용기준:
  - Given 서로 다른 값의 3개 헤더가 동시에 온 요청, When 활성화, Then W3C `baggage`의
    `test.id=` 값이 채택된다.
  - Given `baggage`와 `test.id` 2개만 온 요청(서로 다른 값), When 활성화, Then `baggage`의
    값이 채택된다.
  - Given `test.id`와 `baggage-test.id`만 온 요청, When 활성화, Then `test.id` 값이 채택된다.
  - Given `test.id=` 멤버가 **없는** `baggage` 헤더 + `test.id` 필드 헤더, When 활성화, Then
    필드 헤더 값이 채택된다(우선순위는 헤더 존재가 아니라 **유효 값 추출 성공** 기준 —
    "baggage 헤더가 있기만 하면 하위 무시" 구현을 배제).
- 검증 레벨: integration(단위 3-way + 전체 pairwise) + E2E 충돌 케이스 1건 (MM-E2E-2)

### REQ-MM-005 — 빈 값 폴스루
- 유형: Functional / 우선순위: Must
- 설명: trim 후 빈 헤더 값은 매치가 아니다 — 다음 헤더로 폴스루하고, 전부 없으면 기존
  `missingTestIdInbound` 회계 경로를 보존한다.
- 수용기준:
  - Given `test.id:`(빈 값)와 `baggage-test.id: T3` 헤더, When 활성화, Then `T3`가 채택된다.
  - Given 3개 헤더 모두 빈 값 또는 부재, When 수집 창 내 요청, Then testId 미존재 경로
    (`missingTestIdInbound` 증가)로 처리된다.
- 검증 레벨: integration

### REQ-MM-006 — baggage-폴백 키의 store 생성 규칙
- 유형: Functional / 우선순위: Must
- 설명: `traceKeyAutoCreate=true`여도 baggage-폴백 유래 키는 auto-create 분기를 타지 않고
  기존 레지스트리 계약(strict=시작된 testId만, autoRegister=생성)을 따른다. auto-create는
  트레이서-유래 키(traceId) 전용이다.
- 구현 노트(리뷰 확정): `ServletAdvice.activate()`의 **baggage-폴백 분기만**
  `TestStoreRegistry.active(key)`(기존 strict/lenient 경로)를 호출하도록 바꾸고, 트레이서-유래
  분기와 `TraceScopeBridge`는 기존 `forCoverageKey(key)`를 유지한다 — `forCoverageKey` 시그니처
  변경(origin 파라미터) 금지.
- 수용기준:
  - Given `traceKeyAutoCreate=true` + strict 모드에서 시작되지 않은 `GHOST`, When
    `test.id: GHOST` 헤더 요청, Then store가 생성되지 않고 기존 strict 거부 회계를 따른다.
  - Given 같은 플래그, When **(a) 동기 인바운드에서 ServletAdvice의 tracer-소스 분기**가
    traceId를 해석, Then store가 auto-create된다(기존 동작 유지).
  - Given 같은 플래그, When **(b) TraceScopeBridge의 scope 진입**(비동기 weave 경로), Then
    store가 auto-create된다(기존 동작 유지).
- 검증 레벨: integration

### REQ-MM-007 — 관측성 카운터 3종 + 파티션 불변식
- 유형: Functional / 우선순위: Should
- 설명: `testIdFromW3cBaggage`/`testIdFromFieldHeader`/`testIdFromLegacyFieldHeader` 카운터를
  신설하고, 기존 `fallbackActivations`는 3종 모두에서 계속 증가한다(신규 카운터는 그 파티션).
- 수용기준:
  - Given 각 헤더 유형별 활성화 1회씩, When summary 집계, Then 각 카운터=1이고
    `fallbackActivations`=3이다.
  - Given 신규 카운터 3종, When JVM 종료 `summary()` 라인 검사, Then 3종 모두 라인에
    노출된다(기존 Metrics 필드 전수 노출 관례 유지).
- 검증 레벨: integration

### REQ-MM-008 — testkit `HeaderStyle` FIELD 스타일 emit
- 유형: Functional / 우선순위: Must
- 설명: `HeaderStyle` enum(testkit-core: `W3C_BAGGAGE`/`FIELD`/`BOTH`)과
  `PjacocoRestAssured.enable(HeaderStyle)` **및 대칭 오버로드 `baggageFilter(HeaderStyle)`**
  (기존 per-request 부착 API의 스타일 지원)를 제공한다. FIELD면 `test.id: <id>` 개별 헤더,
  BOTH면 W3C+FIELD 둘 다 emit한다.
- 수용기준:
  - Given `enable(HeaderStyle.FIELD)`와 활성 testId `T1`, When 아웃바운드 요청, Then 요청에
    `test.id: T1` 헤더가 실리고 `baggage` 헤더는 없다.
  - Given `enable(HeaderStyle.BOTH)`, When 요청, Then 두 헤더가 모두 실린다.
- 검증 레벨: integration(testkit 단위) + E2E (MM-E2E-3에서 실사용)

### REQ-MM-009 — 기존 `enable()` 무회귀 호환
- 유형: Functional / 우선순위: Must
- 설명: no-arg `enable()`은 시그니처 그대로 유지되고 `enable(HeaderStyle.W3C_BAGGAGE)`로
  위임한다(소스·바이너리 호환, 2.1.0 minor 정합).
- 수용기준:
  - Given 기존 호출 코드 `PjacocoRestAssured.enable()`, When 컴파일·실행, Then 변경 없이
    동작하며 W3C `baggage` 헤더만 emit한다(기존과 동일).
- 검증 레벨: integration

### REQ-MM-010 — JDK 프록시 무조건 계측 제외
- 유형: Functional / 우선순위: Must
- 설명: `jdk.proxy*`/`com.sun.proxy.*` 클래스는 자기-제외와 같은 층위의 무조건 pre-check로
  계측에서 제외되며, 사용자 `excludes=` 설정과 병합되지 않는다(사용자 설정으로 재활성화 불가).
- 수용기준(관측 지점 = transform 층: `JacocoTransformer.transform()`이 해당 클래스명에 대해
  **null 반환(바이트코드 무변조)**임을 단언 — "예외 없음"만으로는 불충분, 무음 손실 결함
  전례의 재발 방지):
  - Given `includes=*` 기본값, When JDK 동적 프록시 클래스명으로 transform 호출, Then null
    반환(계측 안 함).
  - Given 사용자가 **`includes=jdk.proxy*`를 명시적으로 지정**(재활성화 시도 — excludes는
    집합을 넓힐 수 없어 검증력이 없음), When 프록시 클래스명 transform, Then 여전히 null.
- 검증 레벨: integration(단위) — E2E 측면은 REQ-MM-011

### REQ-MM-011 — Boot 3 기본값 부팅 성공 (발견-방법 인코딩)
- 유형: Functional / 우선순위: Must
- 설명: spike에서 부팅 실패를 재현한 조건(Boot 3 + `includes=*` 기본값)에서 앱이 정상
  부팅한다.
- 수용기준:
  - Given Boot 3.3 앱 + agent `includes=*`(기본), When 기동, Then `IllegalAccessError` 없이
    부팅이 완료되고 HTTP 응답이 정상이다.
- 검증 레벨: E2E black-box (MM-E2E-4)

### REQ-MM-012 — Boot 3 + bridge-brave 트레이서 경로 검증 (동기+async)
- 유형: Functional / 우선순위: Must
- 설명: Boot 3.3 + `micrometer-tracing-bridge-brave` + B3에서 `traceKeyAutoCreate=true` 트레이서
  경로가 동작한다: 인바운드 `b3` 헤더의 traceId가 store 키가 되고, 컨텍스트 전파 decorator가
  구성된 async 실행분이 같은 store에 귀속된다. 이 E2E는 전용 JDK 17 CI 잡에서 실행된다.
- 수용기준:
  - Given 해당 스택의 SUT, When `b3: <traceId>-<spanId>-1` 헤더로 /sync 요청, Then
    `<traceId>` 키 store가 생성되고 해당 커버리지를 담는다.
  - Given 동일 SUT와 분기 없는 직선 코드의 async 작업 메서드, When /async 요청(작업 완료
    대기 후 응답), Then 그 메서드의 실행 라인 probe가 100% 같은 traceId store에 존재한다.
    (생성자·미실행 분기는 단언 대상이 아님 — spike 라인 매핑 재검증으로 "9/11"이 귀속 실패가
    아니라 probe 커버리지 비율임이 확정됨.)
  - Given CI, When PR 실행, Then 이 E2E가 JDK 17 전용 잡에서 실행된다.
- 검증 레벨: E2E black-box (MM-E2E-1, 독립 빌드 `e2e-mm-boot3/`)

### REQ-MM-013 — 분산 2-hop FIELD 스타일 수집
- 유형: Functional / 우선순위: Should
- 설명: 하니스가 `FIELD` 스타일로 SUT-A에 요청하고, SUT-A의 앱 전파(`b3` + `test.id` 필드
  헤더)가 SUT-B로 이어질 때, 두 서비스의 exec가 같은 testId로 수집·병합된다.
- 수용기준:
  - Given 2-hop SUT 체인(양쪽 agent 부착, `remote-fields=test.id`), When FIELD 스타일
    테스트 1건 실행, Then 두 서비스 각각의 `<testId>.exec`가 산출된다.
  - Given 두 exec의 `jacococli merge`(기존 분산 수집 절차와 동일 도구), When 병합 리포트
    생성, Then **SUT-A에만 있는 클래스와 SUT-B에만 있는 클래스가 모두** 병합 결과에
    존재한다(last-writer-wins 오병합 배제).
  - Given 테스트 종료(성공·실패·중단 모든 경로), When 잔존 점검, Then 이 테스트가 띄운
    자식 JVM 프로세스가 0이다(PID 한정 finally teardown — 전역 누수 검증 게이트 준수).
- 검증 레벨: E2E black-box (MM-E2E-3 — **자식 JVM 2개(A·B 인스턴스)로 구현, Docker 불요**;
  plan 리뷰에서 역전파 정정: spike가 컨테이너 없이 동일 검증을 수행했음)

### REQ-MM-014 — 문서화 (헤더 규약 표 + HeaderStyle + async 전제)
- 유형: Functional / 우선순위: Should
- 설명: README ko/en에 §4.2 헤더 규약 표, testkit `HeaderStyle` 사용법, async 귀속의 전제
  조건(컨텍스트 전파 decorator 필요 — spike S2)을 문서화하고, 릴리스 노트에 두 행위 변화
  (폴백 키 auto-create 제외, 프록시 기본 제외)를 명시한다.
- 수용기준:
  - Given 갱신된 README ko/en, When 검토, Then 헤더 3종 표·HeaderStyle 예시·decorator 전제가
    양 언어에 존재하고 좌표·버전이 2.1.0 기준으로 정확하다.
- 검증 레벨: 문서 검토 (PR 문서동기화 게이트)

### REQ-MM-015 — 표면 불변 제약 (신규 옵션 0, 핫패스 무변경, Java 8)
- 유형: Non-functional / 우선순위: Must
- 설명: 이번 사이클은 신규 에이전트 옵션을 추가하지 않고(사용자 결정 ②), 핫패스
  (`CoverageBridge.recordCoverage`)에 코드 변화가 없으며, agent·testkit의 Java 8 호환을
  유지한다.
- 수용기준(자동 가드로 검증 — 사람 리뷰 단독 의존 금지):
  - (a) Given `AgentOptions.KNOWN_KEYS` 스냅숏 단위 테스트(23키 집합 고정), When 실행, Then
    집합이 기존과 동일하다.
  - (b) Given `CoverageBridge` 소스의 체크섬/시그니처 가드 테스트, When 실행, Then
    `recordCoverage` 경로 무변경이다.
  - (c) Given 빌드, When JDK 8 호환 검사(기존 jdk8-compat CI 잡), Then green이다.
- 검증 레벨: integration(자동 가드 a·b + CI c) — 코드 리뷰는 보조

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-MM-001 | `test.id` 필드 헤더 인식 | MmBaggageHeadersE2E#fieldHeaderProducesPerTestExec | E2E | 🔴 planned |
| REQ-MM-002 | `baggage-test.id` legacy 헤더 | MmBaggageHeadersE2E#legacyHeaderProducesPerTestExec | E2E | 🔴 planned |
| REQ-MM-003 | W3C 무회귀 + 파서 분리 | MmBaggageHeadersE2E#w3cBaggageUnchanged + BaggageHeadersTest#w3cParserOnlyForBaggageHeader | E2E+IT | 🔴 planned |
| REQ-MM-004 | 헤더 우선순위 계약 | BaggageHeadersTest#priority3Way/#pairwiseAll/#malformedBaggageFallsThrough + MmBaggageHeadersE2E#conflictW3cWins | IT+E2E | 🔴 planned |
| REQ-MM-005 | 빈 값 폴스루 | BaggageHeadersTest#emptyValueFallsThrough/#allEmptyGoesMissingPath | IT | 🔴 planned |
| REQ-MM-006 | 폴백 키 store 생성 규칙 | StoreCreationRuleIT#baggageKeySkipsAutoCreate/#servletTracerKeyAutoCreates/#scopeBridgeKeyAutoCreates | IT | 🔴 planned |
| REQ-MM-007 | 카운터 3종 + 파티션 불변식 | BaggageHeadersTest#countersPartitionFallbackActivations/#countersAppearInSummary | IT | 🔴 planned |
| REQ-MM-008 | HeaderStyle FIELD emit | HeaderStyleTest#fieldEmitsFieldHeaderOnly/#bothEmitsBoth/#baggageFilterStyleOverload + MmDistributedFieldE2E#twoHopSameTestId(교차) | IT+E2E | 🔴 planned |
| REQ-MM-009 | `enable()` 무회귀 | HeaderStyleTest#noArgEnableKeepsW3c | IT | 🔴 planned |
| REQ-MM-010 | 프록시 무조건 제외 | ProxyExclusionTest#transformReturnsNullForJdkProxy/#explicitIncludesCannotReenable | IT | 🔴 planned |
| REQ-MM-011 | Boot 3 기본값 부팅 성공 | MmBoot3BootE2E#bootsWithDefaultIncludes | E2E | 🔴 planned |
| REQ-MM-012 | 트레이서 경로 동기+async | MmTracerPathE2E#b3TraceIdKeyedStore/#asyncAttributedToSameStore | E2E | 🔴 planned |
| REQ-MM-013 | 분산 2-hop FIELD | MmDistributedFieldE2E#twoHopSameTestId | E2E | 🔴 planned |
| REQ-MM-014 | 문서화 | (PR 문서동기화 게이트 — README ko/en + 릴리스 노트 검토) | doc | 🔴 planned |
| REQ-MM-015 | 표면 불변 제약 | KnownKeysSnapshotTest + HotPathGuardTest + jdk8-compat CI | IT+CI | 🔴 planned |
| (공통) | MM E2E CI 배선(REQ-MM-011·012·013) | CI workflow diff 검토 — `e2e-mm-boot3` 전용 JDK 17 잡 신설 확인(코드 리뷰 게이트) | review | 🔴 planned |

Coverage: 0/15 green (0%) — target 100% (대상: Must 12 + Should 3, 연기 없음 / Won't 0)

## 테스트 배치·환경 (매트릭스 각주)

- **모듈 배치**: `BaggageHeadersTest`/`StoreCreationRuleIT`/`ProxyExclusionTest`/
  `KnownKeysSnapshotTest`/`HotPathGuardTest` = `agent` 모듈(단위/IT — Java 8 하니스로 충분).
  `MmBaggageHeadersE2E` = `agent`의 기존 e2e 하니스(Jetty, 트레이서 불필요 — JDK 17 불요).
  `MmTracerPathE2E`/`MmBoot3BootE2E`/`MmDistributedFieldE2E` = **신규 독립 빌드
  `e2e-mm-boot3/`**(toolchain 17, 전용 CI 잡) — design §4.5의 격리 근거.
  `HeaderStyleTest` = testkit-core/restassured 모듈.
- **MM-E2E-2 환경**: SUT는 **strict 모드**(기본값)로 실행 — REQ-MM-006의 store 생성 계약과
  같은 모드에서 검증(더 엄격한 경로가 더 드러내는 경로).
- **검증 레벨 범주**: REQ-MM-014의 "문서 검토"와 (공통) CI 배선 행의 "review"는 자동 테스트가
  아닌 **PR 게이트 검토 항목**임을 명시적 범주로 인정한다 — green 판정 = 해당 PR 리뷰에서
  체크됨.

## 커버리지 규칙

- 분모 = Must 12건 + 미연기 Should 3건(REQ-MM-007·013·014) = **15건 전부**.
- REQ-MM-013은 자식-JVM 2개 기반이라 게이트 없음 — 로컬·CI 모두 항상 실행, 분모 유지.
- 폐기·연기 발생 시 이 문서의 매트릭스를 갱신하고 ID는 재사용하지 않는다.
