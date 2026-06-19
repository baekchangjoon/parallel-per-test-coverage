# 결정 기록 — ServletAdvice.deactivate() 컨텍스트 정리 의미

> 상태: **결정됨(accepted)** · 날짜: 2026-06-19 · 범위: C1(trace-context per-test coverage) · 관련 REQ: REQ-001, REQ-006, REQ-008, REQ-009
> 결정 경위: subagent-driven 구현 중 발견 → 비서 위임(deferred, record `b4d60288`) → 사용자 승인(권고대로 진행 + 이력 문서화).

## 맥락

`io.pjacoco.agent.inbound.servlet.ServletAdvice`는 `HttpServlet.service()`에 weave된 body-only advice다.
`enter`(=`activate`)에서 요청의 coverage key를 resolve해 `CoverageContext`(ThreadLocal `TestStore`)에
바인딩하고, `exit`(=`deactivate`)에서 정리한다. 핫패스(`CoverageBridge.recordCoverage`)는 이
`CoverageContext`를 **probe당 1-read**만 한다(REQ-001).

M5(`a2f63e1`)부터 존재한 기존 단위 테스트 `ThreadReuseTest.contextClearedAfterRequestSoReusedThreadIsClean`는
**스레드풀 위생 불변식**을 고정한다: 워커 스레드가 재사용될 때 이전 요청의 `TestStore`를 상속하면 안 된다.
상속하면 다음 요청의 probe가 **엉뚱한 testId에 기록**되어 *잘못된 커버리지 귀속(정확성 버그)*이 된다.
그래서 이 테스트는 `deactivate()`가 `CoverageContext`를 **무조건** clear할 것을 단언한다.

## 충돌

C1 plan Task 9는 통합 `CoverageKeyResolver` 도입과 함께 `deactivate()`를 다음으로 바꾸도록 명령했다:
`exit가 trace-set 컨텍스트를 clear하지 않도록 "내가 set한 경우만 clear"` 가드(`SET_BY_US` ThreadLocal 마커).
구현 후 결과:

- `deactivate()`가 `Boolean.TRUE.equals(SET_BY_US.get())`일 때만 clear → `activate`를 거치지 않고
  외부에서 set된 컨텍스트는 정리하지 않음.
- Task 9가 추가한 새 테스트 `deactivateLeavesContextWeDidNotSet`는 "외부 set 컨텍스트를 deactivate가
  보존한다"를 단언 → 기존 `ThreadReuseTest`(외부 set 컨텍스트를 deactivate가 clear한다)와 **정면 모순**.
  둘은 동시에 통과할 수 없다.

`SET_BY_US` 가드의 의도는 "trace scope가 set한 컨텍스트를 servlet exit가 조기에 덮어쓰지 않게" 하는 것이었다.

## 분석 — 가드는 실제 trace 흐름에서 무의미하다

요청 스레드에서 가드가 무언가를 보존하려면 "**weave(trace scope)는 컨텍스트를 set했지만 `activate`는
set하지 않은**" 상태가 servlet exit 시점에 존재해야 한다. 그런데:

- **scope가 servlet `activate` 시점에 이미 열려 있으면**, resolver의 `OtelTestIdSource`/`BraveTestIdSource`가
  현재 스레드의 trace context에서 키를 찾는다 → `activate`가 키를 바인딩하고 `SET_BY_US`를 set한다 →
  가드 조건(SET_BY_US=true) 충족 → deactivate가 어차피 clear한다. (가드가 보존하지 않음.)
- **scope가 `activate` 이후 핸들러 안에서 열렸다 닫히면**, scope는 servlet exit **이전에** 닫히며,
  `TraceScopeBridge.exit`가 이전 값(보통 null)을 복원한다 → servlet exit 시점 컨텍스트는 이미 정리됨.
  (가드가 보존할 대상이 없음.)
- **async 핸드오프(REQ-006)는 별도 워커 스레드**에서 일어난다. 워커 스레드에서는 트레이서가 scope를
  **재진입**하고, weave가 그 스레드의 `CoverageContext`를 자체 `onScopeEnter`/`onScopeExit`로
  set/복원한다(`TraceScopeBridge`, cross-thread-safe, ownerThread 가드). 요청 스레드의 servlet
  `deactivate`와 **무관**하다.

즉 `SET_BY_US` 가드는 어떤 현실적 순서에서도 보존 효과가 없으면서, `ThreadReuseTest`가 지키는
정확성 불변식만 깬다. async 커버리지 귀속(REQ-006)은 weave 경로(`BraveScopeWeaveIT`로 실제 Brave에서
sync+async in-process 검증 완료)가 책임지며 servlet deactivate에 의존하지 않는다.

## 결정

1. `ServletAdvice.deactivate()`를 **무조건 `CoverageContext.clear()`** 로 복원한다(M5 이전 동작).
2. `SET_BY_US` ThreadLocal 마커와 `activate`의 해당 set을 제거한다(더 이상 읽히지 않는 dead state).
3. Task 9가 추가한 모순 테스트 `deactivateLeavesContextWeDidNotSet`를 제거한다.
4. Task 9의 나머지(통합 `CoverageKeyResolver`, `forCoverageKey` 라우팅)는 **유지**한다 — 이 부분은
   가치가 있고 충돌과 무관하다.
5. `ThreadReuseTest`를 다시 green으로 복원한다.

## 근거

- **정확성 우선.** 스레드풀 위생(재사용 워커가 stale store를 상속하지 않음)은 커버리지 귀속의 정확성을
  좌우한다. 가드가 이를 깨는 대가로 얻는 이득이 없다.
- **가역성·국소성.** 변경은 `deactivate` 본문 + 마커 제거 + 모순 테스트 제거로 국소적이고 가역적이다.
- **plan 텍스트보다 불변식.** plan의 예시 코드/지시는 출발점이며, 구현 중 드러난 기존 정확성 불변식과
  충돌하면 불변식이 우선한다(사용자 승인).

## 대안(기각)

- **`SET_BY_US` 유지 + `ThreadReuseTest` 수정/삭제.** 기각 — 정확성 불변식을 약화시키고, 가드가 보존하는
  실질 시나리오가 없어 순이득이 음(-)이다.
- **deactivate에서 "trace scope 활성 여부"를 감지해 조건부 clear.** 기각 — 복잡도만 늘고, 위 분석상
  trace scope는 자체 enter/exit로 컨텍스트를 관리하므로 servlet의 추가 조정이 불필요하다.

## 영향 / 추적

- 코드: `agent/.../inbound/servlet/ServletAdvice.java`(deactivate 복원, SET_BY_US 제거),
  `ServletAdviceTest.java`(모순 테스트 제거). 핫패스(REQ-001) 무변경.
- 테스트: `ThreadReuseTest` green 복원; 기존 baggage/strict 동작(REQ-008 라우팅) 무회귀.
- C1 plan Task 9 항목은 "resolver + forCoverageKey 라우팅 유지, SET_BY_US 가드 철회"로 갱신된 것으로 본다.
- REQ-006 async 귀속은 weave 경로(Task 10a/10b-1)가 담당하며 이 결정의 영향을 받지 않는다.
