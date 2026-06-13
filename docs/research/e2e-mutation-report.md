# E2E 뮤테이션 테스트 보고서

- 일자: 2026-06-13
- 목적: `SpecAcceptanceE2E`(out-of-process HTTP 블랙박스 e2e)가 **실제로 회귀를 잡는지** 검증.
- 방법론: [baekchangjoon/spring-mutation-tests](https://github.com/baekchangjoon/spring-mutation-tests)
  의 `pitbox`(SUT mutant 주입 → 분리 프로세스 블랙박스 테스트로 KILLED/SURVIVED 측정)를 차용.
  pitbox는 Docker+Spring SUT 전용이라, 본 프로젝트(java agent SUT)에는 동일 방법론을
  소스 레벨로 적용했다: 에이전트 SUT에 mutant 1개 주입 → 에이전트 jar 재빌드 → 프레시
  coverage dir로 e2e 실행 → 종료코드로 분류. 재현: `scripts/mutation-e2e.sh`.

## 결과 (최종)

| mutant | SUT 변형 | 결과 | 잡은 e2e 보장 |
|---|---|---|---|
| M1 | `CoverageContext` ThreadLocal → 전역 static | **KILLED** | concurrentIsolation (스레드 격리) |
| M2 | `TestStore` 프로브 비트 미기록 (`=true`→`=false`) | **KILLED** | 격리 (커버리지 공집합) |
| M3 | `ServletAdvice` 요청 종료 시 `clear()` 누락 | **KILLED** | untagged 누수 (워커 재사용 오염) |
| M4 | strict → lenient(미등록 testId 자동 생성) | **KILLED** | strict 모드 (GHOST flush) |
| M5 | `BaggageParser`가 test.id 추출 실패(항상 null) | **KILLED** | 격리 (라우팅 없음) |
| M6 | 사이드카 `classCount` 항상 0 | **KILLED** | 사이드카 스키마 |
| M7 | 재시도 시 스토어 미리셋(누적) | **KILLED** | 재시도 덮어쓰기 |
| M8 | manifest에 commitSha 누락 | **KILLED** | manifest 헤더 |
| M9 | stop이 flush 안 함(.exec 미생성) | **KILLED** | 격리/사이드카 (파일 부재) |

**Mutation score: 9/9 (100%)** — baseline 통과.

## 1차 실행에서 발견한 구멍 2개 (강화 전 → 후)

뮤테이션 테스트가 **느슨한 검증 2개를 실제로 적발**했다:

1. **M3 SURVIVED → KILLED**: `clear()` 누락을 e2e가 못 잡았다(단위 `ThreadReuseTest`는 잡음).
   원인: 모든 의미 있는 요청이 자기 Baggage로 컨텍스트를 재설정해, 누락된 clear가
   관측되지 않음. → **강화**: `untaggedRequest_notRecorded_andNoThreadLeak`에서 워커 풀을
   T_LEAK(positive)로 warm한 뒤 untagged(negative) 요청을 보내, 재사용 워커로의 누수가
   있으면 T_LEAK가 negative 라인을 얻도록 → `assertEquals(posBase, leak)`로 적발.

2. **M4 SURVIVED → KILLED**: strict→lenient 변형을 못 잡았다. 원인: GHOST가 lazy 생성돼도
   **stop을 안 호출하니 flush가 안 돼** 파일이 안 생김(파일 부재로 통과). → **강화**:
   strict 테스트에서 GHOST를 **명시적으로 stop**한 뒤 `.exec`/`.json` 부재를 단언 →
   lenient면 stop이 flush해 파일이 생기므로 적발.

## 결론

초기 e2e는 형태는 맞았으나 격리(부분 오염)·strict·thread-leak에서 **구멍이 있었고**,
뮤테이션 테스트로 이를 정량 적발해 닫았다. 현재 e2e는 대표 mutant 9종을 모두 잡으며,
스펙 §1·§4·§5·§7의 핵심 보장이 회귀 시 실패하도록 보장된다.
