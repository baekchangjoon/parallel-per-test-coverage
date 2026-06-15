# Publishing pjacoco artifacts

The build produces these artifacts (single lockstep SemVer; bump with `-PreleaseVersion=x.y.z`):

| Artifact | Coordinate | Built by | Notes |
|---|---|---|---|
| Agent (`-javaagent`) | `io.pjacoco:pjacoco-agent` | Gradle (`:agent`) | the **shaded** jar (relocated jacoco-core + byte-buddy) |
| Testkit core | `io.pjacoco:pjacoco-testkit` | Gradle | zero-dep, Java 8 |
| Testkit JUnit 5 | `io.pjacoco:pjacoco-testkit-junit5` | Gradle | `PjacocoExtension` |
| Testkit JUnit 4 | `io.pjacoco:pjacoco-testkit-junit4` | Gradle | `PjacocoRule` |
| Testkit REST Assured | `io.pjacoco:pjacoco-testkit-restassured` | Gradle | baggage filter |
| Gradle plugin | `io.pjacoco.gradle` (plugin id) | Gradle (`:gradle-plugin`) | Gradle Plugin Portal |
| Maven plugin | `io.pjacoco:pjacoco-maven-plugin` | Maven (`maven-plugin/`) | `prepare-agent` goal |

## Local validation (no credentials — this is what CI runs on every PR)

```bash
# 1) Libraries + agent (shaded) to your local Maven repo
./gradlew :agent:publishToMavenLocal \
  :testkit-core:publishToMavenLocal :testkit-junit5:publishToMavenLocal \
  :testkit-junit4:publishToMavenLocal :testkit-restassured:publishToMavenLocal

# 2) Maven plugin (resolves io.pjacoco:pjacoco-agent from step 1)
mvn -f maven-plugin/pom.xml install

# 3) End-to-end consumers
mvn -f samples/maven-sample/pom.xml test     # AC3: produces target/pjacoco/T1.exec
./gradlew :gradle-plugin:test                # AC2: TestKit consumer produces build/pjacoco/T1.exec
```

The POM metadata (MIT license, scm, developers) and GPG signing are already configured; signing is
**gated** — it activates only when `SIGNING_KEY` is present, so local builds without keys still work.

## Public release (credentials-gated)

These steps run from the `release` workflow and only execute when the corresponding secrets exist.

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

> Namespace: confirm ownership of `io.pjacoco` on Central; otherwise fall back to
> `io.github.baekchangjoon`. This decision is only needed before the first public publish.
