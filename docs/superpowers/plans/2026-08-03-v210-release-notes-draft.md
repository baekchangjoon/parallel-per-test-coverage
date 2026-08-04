# v2.1.0 릴리스 노트 초안 — Micrometer Tracing / B3 지원

> 출처: `docs/superpowers/specs/2026-08-03-micrometer-b3-baggage-design.md`,
> `docs/superpowers/requirements/2026-08-03-micrometer-b3-baggage-requirements.md`
> 용도: `gh release edit v2.1.0 --notes-file <이 파일 기반 발췌>` — `docs/RELEASING.md`의
> "릴리스 노트(소비자 영향 명시)" 절차대로 `--generate-notes`(커밋 자동 요약)가 놓치는
> BREAKING/동작 변화를 상단에 직접 얹는다.

## 한 줄 요약

Micrometer Tracing(Brave/B3) 스택을 쓰는 Spring Boot 3 서비스에서도 pjacoco per-test 커버리지를
쓸 수 있게 됐다 — 인바운드 필드 헤더 인식, testkit `HeaderStyle` 아웃바운드 선택, 트레이서 경로의
동기/async 실행 귀속, 서비스 간 분산 수집까지 커버한다. 그 과정에서 두 가지 기존 동작이 바뀐다
(아래 "동작 변화" 참고 — 대부분의 사용자에게는 영향이 없다).

### BREAKING

없음(추가 호환 확장 — 기존 공개 API·시그니처 무변경. `PjacocoRestAssured.enable()` / `baggageFilter()`
no-arg 오버로드는 그대로 `W3C_BAGGAGE`로 동작).

### 동작 변화

- **폴백(baggage) 유래 키는 더 이상 `traceKeyAutoCreate=true`로 auto-create되지 않는다.**
  기존에는 `traceKeyAutoCreate=true`(트레이서 경로용 옵션)를 켜면 트레이서가 없는 요청도 `test.id`류
  헤더 값만으로 store가 자동 생성됐다. v2.1.0부터는 baggage 폴백 경로에서 온 키는 기존 레지스트리
  계약(strict=시작된 testId만 활성화, autoRegister=생성)을 그대로 따른다 — auto-create는 트레이서가
  실제로 해석한 traceId 키 전용이다. 일반적인 헤더명(`test.id`)의 오탐만으로 store가 무한 생성되던
  위험을 차단하기 위한 변경이다. **영향**: `traceKeyAutoCreate=true` + strict 모드에서 폴백 경로로
  들어오던 미시작 testId 요청은 이제 store가 생기지 않고 기존 strict 거부 회계를 따른다(드문
  조합 — 이 옵션은 트레이서 스택 대상이라 폴백 경로 자체가 희귀 케이스).
- **JVM이 생성하는 프록시·리플렉션 액세서 클래스는 항상 계측 대상에서 제외된다.**
  `jdk.proxy*`/`com.sun.proxy.*`(JDK 동적 프록시)와 `jdk.internal.reflect.GeneratedConstructorAccessor*`
  등(JVM이 특수 로더로 생성하는 리플렉션 액세서)이 자기-제외(`io.pjacoco.`/`org.jacoco.`/`net.bytebuddy.`)와 같은
  층위의 무조건 pre-check로 빠진다 — 사용자 `includes=`로도 재활성화할 수 없다. Spring Boot 3 앱을
  기본 `includes=*`로 계측하면 이 클래스들이 계측되어 `IllegalAccessError`로 부팅이 실패하던 결함의
  수정이다. **영향**: 동적 프록시·리플렉션 액세서는 애초에 커버리지 대상으로 의미가 없는 생성 코드라,
  분모(계측 대상 라인)가 미세하게 줄어드는 것 외 실질적 영향은 없다.

## 신규 기능

- **인바운드 헤더 3종 인식(폴백 경로).** `baggage`(W3C, 기존) 외에 Brave/Micrometer 필드 헤더
  `test.id: <id>`와 legacy Sleuth 헤더 `baggage-test.id: <id>`를 인식한다. 우선순위는
  `baggage` > `test.id` > `baggage-test.id`(첫 유효 값 승리). 트레이서 경로가 활성인 앱에서는
  이 폴백 자체가 적용되지 않는다(키는 항상 traceId).
- **testkit `HeaderStyle`.** `testkit-core`에 `HeaderStyle`(`W3C_BAGGAGE`/`FIELD`/`BOTH`) enum을
  추가하고, `PjacocoRestAssured.enable(HeaderStyle)` / `baggageFilter(HeaderStyle)` 오버로드로
  SUT가 소비하는 형식을 고를 수 있다. 기존 no-arg 오버로드는 무변경 유지.
- **Boot 3 + bridge-brave 트레이서 경로 E2E 검증.** 신규 독립 빌드 `e2e-mm-boot3/`(JDK 17 toolchain,
  전용 CI 잡)에서 동기/async 실행 귀속, 2-hop 분산 FIELD 스타일 수집, 기본값 부팅 성공을 검증한다.
- **관측성 카운터 3종 신설.** `testIdFromW3cBaggage`/`testIdFromFieldHeader`/`testIdFromLegacyFieldHeader`
  — 기존 `fallbackActivations`는 3종 모두에서 계속 증가(신규 카운터는 그 파티션).

## 표면 불변 제약(이번 사이클 안전장치)

신규 에이전트 옵션 0개, `CoverageBridge.recordCoverage` 핫패스 무변경, agent/testkit Java 8 호환
유지 — 자동 가드 테스트(`AgentOptionsParseWarningsTest#knownKeysSetIsFrozenForThisCycle`,
`HotPathGuardTest#recordCoverageSourceUnchanged`) + 기존 jdk8-compat CI 잡으로 고정.

## 좌표

groupId/버전 변경 없음 — `io.github.beltian.pjacoco:*` 좌표를 그대로 쓰고 버전만 `2.1.0`으로
올리면 된다. 자세한 예시는 README ["헤더 규약(Micrometer/Brave B3 지원)"](../../../README.md#헤더-규약-micrometerbrave-b3-지원)
절과 "빠른 시작" 좌표 예시 참고.

## 참고

- 요구사항 추적: `docs/superpowers/requirements/2026-08-03-micrometer-b3-baggage-requirements.md`
  (REQ-MM-001~016, PR 문서동기화 게이트에서 REQ-MM-014 최종 확인).
- 설계 근거: `docs/superpowers/specs/2026-08-03-micrometer-b3-baggage-design.md` §4(설계) 전체,
  특히 §4.1(헤더 3종 우선순위), §4.2(헤더 규약 표), §4.6(트레이서 활성 앱에서의 test.id).
