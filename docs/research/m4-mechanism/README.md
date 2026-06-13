# M4 계측 메커니즘 — 조사·검증 기록

- 작성일: 2026-06-13
- 목적: 병렬 per-test 커버리지 에이전트의 핵심(M4) — "vanilla JaCoCo와 동일한 `.exec`를
  testId별로 분리 생성" — 메커니즘을 **레퍼런스 분석 + 동작 스파이크**로 확정한 기록.
- 결론: **성공**. self-contained(jacoco-core 임베드) 방식으로 vanilla byte-동일 + 병렬 격리를
  실증했고, 이 결과로 설계 스펙·구현 플랜의 메커니즘 부분을 정정함.

## 이 폴더의 구성

| 문서 | 내용 |
|---|---|
| [01-datadog-mechanism.md](01-datadog-mechanism.md) | Datadog `dd-trace-java`의 실제 jacoco 후킹 메커니즘을 소스에서 분석 (클론 방법, 5개 후킹 파일, 동작 원리, 핵심 코드) |
| [02-spike-validation.md](02-spike-validation.md) | self-contained 변형을 검증한 스파이크의 설계·확인한 jacoco 내부 구조·실행 결과·결론 |
| [03-spike-code.md](03-spike-code.md) | 스파이크로 작성한 코드/테스트 전체(파일별 설명 + 실행법 + 에이전트 매핑) |

## 한 문단 요약

JaCoCo는 "프로브를 컨텍스트별로 분리"하도록 설계되지 않았다(전역 `boolean[]` static 필드,
단일 `ExecutionDataStore`). Datadog은 이를 **JaCoCo를 고치지 않고** 푼다: 계측 *시점*에
`ProbeInserter.insertProbe`에 advice를 걸어, JaCoCo가 자기 프로브(`probes[id]=true`)를 심은
직후 **추가(additive)** 바이트코드 `recordCoverage(Class, classId, probeId)`를 같이 심는다.
런타임엔 두 기록이 함께 실행되어 전역 배열(=vanilla)은 무손상, 추가 호출은 ThreadLocal 컨텍스트의
per-test 스토어로 들어간다. 프로브 개수는 `ClassInstrumenter.visitTotalProbeCount`에서 따로 캡처한다.

본 프로젝트는 이 패턴을 **self-contained로 차용**한다: Datadog은 사용자가 붙인 *relocate된*
jacoco 에이전트를 후킹하느라 obfuscate 패키지 매칭 + MethodHandle 우회가 필요했지만, 우리는
jacoco-core를 직접 임베드하므로 **깨끗한 `org.jacoco.core.internal.instr.*`를 직접 캐스팅**으로
후킹한다(더 단순·견고, 버전도 우리가 통제). 스파이크에서 프로브 배열이 vanilla와 **byte 단위로
동일**하고, 2스레드 동시 실행에서 **교차 오염 0**임을 통과 테스트로 확인했다.

## 재현 (스파이크 실행)

```bash
cd spike
JAVA_HOME=<JDK17+ 경로> gradle test     # Gradle 9.x는 실행에 JDK17+ 필요
```
기대 출력:
```
SpikeMechanismTest > parallelContextsAreIsolated()  PASSED   [spike] isolation negLines=[7, 8] posLines=[7, 10, 13]
SpikeMechanismTest > perTestProbesMatchVanillaJacoco()  PASSED   [spike] classId=-2198370455144847958 probes=6 coveredLines=[4, 7, 10, 13]
```

## 관련 산출물

- 설계 스펙: `docs/superpowers/specs/2026-06-13-parallel-jacoco-design.md` (§3 메커니즘은 본 검증 반영)
- 구현 플랜: `docs/superpowers/plans/2026-06-13-jacocoagent-parallel-v1.md` (Task 11이 스파이크 코드 이식)
- 스파이크 코드: `spike/` (검증 통과본)
- Datadog 레퍼런스(분석용 sparse 클론, 세션 한정): `/tmp/dd-trace-java-ref` (Apache 2.0)
