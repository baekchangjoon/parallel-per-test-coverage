# 배포·온보딩 요구사항명세

> 출처(design spec): `docs/superpowers/specs/2026-06-16-pjacoco-ergonomics-design.md`
> (§"Distribution target" / §7 "Versioning, compatibility, publishing")
> 보조 문서: `docs/PUBLISHING.md`, `README.md` "빠른 시작 (권장)"
> 완료 정의(DoD): 커버리지 대상(분모) = **Must + 미연기 Should**(본 명세에는 Should 없음 → 실질 분모 = 미연기 Must). 각 대상 REQ가 ≥1개의 통과 수용 테스트/검증을 가짐(대상 매트릭스 전부 🟢). 명시적으로 연기한 항목은 `🔵`로 표시해 분모에서 제외한다.

## 배경

ergonomics design spec(2026-06-16)은 **로컬 우선(local-first)** 배포 전략을 채택했다:
"build & validate locally first (`publishToMavenLocal` / `includedBuild`); design the publication
metadata for Maven Central (libraries) and Gradle Plugin Portal (the Gradle plugin) so the public
publish is a **credentials-gated follow-up**." 즉 공개 저장소 자동 배포는 **의도적으로 후속 과제로 연기**했다.

현재 상태(2026-06-20 확인):
- `release.yml`은 **agent 셰이드 jar 1개만** GitHub Release에 올린다(`:agent:shadowJar` → `gh release create`).
- testkit·Gradle plugin·Maven plugin은 **Maven Central / Gradle Plugin Portal / GitHub Packages 어디에도
  자동 publish되지 않는다**(워크플로에 publish 스텝 없음).
- Gradle 모듈의 POM 메타데이터 + (secrets-gated) GPG 서명(root `build.gradle.kts`) + Gradle Plugin Portal
  메타데이터(`com.gradle.plugin-publish`)는 **배선되어 있으나**, **Central Portal 업로드 계층**(예:
  `com.vanniktech.maven.publish`)은 어떤 `build.gradle.kts`에도 적용돼 있지 않고 실제 업로드 스텝도
  `release.yml`에 **아직 없다**. 별도 Maven 빌드인 `maven-plugin/pom.xml`은 Central 필수 메타데이터
  (url/licenses/developers/scm)·`maven-gpg-plugin`이 **아직 없다**(REQ-D03 범위).

이 명세는 그 연기 결정을 **추적 가능한 REQ-ID로 고정**하고, 공개 배포 전까지 사용자가 막히지 않도록
로컬 경로를 1차로 안내하는 것을 요구사항으로 박는다.

---

## 요구사항 목록

### REQ-D01 — 로컬 설치 경로로 전 모듈 사용 가능 + README 1차 안내
- 유형: Functional / 사용자 대면
- 우선순위: Must
- 설명: 공개 배포 전이라도 사용자는 **소스 빌드 + 로컬 설치**만으로 전 모듈(agent·testkit·Gradle/Maven
  플러그인)을 소비할 수 있어야 하고, `README.md` "빠른 시작 (권장)"이 이 로컬 경로를 **공개 좌표 스니펫보다
  먼저, 명확히** 안내해야 한다. 공개 좌표(`io.pjacoco:…`, `id("io.pjacoco.gradle")`) 스니펫은 그대로 두되,
  "아직 공개 배포되지 않음 → 지금은 로컬 설치 필요" 경고를 **스니펫 앞**에 노출해, 복붙 시 resolve 실패를
  버그로 오인하지 않게 한다.
- 수용기준:
  - (Gradle) Given 공개 저장소에 아티팩트가 없는 상태, When 사용자가
    `:agent:publishToMavenLocal :testkit-*:publishToMavenLocal :gradle-plugin:publishToMavenLocal`로
    로컬 설치 후 `samples/gradle-sample`을 실행하면, Then `id("io.pjacoco.gradle")`가 mavenLocal에서
    resolve되고 per-testId `.exec`가 생성된다. (**`:gradle-plugin:publishToMavenLocal`이 빠지면 플러그인
    resolve가 실패**하므로 필수.)
  - (Maven) Given 위와 동일 상태, When `mvn -f maven-plugin/pom.xml install` 후 `samples/maven-sample`을
    실행하면, Then per-testId `.exec`가 생성된다.
  - Given `README.md`·`README.en.md`의 "빠른 시작 (권장)" 섹션, When 사용자가 위에서부터 읽으면, Then 공개
    좌표 스니펫에 도달하기 **전에** "현재 로컬 설치 필요"가 고지되어 있다.
- 검증: 기존 sample E2E(CI의 `samples/gradle-sample`·`samples/maven-sample` job, **mavenLocal 경유** —
  includedBuild 미사용, ergonomics spec §10 accepted deviation) + README/PUBLISHING.md 문안 점검
  (로컬 설치 명령이 `:gradle-plugin:publishToMavenLocal`을 포함하고 CI job과 일치)

