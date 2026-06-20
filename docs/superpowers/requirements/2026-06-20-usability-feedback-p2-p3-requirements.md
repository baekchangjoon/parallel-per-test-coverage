# 사용성 피드백 대응 요구사항명세 (P2/P3)

> 출처(피드백): `docs/feedback/2026-06-20-pjacoco-사용성-피드백.md` (소비자 관점, v1.1.0 실측)
> 선행 처리: P1-A(`includes=*` 크래시)=PR #21 머지, P1-B(공개 배포)=PR #19 + REQ-D03 추적.
> 이 명세 범위: 남은 P2-C / P2-D / P3-E / P3-F.
> 완료 정의(DoD): 대상(Must + 미연기 Should) 각 REQ가 ≥1개 통과 테스트를 가짐(매트릭스 전부 🟢).
> **본 명세의 Should(U02·U03·U04)는 연기 없이 Must와 동등하게 DoD에 포함**(분모 4). 플러그인/테스트킷의
> 신규 옵션 passthrough는 명시적 후속 과제로 본 범위에서 제외(리뷰 I7).
> **하위호환 원칙(전 범위 공통):** 기존 기본 동작·옵션·산출물 파일명을 바꾸지 않는다. 모든 변경은
> **additive**(새 옵션/별칭/플레이스홀더)여야 하며, 기존 E2E·플러그인·샘플이 무회귀여야 한다.

---

## REQ-U01 — aggregate-only/병렬 실행에서 control endpoint 비용·충돌 제거 (P2-D)
- 유형: Functional / 사용자 대면
- 우선순위: Must
- 설명: per-test 경계가 필요 없는 순수 aggregate/in-process 사용자가 control endpoint의 **포트 바인딩
  비용·충돌**을 피할 수 있어야 한다. 두 가지 additive 수단을 제공한다:
  - (a) `control=false` → endpoint를 **아예 열지 않는다**(기본 `true`로 현행 유지).
  - (b) `port=0` → **임의 빈 포트** 바인딩(병렬 충돌 제거). `start()`가 실제 포트를 반환하므로,
    premain이 그 포트를 **System property `pjacoco.control-port`** 로 노출해 발견 가능하게 한다(리뷰 I1/I3).
- **범위 분리(리뷰 합의):** (a) `control=false` = **aggregate/in-process 전용 사용자의 1차 해법**
  (제어 평면 불필요 → 비용·충돌 모두 제거). (b) `port=0` = 제어 평면은 유지하되 고정 포트 충돌만 피하려는
  경우 — 실제 포트는 `pjacoco.control-port`로 발견. **플러그인/테스트킷의 `-Dpjacoco.control-url` 자동
  연동(ephemeral 포트 전파)은 본 범위 밖(후속 과제)** 이며, 현재 per-test 소비자는 고정 포트 모델을 유지한다.
- 하위호환: 기본값 `control=true`, `port=6310` 유지 — 기존 per-test 플러그인/테스트킷/E2E 무영향
  (`agent/build.gradle.kts`의 e2e 태스크는 명시적으로 `port=63xx`를 주고 `control`을 안 주므로 무회귀).
- 수용기준:
  - Given `control=false`로 attach, When premain이 실행되면, Then `ControlEndpoint`가 생성·start되지
    않고(해당 포트를 테스트가 직접 bind 가능 = 점유 없음) aggregate/in-process 경로는 정상 동작한다.
  - Given `port=0`으로 attach, When endpoint를 시작하면, Then 0이 아닌 실제 ephemeral 포트가 반환되고
    `System.getProperty("pjacoco.control-port")`로 그 값을 읽을 수 있다.
- 검증: unit(AgentOptions `control()` 기본/파싱) + integration(`control=false` 시 endpoint 미생성 →
  같은 포트 bind 성공으로 점유 없음 확인; `port=0` 시 `pjacoco.control-port` 노출) + 기존 control E2E 무회귀.

