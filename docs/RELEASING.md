# 릴리스 절차 (pjacoco)

버전은 **lockstep**(모든 모듈 동일 SemVer)으로 올린다. 과거 `1.2.0` bump 때 `DEFAULT_AGENT_VERSION`을
놓쳐 CI가 `agent:1.1.0 not found`로 깨진 적이 있어, 아래 단일소스화 + 가드를 둔다.

## 자동 추적(편집 불필요)

- **`PjacocoPlugin.DEFAULT_AGENT_VERSION`** — 더 이상 하드코딩하지 않는다. 빌드가 생성하는 리소스
  `gradle-plugin/.../version.properties`(= `project.version`)에서 런타임에 읽으므로 플러그인 자기 버전을
  자동으로 따라간다. (`gradle-plugin/build.gradle.kts`의 `generateVersionResource`.)
- **릴리스 산출물 버전** — `release` 워크플로가 빌드 전에 **소스 버전(`build.gradle.kts`) == 릴리스 버전**을
  검증한다(불일치 시 실패). 즉 워크플로 `version` input만 바꿔 릴리스할 수 없다 — 반드시 소스 bump 커밋을
  태그에 포함해야 한다.

## 버전 bump 시 편집할 곳 (체크리스트)

> 단일 정본은 `build.gradle.kts`의 `releaseVersion` 기본값. 나머지는 소비자 대면 리터럴이라 함께 맞춘다.
> (향후 version catalog로 더 줄일 수 있음 — 현재는 이 목록을 유지한다.)

- [ ] `build.gradle.kts` — `version = ... getOrElse("X.Y.Z")` (정본)
- [ ] `maven-plugin/pom.xml` — `<version>` + `<pjacoco.agent.version>`
- [ ] `samples/gradle-sample/build.gradle.kts` — `id("io.github.beltian.pjacoco") version "X.Y.Z"` + `val pjacocoVersion`
- [ ] `samples/maven-sample/pom.xml` — `<pjacoco.version>`
- [ ] `README.md` / `README.en.md` — 플러그인/테스트킷 좌표 + 다운로드 예시(`pjacoco-agent-X.Y.Z.jar`)
- [ ] (자동) `PjacocoPlugin.DEFAULT_AGENT_VERSION` — 편집 금지(생성 리소스가 처리)
- [ ] functional test는 버전 리터럴을 두지 않는다 — `ItSupport.itVersion()`가 빌드 주입값을 강제(미주입 시 실패)

## 릴리스 실행

1. 위 체크리스트대로 bump한 **커밋을 main에 머지**(소스 버전 = 릴리스 버전).
2. PR CI green 확인(샘플 E2E가 mavenLocal로 새 버전 resolve).
3. `release` 워크플로 dispatch: `gh workflow run release.yml --ref main -f version=X.Y.Z`
   - "Verify source version matches the release version" 가드가 통과해야 진행된다.
   - 산출 자산: `pjacoco-agent` + testkit 4종 + `pjacoco-maven-plugin` jar(+각 `.sha256`).
4. 릴리스 노트에 **소비자 영향(BREAKING 등)**을 명시한다(자동 생성 노트만으로는 약함).

## release-guard (수동 릴리스·태그 오지정 탐지)

검증 로직은 `.github/scripts/release-guard.sh` 하나이고, 두 경로로 실행된다:

- **정상 릴리스**: `release` 워크플로가 릴리스 생성 직후 같은 스크립트를 **자체 검증 단계**로
  실행한다. (GITHUB_TOKEN으로 만든 태그·릴리스는 다른 워크플로의 이벤트 트리거를 발화시키지
  않으므로, 아래 독립 워크플로만으로는 정상 경로가 커버되지 않는다.)
- **수동 태그/릴리스**: `release-guard` 워크플로(`.github/workflows/release-guard.yml`)가
  사람 자격증명(PAT)으로 만든 `v*` 태그 push·release 생성/수정에 반응한다 — 정확히 이
  가드가 잡으려는 사례(v1.4.0 사고 유형). workflow_dispatch(tag 입력)로 수동 재검증도 가능.

검증 내용:

- 태그 커밋의 소스 버전 == 태그 버전: `build.gradle.kts`, `maven-plugin/pom.xml`의
  `<version>`과 `<pjacoco.agent.version>`(1.2.0 사고 재발 방지). samples/README 리터럴은
  릴리스 산출물이 아니므로 범위 외(위 체크리스트로 관리).
- 릴리스 자산이 완전한지: agent + testkit 4종 + maven-plugin jar, 각 jar마다 `.sha256`,
  고아 체크섬·정체불명 자산 없음. (deprecated alias `jacocoagent-parallel-*.jar`는 선택 —
  있으면 `.sha256` 필수.)

GitHub에는 릴리스 생성 자체를 **차단**하는 수단이 없으므로 이 가드는 **탐지 컨트롤**이다 —
위반 시 Actions가 빨간 실패로 드러낸다. 태그 push 시점에는 릴리스가 아직 없을 수 있어 자산
검사만 건너뛴다(release 이벤트/자체 검증 단계에서 재검사).

guard 워크플로는 **스크립트를 default branch에서, 검증 대상 코드를 태그에서** 각각
체크아웃한다. 따라서 guard 도입 이전 태그(v1.4.0 등)도 workflow_dispatch로 재검증할 수
있고, 판정 기준은 언제나 현재 main의 스크립트다(스크립트를 고치면 과거 태그의 판정도
소급 적용 — 자산 규칙이 바뀌면 옛 태그 재검증은 새 규칙으로 판정된다는 점 유의).

## 릴리스 노트 (소비자 영향 명시)

`--generate-notes`(커밋 자동 요약)만으로는 **BREAKING/동작 변화**가 묻힌다. 다음에 해당하면 릴리스 노트
상단에 **`### BREAKING` / `### 동작 변화` 섹션을 직접 추가**한다(`gh release edit <tag> --notes-file ...`):

- 산출물/좌표 이름 변경, 옵션 기본값 변경, 출력 형식·파일명 변경 → BREAKING + 마이그레이션 한 줄.
- 관측 가능한 동작 변화(예: `@Test(timeout)`이 이제 `incompleteAttribution` `.exec`를 생성) → 동작 변화.

## agent jar 이름 변경 + deprecated alias (v1.3.0~)

- agent jar 산출물명 = `pjacoco-agent`(= Maven artifactId). v1.3.0에서 구명 `jacocoagent-parallel`에서
  변경 — **이름이 아니라 좌표 `io.github.beltian.pjacoco:pjacoco-agent`로 의존**할 것.
- `release.yml`은 v1.3.0~v1.4.x 동안 구이름 `jacocoagent-parallel-<ver>.jar`를 deprecated alias 자산으로
  함께 첨부했다. **v2.0.0에서 예정대로 제거**(스테이징 jar 개수 가드 7→6 반영, release-guard는 alias를
  이전부터 선택 자산으로 취급하므로 무변경).

## 참고

- 공개 저장소 자동 배포(Maven Central / Gradle Plugin Portal)는 **배선 완료·가동 중**(v2.0.0부터,
  secrets-게이트) — Central은 v2.0.0 공개 완료, Gradle Plugin Portal은 최초 승인 대기. `docs/PUBLISHING.md`,
  REQ-D03(`docs/superpowers/requirements/2026-06-20-distribution-onboarding-requirements.md`) 참고.
