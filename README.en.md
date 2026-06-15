# parallel-per-test-coverage

> 🇰🇷 [한국어](README.md) · 🇬🇧 **English** (this page)

<p align="center">
  <a href="https://github.com/baekchangjoon/parallel-per-test-coverage/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/baekchangjoon/parallel-per-test-coverage/actions/workflows/ci.yml/badge.svg"></a>
  <a href="https://github.com/baekchangjoon/parallel-per-test-coverage/actions/workflows/jacoco-canary.yml"><img alt="JaCoCo version canary" src="https://github.com/baekchangjoon/parallel-per-test-coverage/actions/workflows/jacoco-canary.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/License-MIT-yellow.svg"></a>
  <img alt="Java" src="https://img.shields.io/badge/Java-8%2B-orange">
  <img alt="JaCoCo" src="https://img.shields.io/badge/JaCoCo-0.8.11%E2%80%930.8.13-brightgreen">
  <a href="README.md"><img alt="Docs" src="https://img.shields.io/badge/Docs-KO%20%7C%20EN-green"></a>
</p>

A Java agent that **separates the code coverage each test produces — with zero cross-contamination —
even when tests run in parallel and interleave requests against the same server**. The output is
**vanilla-JaCoCo-compatible `.exec`**, one file per `testId`, so the existing JaCoCo / Sonar /
`jacococli` ecosystem works unchanged.

## Why

JaCoCo was **not designed for per-test separation.** Runtime data accumulates in a single global
`ExecutionDataStore` (keyed by classId); `sessionId` is just a *label* stamped at dump time — there
is no per-session partitioning. Splitting parallel tests by `sessionId` is therefore not a misuse but
**structurally impossible**. The probe (`$jacocoData` boolean[]) is an intentional "unsynchronized
write, minimal overhead" design with no room for a test/request context dimension.

This project solves it **from the outside, without forking JaCoCo** — borrowing and validating the
pattern Datadog's `dd-trace-java` proved: hook the probe-insertion site at instrumentation time and
emit an **additive** call that mirrors each probe into a per-test store. (Deep dive:
[`docs/research/m4-mechanism/`](docs/research/m4-mechanism/).)

## How it works

A **single, self-contained agent** attaches to the target app JVM via `-javaagent`. The `testId`
arrives on each inbound request as an **OpenTelemetry Baggage** header (`baggage: test.id=...`), and an
explicit **control endpoint** defines the flush boundary.

```
Test harness                          Target app JVM  (-javaagent:jacocoagent-parallel.jar)
   │                                   ┌──────────────────────────────────────────────┐
   │ setup:                            │ premain: embed jacoco-core + ByteBuddy advice  │
   │   POST /__coverage__/test/start ──┼─► TestStoreRegistry: create the T1 store        │
   │       ?testId=T1                  │                                                │
   │ requests (parallel):              │  ServletAdvice: baggage → CoverageContext=T1   │
   │   GET /api/...                    │       (per-request ThreadLocal store)          │
   │   baggage: test.id=T1          ───┼─►  instrumented probes fire                     │
   │                                   │     → CoverageBridge.recordCoverage(...)        │
   │                                   │     → recorded into T1 (global array untouched) │
   │ teardown:                         │                                                │
   │   POST /__coverage__/test/stop ───┼─► flush → coverage/T1.exec  +  T1.json          │
   │       ?testId=T1                  └──────────────────────────────────────────────┘
```

- **Instrumentation**: jacoco-core's `Instrumenter` directly — identical `classId` (CRC64) + probe
  scheme to vanilla.