## REQ-U02 — 멀티모듈에서 `aggregate.exec` 덮어쓰기 방지 (P2-C)
- 유형: Functional / 사용자 대면
- 우선순위: Should
- 설명: 여러 모듈/JVM이 같은 `destfile` 디렉터리에 고정명 `aggregate.exec`를 써서 reactor에서 마지막
  모듈만 남는 문제를, **JVM 단위 네임스페이스**로 해소한다. `aggregateFile` 값에 **`%p`(PID)
  플레이스홀더**를 지원해 `aggregate-%p.exec`처럼 JVM별로 파일이 갈리게 한다. PID는 Java 8 안전하게
  `RuntimeMXBean.getName()`("pid@host")의 `@` 앞 숫자에서 얻고, **형식이 다르거나 파싱 실패 시
  `System.nanoTime()` 기반 hex 토큰으로 fallback**해 유일성을 보장한다(리뷰 I3). 치환은 shutdown 시
  aggregate 쓰기 시점에 1회 수행한다.
- **downstream merge 정합(리뷰 I2 필수):** `TraceCoverageMerger`는 현재 `aggregate.exec` 정확 일치만
  제외하므로, `aggregate-<pid>.exec`가 per-test `.exec`로 오인 병합된다. 따라서 merger의 제외 규칙을
  **aggregate 네이밍 컨벤션**(`aggregate.exec` 또는 `aggregate-*.exec`)으로 확장한다. (완전 임의의
  사용자 지정 aggregateFile 이름은 현재도 merger가 제외 못 하는 기존 한계 — 본 범위 밖.)
- 하위호환: 기본값은 `aggregate.exec`(플레이스홀더 없음) 그대로 — 단일 모듈/기존 소비자 무영향.
  플레이스홀더는 **opt-in**.
- 수용기준:
  - Given `aggregateFile=aggregate-%p.exec`, When 두 JVM이 같은 디렉터리에 aggregate를 쓰면, Then 서로
    다른 파일명(`aggregate-<pid1>.exec`, `aggregate-<pid2>.exec`)으로 공존하며 덮어쓰지 않는다.
  - Given 플레이스홀더 없는 기본값, When aggregate를 쓰면, Then 파일명은 `aggregate.exec` 그대로다.
  - Given `getName()`이 `숫자@host` 형식이 아닌 상황, When `%p`를 치환하면, Then 예외 없이 유일한
    fallback 토큰으로 치환된다.
  - Given `aggregate-<pid>.exec` 파일이 섞인 디렉터리, When `TraceCoverageMerger`가 병합하면, Then 그
    파일은 per-test로 오인되지 않고 제외된다.
- 검증: unit(파일명 해석: `%p`→PID 치환·fallback·무플레이스홀더 불변; merger 제외) + README 멀티모듈 가이드(merge 절차).

## REQ-U03 — premain 실패 표면을 자기 식별 메시지로 (P3-E)
- 유형: Non-functional (진단성)
- 우선순위: Should
- 설명: premain 초기화가 실패할 때 `Exit Code 134`/무맥락 대신 **`[pjacoco][ERROR] …` 식별 메시지를
  stderr에 1줄 남긴다.** premain 본문을 `try/catch(Throwable)`로 감싸 식별 메시지를 남긴 뒤 **rethrow한다
  (log-then-rethrow, fail-fast).** `AgentLog`에 `error(String key, String message)` 추가 — 출력
  `[pjacoco][ERROR] <message>`, 기존 `warn`과 동일 rate-limit(MAX_PER_KEY=20).
- **swallow가 아니라 rethrow인 이유(리뷰 I2 반영):** `CoverageControl.bindRegistry`/`ProbeInstrumentation
  .install`/shutdown hook 등록은 순차적이라, 중간 실패를 swallow하면 **half-init**(레지스트리는 bound인데
  계측 미설치, 또는 shutdown hook 미등록으로 aggregate 미작성) 상태로 testkit이 **조용히 틀린 커버리지**를
  볼 수 있다. 피드백 원문도 "한 줄 남기고 **죽으면** 진단이 쉽다"이므로, 진단 메시지 + fail-fast가
  안전하고 충실하다.
- 비고: P1-A(네이티브 어서션)는 PR #21로 이미 해소됨. 본 REQ는 **catchable한 premain 실패**의 표면을
  개선한다(네이티브 abort는 JVM 레벨이라 Java로 가로챌 수 없음 — 한계 명시).
