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
- [ ] `samples/gradle-sample/build.gradle.kts` — `id("io.pjacoco.gradle") version "X.Y.Z"` + `val pjacocoVersion`
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

## 참고

- 공개 저장소 자동 배포(Maven Central / Gradle Plugin Portal)는 아직 미배선 — `docs/PUBLISHING.md`,
  REQ-D03(`docs/superpowers/requirements/2026-06-20-distribution-onboarding-requirements.md`) 참고.
- agent jar 산출물명은 `pjacoco-agent`(= Maven artifactId). v1.3.0에서 구명 `jacocoagent-parallel`에서
  변경됨 — 이름이 아니라 좌표 `io.pjacoco:pjacoco-agent`로 의존할 것.
