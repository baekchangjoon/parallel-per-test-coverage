# Samples — getting started

Two complete, runnable consumers that produce one vanilla-JaCoCo `.exec` per test case through the
pjacoco plugin + testkit. Copy either into your project as a starting point.

Both resolve the pjacoco artifacts from your **local Maven repo**, so publish them once first (no
credentials needed):

```bash
# from the repo root
./gradlew :agent:publishToMavenLocal \
  :testkit-core:publishToMavenLocal :testkit-junit5:publishToMavenLocal \
  :testkit-junit4:publishToMavenLocal :testkit-restassured:publishToMavenLocal \
  :gradle-plugin:publishToMavenLocal
```

## `gradle-sample` — Gradle + JUnit 5 (REST Assured, parallel) + JUnit 4

```bash
./gradlew -p samples/gradle-sample test
ls samples/gradle-sample/build/pjacoco/      # one <Class#method>.exec (+ .json) per test
```

What it shows:
- `build.gradle.kts`: apply `id("io.pjacoco.gradle")`, set `pjacoco { includes; attachTo = ["test"] }`,
  add the `pjacoco-testkit-*` dependencies — the plugin injects the agent + `-Dpjacoco.control-url`.
- `OwnerRestAssuredIT` (JUnit 5): `@ExtendWith(PjacocoExtension)` + `PjacocoRestAssured.enable()`; two
  tests run **in parallel** against the same server but hit **different SUT classes**, so each
  `.exec` records only its own class (`classCount=1`) — proving per-test isolation.
- `AlphaJUnit4Test` (JUnit 4 via the Vintage engine): `@Rule PjacocoRule`.

## `maven-sample` — Maven + JUnit 5

```bash
# install the maven plugin once (it resolves io.pjacoco:pjacoco-agent from mavenLocal)
mvn -f maven-plugin/pom.xml install
mvn -f samples/maven-sample/pom.xml test
ls samples/maven-sample/target/pjacoco/      # T1.exec (+ .json)
```

What it shows:
- `pom.xml`: the `pjacoco-maven-plugin` `prepare-agent` goal sets `pjacoco.argLine`; surefire references
  `${pjacoco.argLine}` so the forked test JVM gets the agent + control-url.
- `CalcCoverageTest`: opens a boundary with `Pjacoco.start/stop` and tags the request with the baggage
  header.

## Reporting

The `.exec` files are byte-compatible with vanilla JaCoCo, so report them with the standard CLI:

```bash
java -jar jacococli.jar report build/pjacoco/<Class#method>.exec \
  --classfiles build/classes/java/main --sourcefiles src/main/java --html out/
```