- **Routing**: right after jacoco's own probe, an additive `CoverageBridge.recordCoverage(Class,
  classId, probeId)` mirrors the hit into the active thread's `testId` store. JaCoCo's global array is
  left intact — if our logic dies, normal behavior survives (additive).
- **Isolation**: the context is a ThreadLocal `TestStore` reference → zero cross-contamination across
  parallel workers.
- **Output**: one `.exec` per testId + a `.json` sidecar + a startup `manifest.json` header.

> **Keystone check**: in a single instrument+run, the per-test probe array equals jacoco's global array
> **byte-for-byte** (`GoldenEquivalenceIT`); concurrent isolation is proven too (`spike/`, e2e).

## Download

Grab a pre-built agent jar from the [**Releases**](../../releases/latest) page instead of building it yourself.

```bash
# Download jacocoagent-parallel-<version>.jar and its .sha256 checksum from the Releases page.
sha256sum -c jacocoagent-parallel-<version>.jar.sha256   # verify integrity
```

Releases are published by the `release` GitHub Actions workflow (manual run): Actions → "release" → "Run workflow".

## Quick start

> 💡 **The easiest path is [Use as a dependency / plugin (recommended)](#use-as-a-dependency--plugin-recommended)** —
> the plugin attaches the agent for you and the testkit handles the boundary/propagation. **Complete,
> copy-and-run examples**: [`samples/gradle-sample`](samples/gradle-sample) ·
> [`samples/maven-sample`](samples/maven-sample). The steps below are the **low-level path** of driving
> the agent by hand.

```bash
# 1) Build the agent jar (JDK 17+ to run Gradle; the artifact targets Java 8)
#    Or grab a pre-built jar from "Download" above.
JAVA_HOME=<jdk17+> ./gradlew :agent:shadowJar
#   → agent/build/libs/jacocoagent-parallel.jar

# 2) Attach to the target app
java -javaagent:jacocoagent-parallel.jar=destfile=coverage,port=6310,includes=com.example.* \
     -jar your-app.jar
```

From the test harness:

```bash
curl -XPOST 'http://127.0.0.1:6310/__coverage__/test/start?testId=T1&shardId=s1'
curl -H 'baggage: test.id=T1' 'http://app/api/...'          # propagate testId per request (OTel Baggage)
curl -XPOST 'http://127.0.0.1:6310/__coverage__/test/stop?testId=T1&result=passed'
java -jar jacococli.jar report coverage/T1.exec --classfiles app/classes --html out/T1
```

### Agent options

| option | meaning | default |
|---|---|---|
| `destfile` | output **directory** (many per-test files) | `coverage` |
| `includes`/`excludes` | instrumentation scope (jacoco `WildcardMatcher`) | `*` / `` |
| `port`/`address` | control endpoint binding | `6310` / `127.0.0.1` (loopback) |
| `lenient` | auto-register unknown testIds (default strict: not recorded) | `false` |
| `commitSha` | written to the manifest header (or env `PJACOCO_COMMIT`) | — |

## Use as a dependency / plugin (recommended)

Instead of downloading the jar and hand-wiring `-javaagent`, use the **build plugin + testkit**. The
plugin resolves the agent and injects `-javaagent`; the testkit owns the per-test boundary (start/stop)
and `baggage: test.id=...` propagation.

**Gradle** (`build.gradle.kts`):

```kotlin
plugins { id("io.pjacoco.gradle") version "1.1.0" }

pjacoco {
    includes.set(listOf("com.example.*"))
    attachTo.set(listOf("integrationTest"))   // inject the agent + control-url into this test JVM
}
dependencies {
    testImplementation("io.pjacoco:pjacoco-testkit-junit5:1.1.0")
    testImplementation("io.pjacoco:pjacoco-testkit-restassured:1.1.0")
}
```

```java
@ExtendWith(io.pjacoco.testkit.junit5.PjacocoExtension.class)   // per-test start/stop + test.id
class OwnerBlackBoxIT {
    @BeforeAll static void enable() { io.pjacoco.testkit.restassured.PjacocoRestAssured.enable(); } // baggage
    // ... parallel REST Assured black-box tests ...
}
```

> For a separately-launched server, point `attachTo` at the test task (testkit side gets the
> control-url) and wire the exposed `pjacoco.agentJvmArg` property onto the server launch. For JUnit 4
> use `@Rule PjacocoRule` from `io.pjacoco:pjacoco-testkit-junit4`.

**Maven** (`pom.xml`): `prepare-agent` sets `pjacoco.argLine`, which surefire references.

```xml
<plugin>
  <groupId>io.pjacoco</groupId><artifactId>pjacoco-maven-plugin</artifactId><version>1.1.0</version>
  <executions><execution><goals><goal>prepare-agent</goal></goals></execution></executions>
  <configuration><includes><include>com.example.*</include></includes></configuration>
</plugin>
<plugin>
  <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
  <configuration><argLine>${pjacoco.argLine}</argLine></configuration>