### REQ-D02 — 배포 상태 문서 ↔ 코드(release.yml) 정합
- 유형: Non-functional (정합성 / drift 방지)
- 우선순위: Must
- 설명: 배포 상태를 기술하는 모든 산문/주석(`build.gradle.kts` 주석, `docs/PUBLISHING.md`, `README.md`,
  `README.en.md`)이 `release.yml`의 **실제 상태**와 일치해야 한다. 특히 아직 배선되지 않은 공개 업로드를
  "배선됨(wired)"으로 표기하지 않는다(미배선을 미배선으로, 연기를 연기로 정직하게 기술). 국문/영문 README는
  동일한 "스니펫 앞 경고" 온보딩 경험을 가져야 한다.
- 수용기준:
  - Given `build.gradle.kts`/`PUBLISHING.md`/`README.md`의 배포 관련 문장, When `release.yml`의 실제 스텝과
    대조하면, Then "Central Portal 업로드가 release workflow에 배선됨" 같은 사실과 어긋나는 표기가 없다.
- 검증: 문서 대조(diff 리뷰) — `release.yml` 스텝 목록 vs 문서 주장

### REQ-D03 — 공개 저장소 자동 배포 (Maven Central + Gradle Plugin Portal) — 🔵 연기(추적용)
- 유형: Functional / 사용자 대면
- 우선순위: Must(연기) — ergonomics spec의 "credentials-gated follow-up" 결정에 따라 **명시적으로 연기**
- 설명: `release.yml`에 **secrets-gated** 공개 배포 스텝을 추가해, (a) 라이브러리 + agent + maven-plugin을
  **Maven Central**(Sonatype Central Portal, `com.vanniktech.maven.publish`)에, (b) Gradle 플러그인을
  **Gradle Plugin Portal**(`:gradle-plugin:publishPlugins`)에 올린다. 완료되면 사용자는 README의 공개 좌표
  스니펫을 **복붙만으로 resolve**할 수 있어야 한다(로컬 설치 불필요).
- 수용기준(완료 시):
  - Given 배포 secrets가 설정된 상태, When `release` 워크플로를 실행하면, Then **agent(`io.pjacoco:pjacoco-agent`
    shaded)** + testkit + Gradle plugin + maven plugin이 각 공개 저장소에 게시되고, 신규 소비자가
    `id("io.pjacoco.gradle") version "<v>"` / `testImplementation("io.pjacoco:pjacoco-testkit-junit5:<v>")`를
    로컬 설치 없이 resolve한다.
  - Given secrets가 없는 fork/PR, When 워크플로가 돌면, Then 공개 배포 스텝은 **건너뛰고**(silent publish
    없음) agent jar GitHub Release 경로는 현행대로 동작한다.
- **선결 결정:** Maven Central 네임스페이스 소유권 확정 — `io.pjacoco` 소유 검증 vs `io.github.baekchangjoon`
  폴백(ergonomics spec 미해결 항목). 공개 배포 착수 전 1회 결정 필요.
- **선결 작업(코드, 아직 미적용):** ① Central Portal 업로드 플러그인(예: `com.vanniktech.maven.publish`, 또는
  maven-plugin은 `central-publishing-maven-plugin`)을 해당 모듈에 **적용** — 현재 어떤 빌드에도 없음.
  ② `maven-plugin/pom.xml`에 Central 필수 POM 메타데이터(url/licenses/developers/scm) + `maven-gpg-plugin`
  추가. ③ `release.yml`에 secrets-gated publish 스텝 추가.
- **완료 트리거(이 REQ가 🟢로 전환될 때 함께 할 일):** README "빠른 시작"의 "로컬 설치 필요" 경고 제거 +
  공개 좌표를 기본 경로로 승격(REQ-D01 문안 역갱신).
- 상태: 🔵 **연기(분모 제외)** — 추적용. 현재 미구현.

---

## 추적 매트릭스

| REQ-ID | 요구사항 | 검증 | 우선순위 | Status |
|--------|----------|------|----------|--------|
| REQ-D01 | 로컬 설치 경로 사용 가능 + README 1차 안내 | CI sample E2E(gradle-sample/maven-sample, mavenLocal) + 국/영 README·PUBLISHING.md 문안 점검(`:gradle-plugin:publishToMavenLocal` 포함) | Must | 🟢 green |
| REQ-D02 | 배포 상태 문서↔release.yml 정합 | 문서 대조(release.yml 스텝 vs 주장) | Must | 🟢 green |
| REQ-D03 | 공개 저장소 자동 배포(Central + Plugin Portal) | (완료 시) secrets-gated 워크플로 게시 + 복붙 resolve | Must(연기) | 🔵 연기(분모 제외) |

**완료 상태(DoD):**
- 대상(분모) = Must 미연기 = REQ-D01, REQ-D02 → **2/2 🟢 (100%)**
- 연기(분모 제외) = REQ-D03 🔵 — ergonomics spec "credentials-gated follow-up" 결정에 따른 추적용 항목.
  공개 배포 완료 시 🟢 전환 + README 좌표 승격.