- 수용기준:
  - Given premain 중 초기화 단계가 Throwable을 던지는 상황, When agent가 로드되면, Then stderr에
    `[pjacoco][ERROR]`로 시작하는 식별 메시지가 1줄 남는다(그 후 fail-fast).
  - Given `AgentLog.error(key, msg)`, When 호출하면, Then `[pjacoco][ERROR] msg`가 stderr에 출력된다.
- 검증: unit(`AgentLog.error`가 `[pjacoco][ERROR]` prefix로 출력) + 주입 가능한 실패로 premain이 식별
  메시지를 남기는지 단위/소형 통합 검증.

## REQ-U04 — 용어/기본 모드 문서화: `destfile`=디렉터리, strict 기본 (P3-F)
- 유형: 문서 / 사용자 대면
- 우선순위: Should
- 설명: ① `destfile`이 실제로는 **출력 디렉터리**라 vanilla jacoco(단일 파일)와 의미가 충돌 — `destdir`
  **별칭**을 추가(`destdir` 우선, 없으면 `destfile`)하고 옵션 표 주석을 강화. ② "테스트킷 없이 in-process
  부착 = **aggregate 전용(vanilla 대체)**, per-test 기록은 0(strict 기본)"을 빠른 시작 옆에 한 줄 명시.
- 하위호환: `destfile` 계속 동작(별칭 추가일 뿐). 조회 우선순위 = **non-empty `destdir` → non-empty
  `destfile` → 기본 `coverage`**(빈 문자열 별칭은 absent로 취급, 리뷰 I5).
- 수용기준:
  - Given `destdir=foo`, When 옵션을 파싱하면, Then 출력 디렉터리는 `foo`다(=`destfile=foo`와 동일).
  - Given `destdir`/`destfile` 둘 다 지정, When 파싱하면, Then `destdir`가 우선한다.
  - Given `destdir=`(빈 값)만, When 파싱하면, Then 기본 `coverage`를 반환한다(빈 별칭=absent).
  - README/README.en에 ①(destfile=디렉터리 설명 유지 + `destdir` 별칭 명시) + ②(in-process 부착 =
    aggregate 전용/strict 기본 한 줄 **신규 추가**)가 반영된다(현행 대비 delta).
- 검증: unit(AgentOptions `destdir` 별칭/우선순위/빈값) + README 문안.

---

## 추적 매트릭스

| REQ-ID | 요구사항 | 검증 | 우선순위 | Status |
|--------|----------|------|----------|--------|
| REQ-U01 | control opt-out(`control=false`) + ephemeral(`port=0`) | `AgentOptionsUsabilityTest#{controlDefaultsTrue,controlFalseDisables}` + `ControlEndpointOptOutE2E#{controlFalseDoesNotBindThePort,defaultControlBindsThePort,ephemeralPortIsExposedViaSystemProperty}` | Must | 🟢 green |
| REQ-U02 | aggregate 파일명 `%p` 네임스페이스 | `AggregateFileNamePidTest#{pidPlaceholderIsSubstituted,pidPlaceholderIsStableWithinAJvm,noPlaceholderIsUnchanged}` + `MergerAggregateExclusionTest#{defaultAggregateNameIsExcluded,pidNamespacedAggregateIsExcluded,perTestExecIsNotExcluded}` | Should | 🟢 green |
| REQ-U03 | premain 실패 `[pjacoco][ERROR]` + fail-fast | `AgentLogTest#errorPrintsPjacocoErrorPrefix` + Bootstrap premain try/catch→log.error→rethrow(구조) | Should | 🟢 green |
| REQ-U04 | `destdir` 별칭 + strict/aggregate 문서화 | `AgentOptionsUsabilityTest#{destdirAliasResolvesOutputDir,destdirTakesPrecedenceOverDestfile,emptyDestdirIsTreatedAsAbsent,destfileStillWorks}` + README/en 문안 | Should | 🟢 green |

DoD 대상(분모) = Must(U01) + 미연기 Should(U02·U03·U04) = 4 → **4/4 🟢 (100%)**.