</plugin>
```

> Coordinates: agent `io.pjacoco:pjacoco-agent`, testkit `io.pjacoco:pjacoco-testkit[-junit5|-junit4|-restassured]`,
> Gradle plugin id `io.pjacoco.gradle`, Maven plugin `io.pjacoco:pjacoco-maven-plugin`. Public release
> (Maven Central / Gradle Plugin Portal) is pending; see [`docs/PUBLISHING.md`](docs/PUBLISHING.md) for
> local validation today.

## Output

```
coverage/
  T1.exec        # vanilla JaCoCo format (jacococli/Sonar/TIA as-is)
  T1.json        # sidecar: testId, result, classCount, retryCount, shardId, status, durationMs, …
  manifest.json  # global header: schemaVersion, jacocoVersion, commitSha, precision (once, at premain)
```

## Example: per-test coverage of a parallel black-box suite — spring-petclinic (Spring Boot 4 / jakarta)

A full walkthrough of attaching this agent to
[spring-petclinic](https://github.com/baekchangjoon/spring-petclinic)'s `@Tag("blackbox")` out-of-process
REST Assured suite (run in parallel) to get **one `.exec` per test case**. Assumes both repos are cloned
as siblings under one parent directory.

```bash
# Run from the directory that contains both clones (siblings).

# 1) Build the coverage agent (JDK 17+ to run Gradle; the jar itself targets Java 8).
( cd parallel-per-test-coverage && JAVA_HOME=<jdk17+> ./gradlew :agent:shadowJar )
#   → parallel-per-test-coverage/agent/build/libs/jacocoagent-parallel.jar

# 2) Build spring-petclinic from source, then start it with the agent attached.
#    includes = the app's packages; the Spring Boot 4 jakarta.servlet / Tomcat 11 stack is supported.
( cd spring-petclinic && ./gradlew bootJar )
java -javaagent:"$PWD/parallel-per-test-coverage/agent/build/libs/jacocoagent-parallel.jar=destfile=/tmp/petclinic-coverage,port=6310,includes=org.springframework.samples.petclinic.*" \
     -jar "$PWD/spring-petclinic/build/libs/spring-petclinic-4.0.0-SNAPSHOT.jar"

