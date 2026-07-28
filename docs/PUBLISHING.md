# Publishing pjacoco artifacts

The build produces these artifacts (single lockstep SemVer; bump with `-PreleaseVersion=x.y.z`):

| Artifact | Name | Built by | Notes |
|---|---|---|---|
| Agent (`-javaagent`) | `io.github.beltian.pjacoco:pjacoco-agent` | Gradle (`:agent`) | the **shaded** jar (relocated jacoco-core + byte-buddy) |
| Testkit core | `io.github.beltian.pjacoco:pjacoco-testkit` | Gradle | zero-dep, Java 8 |
| Testkit JUnit 5 | `io.github.beltian.pjacoco:pjacoco-testkit-junit5` | Gradle | `PjacocoExtension` |
| Testkit JUnit 4 | `io.github.beltian.pjacoco:pjacoco-testkit-junit4` | Gradle | `PjacocoRule` |
| Testkit REST Assured | `io.github.beltian.pjacoco:pjacoco-testkit-restassured` | Gradle | baggage filter |
| Gradle plugin | `io.github.beltian.pjacoco` (plugin id) | Gradle (`:gradle-plugin`) | Gradle Plugin Portal |
| Maven plugin | `io.github.beltian.pjacoco:pjacoco-maven-plugin` | Maven (`maven-plugin/`) | `prepare-agent` goal |

## Local validation (no credentials — this is what CI runs on every PR)

```bash
# 1) Libraries + agent (shaded) + Gradle plugin to your local Maven repo
#    (:gradle-plugin:publishToMavenLocal is required for the gradle-sample consumer to resolve the
#     io.github.beltian.pjacoco plugin id from mavenLocal — see ci.yml "gradle-sample" job)
./gradlew :agent:publishToMavenLocal \
  :testkit-core:publishToMavenLocal :testkit-junit5:publishToMavenLocal \
  :testkit-junit4:publishToMavenLocal :testkit-restassured:publishToMavenLocal \
  :gradle-plugin:publishToMavenLocal

# 2) Maven plugin (resolves io.github.beltian.pjacoco:pjacoco-agent from step 1)
mvn -f maven-plugin/pom.xml install

# 3) End-to-end consumers
mvn -f samples/maven-sample/pom.xml test     # produces target/pjacoco/T1.exec
./gradlew :gradle-plugin:test                # the TestKit consumer produces build/pjacoco/T1.exec
./gradlew -p samples/gradle-sample test      # the gradle-sample consumer (resolves plugin from mavenLocal)
```

The Gradle modules' POM metadata (MIT license, scm, developers) and GPG signing are already configured
in the root `build.gradle.kts`; signing is **gated** — it activates only when `SIGNING_KEY` is present,
so local builds without keys still work. NOTE: the standalone `maven-plugin/pom.xml` does **not** yet
carry the Central-required POM metadata (url/licenses/developers/scm) or `maven-gpg-plugin` — wiring that
is part of the deferred public-publish work (REQ-D03).

## Public release (credentials-gated) — WIRED into `release.yml` (2026-07-28, REQ-D03)

> Status: as of **v2.0.0** the coordinates are `io.github.beltian.pjacoco:*` (plugin id
> `io.github.beltian.pjacoco`) and `release.yml` carries three **secrets-gated** publish steps that
> run automatically after the GitHub Release once the corresponding secrets exist (absent secrets →
> steps are skipped, the GitHub Release is unaffected):
>
> 1. **Libraries → Central Portal**: agent + 4 testkits publish into `build/staging-deploy`
>    (signed, checksummed), `centralBundle` zips the tree, and the bundle is POSTed to the Portal
>    Publisher API with `publishingType=USER_MANAGED` — artifacts wait for a manual **Publish**
>    click at <https://central.sonatype.com/publishing>.
> 2. **maven-plugin → Central**: `mvn -Pcentral deploy` (gpg + central-publishing-maven-plugin,
>    `autoPublish=false` — same manual-publish policy).
> 3. **Gradle plugin → Plugin Portal**: `:gradle-plugin:publishPlugins` (reads
>    `GRADLE_PUBLISH_KEY`/`GRADLE_PUBLISH_SECRET` from the environment).
>
> **One-time setup before the first publish** (owner actions):
> - Sign in to <https://central.sonatype.com> with the **beltian** GitHub account → the
>   `io.github.beltian` namespace auto-verifies → generate a user token → GitHub secrets
>   `MAVEN_CENTRAL_USERNAME`/`MAVEN_CENTRAL_PASSWORD`.
> - Generate a GPG key, publish the public key to a keyserver, register secrets
>   `SIGNING_KEY` (ASCII-armored private key) / `SIGNING_PASSWORD`.
> - Register the Gradle Plugin Portal API keys (`GRADLE_PUBLISH_KEY`/`GRADLE_PUBLISH_SECRET`).
>
> After the first successful publish: flip `USER_MANAGED`→`AUTOMATIC` / `autoPublish=true` if the
> manual click becomes ceremony, and update the README's "not yet published" warning.

## (superseded) original deferred-wiring notes

> Status (2026-06-20): as of **v1.3.0**, `release.yml` attaches the **agent shaded jar + the four
> testkit jars (`pjacoco-testkit[-junit5|-junit4|-restassured]`) + the maven-plugin jar** to the GitHub
> Release (named by their Maven artifactId, e.g. `pjacoco-agent-1.3.0.jar`). What is still **not** wired
> is the **public-repository upload** (Maven Central / Gradle Plugin Portal) below — the deferred,
> credentials-gated follow-up tracked as REQ-D03 in
> `docs/superpowers/requirements/2026-06-20-distribution-onboarding-requirements.md`. Until that lands,
> consume the modules via `publishToMavenLocal` or grab the GitHub Release jars directly (the Gradle
> plugin id still resolves only via the Plugin Portal / mavenLocal).

Once wired, these steps will run from the `release` workflow and only execute when the corresponding
secrets exist.

### Required secrets

| Target | Secrets |
|---|---|
| Maven Central (libraries + maven plugin) | `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` (Central Portal token), `SIGNING_KEY` (ASCII-armored GPG private key), `SIGNING_PASSWORD` |
| Gradle Plugin Portal (gradle plugin) | `GRADLE_PUBLISH_KEY`, `GRADLE_PUBLISH_SECRET` |

### Gradle plugin → Gradle Plugin Portal

```bash
GRADLE_PUBLISH_KEY=… GRADLE_PUBLISH_SECRET=… ./gradlew :gradle-plugin:publishPlugins
```

### Libraries → Maven Central (Sonatype **Central Portal**, not legacy OSSRH)

Signing + POM metadata are in place. The remaining wiring is the Central Portal upload step: apply
`com.vanniktech.maven.publish` (or the Central Portal `central-publishing-maven-plugin` for the maven
plugin), which bundles the signed artifacts and uploads them via the Portal Publisher API at
`https://central.sonatype.com`. The legacy OSSRH/nexus-staging JIRA flow is **not** used.

> Namespace: RESOLVED (2026-07-28) — `io.github.beltian` via the beltian GitHub account
> (auto-verified on Central sign-in); groupId `io.github.beltian.pjacoco`.