# 3) In another shell, run the parallel black-box suite with per-test coverage routing on.
#    It activates only because -Dpjacoco.control-url is set (otherwise the suite runs unchanged).
( cd spring-petclinic && ./gradlew blackboxTest \
    -Dpetclinic.base-url=http://localhost:8080 \
    -Dpjacoco.control-url=http://127.0.0.1:6310 )
#   → /tmp/petclinic-coverage/<Class#method>.exec   (one vanilla-JaCoCo .exec per test case)

# 4) Report any single test's coverage with standard jacoco tooling.
#    jacococli = org.jacoco:org.jacoco.cli:0.8.12:nodeps (download from Maven Central).
java -jar jacococli.jar report "/tmp/petclinic-coverage/OwnerApiBlackBoxIT#getOwnerById.exec" \
    --classfiles spring-petclinic/build/classes/java/main \
    --sourcefiles spring-petclinic/src/main/java \
    --html /tmp/cov-OwnerGetById
```

> The app-side `test.id` propagation is done by petclinic's JUnit 5 extension `PerTestCoverageExtension`:
> it calls the control endpoint around each test and stamps every request with a
> `baggage: test.id=<Class#method>` header. Threads spawned by `ConcurrencyBlackBoxIT` inherit the same
> `test.id` via an `InheritableThreadLocal`.

## Testing & verification

This repo verifies itself rigorously; CI (`ci.yml`) runs everything on PRs and pushes to main.

| layer | what |
|---|---|
| **unit** (`test`) | per-component in-process tests |
| **in-process integration** (`integrationTest`) | `GoldenEquivalenceIT` (vanilla byte-equivalence), `ProbeRoutingIT` |
| **e2e** (`e2eTest`) | real `-javaagent` + embedded Jetty + HTTP black-box spec acceptance (isolation, sidecar, manifest, strict mode, untagged, retry, concurrency) |
| **mutation** (`scripts/mutation-e2e.sh`) | inject 9 mutants into the agent SUT → measure e2e KILLED/SURVIVED. **9/9 KILLED** (proves the e2e catches real regressions) |
| **version canary** (`jacoco-canary.yml`) | hook compatibility across jacoco **0.8.11/0.8.12/0.8.13** |
| **coverage** | `jacocoTestReport` measures agent self-coverage + CI summary/artifact |

CI runs the agent suite on a **JDK 11/17/21 matrix** (with `-PcondyRelease` to exercise the Condy
path), plus separate jobs for JDK 8 testkit compatibility, `samples/gradle-sample` (AC1 parallel REST
Assured + AC4 JUnit 4), and `samples/maven-sample` (AC3). The testkit/plugin modules each have their
own unit/functional tests.

```bash
# Agent full suite (multi-module: tasks are prefixed with :agent:)
JAVA_HOME=<jdk17+> ./gradlew :agent:test :agent:integrationTest :agent:e2eTest \
                              :agent:e2eJakartaTest :agent:e2eCondyTest :agent:jacocoTestReport
JAVA_HOME=<jdk17+> ./gradlew test            # every module's unit/functional tests (testkit + plugins)
JAVA_HOME=<jdk17+> ./gradlew -p samples/gradle-sample test   # plugin+testkit end-to-end sample
JAVA_HOME=<jdk17+> scripts/mutation-e2e.sh   # mutation campaign
```

## Layout (Gradle multi-module)

```
agent/                  io.pjacoco:pjacoco-agent             # -javaagent (Bootstrap, ProbeInstrumentation,
                                                             #   CoverageBridge, ControlEndpoint, inbound SPI …)
testkit-core/           io.pjacoco:pjacoco-testkit           # testkit core (control API, zero deps, Java 8)
testkit-junit5/         io.pjacoco:pjacoco-testkit-junit5    # PjacocoExtension
testkit-junit4/         io.pjacoco:pjacoco-testkit-junit4    # PjacocoRule
testkit-restassured/    io.pjacoco:pjacoco-testkit-restassured  # baggage filter
gradle-plugin/          id "io.pjacoco.gradle"               # resolve the agent + wire -javaagent
maven-plugin/           io.pjacoco:pjacoco-maven-plugin      # prepare-agent (Maven builds)
samples/                gradle-sample · maven-sample         # complete runnable end-to-end examples
spike/                  # M4 instrumentation-mechanism validation PoC (standalone build)
scripts/mutation-e2e.sh # mutation-campaign harness
docs/                   # design specs · publishing guide · research reports
.github/workflows/      # ci.yml (matrix) · release.yml (one-click release) · jacoco-canary.yml
```

## Scope

**In v1**: synchronous servlet stack, line-level vanilla-equivalent `.exec` per testId, Baggage
routing, control endpoint, failure isolation / memory cap / observability, jacoco option mirroring.

**Phase 2 (non-goals)**: async/thread-pool context propagation, reactive (WebFlux) / gRPC, drain mode,
time-based TTL eviction, JMX, backend upload.

> The end goal is a drop-in replacement for the serial JaCoCo collection layer of a Test Impact
> Analysis pipeline.

## Design docs

- Distribution & ergonomics (testkit + plugins): [`docs/superpowers/specs/2026-06-16-pjacoco-ergonomics-design.md`](docs/superpowers/specs/2026-06-16-pjacoco-ergonomics-design.md)
- Agent design spec (v1): [`docs/superpowers/specs/2026-06-13-parallel-jacoco-design.md`](docs/superpowers/specs/2026-06-13-parallel-jacoco-design.md)
- Publishing guide (release + secrets): [`docs/PUBLISHING.md`](docs/PUBLISHING.md)
- Third-party licenses: [`THIRD-PARTY-LICENSES.md`](THIRD-PARTY-LICENSES.md)

## References

- [Datadog `dd-trace-java`](https://github.com/DataDog/dd-trace-java) (Apache 2.0) — origin of the hooking pattern we borrowed and validated
- [JaCoCo](https://www.jacoco.org/jacoco/) — the embedded coverage engine

## License

The project's own code is [MIT](LICENSE) © 2026 baekchangjoon.

> **Distributed agent jar notice**: the `-javaagent` jar (`io.pjacoco:pjacoco-agent`) **embeds**
> **JaCoCo core (EPL-2.0)** and **Byte Buddy (Apache-2.0)**, relocated under `io.pjacoco.shaded.*`. Each
> component stays under its own license (it is NOT relicensed as MIT) and its original notices
> (`about.html`, `META-INF/NOTICE`, …) are preserved inside the jar. Full details in
> [`THIRD-PARTY-LICENSES.md`](THIRD-PARTY-LICENSES.md). (Datadog `dd-trace-java`: **technique borrowed,
> no code used**.)
