# Design: pjacoco distribution & ergonomics (testkit + Gradle/Maven plugins)

Status: draft for review
Date: 2026-06-16
Branch: `worktree-pjacoco-ergonomics`
Author: baekchangjoon (+ Claude)

## 1. Problem & goal

Today a new user must (a) download/build the `-javaagent` jar by hand, (b) hand-write the
`-javaagent:...=opts` string onto the app-under-test's launch command, and (c) hand-write the
test-side glue that opens/closes a per-test coverage boundary and propagates `test.id` as an
OpenTelemetry baggage header (the petclinic `PerTestCoverageExtension`).

Goal: make adoption "add a dependency / apply a plugin" wherever the build controls the relevant
JVM, by publishing reusable artifacts:

1. a **test-side helper library** (framework-neutral core + JUnit 5 / JUnit 4 / REST Assured adapters),
2. a **Gradle plugin** that resolves the agent and wires `-javaagent` (convention + escape hatch),
3. a **Maven plugin** mirroring jacoco's `prepare-agent` ergonomics.

Distribution target: build & validate locally first (`publishToMavenLocal` / `includedBuild`); design
the publication metadata for **Maven Central** (libraries) and **Gradle Plugin Portal** (the Gradle
plugin) so the public publish is a credentials-gated follow-up.

## 2. Background fact established during design (important)

An earlier assumption — that per-test routing only works for Java 8 bytecode because
`CondyProbeArrayStrategy` (used for class-file major ≥ 55, i.e. Java 11+) lacks the
`className`/`classId` fields the routing hook reflects — was **empirically refuted** and the fix is
already committed (`5640bb5`):

- All three jacoco strategies (`ClassField`, `Condy`, `Local`) declare `className`+`classId` with the
  same names, so `InsertProbeAdvice` works across bytecode versions.
- Proven in-process (`ProbeRoutingCondyIT`, major-55 fixture) and end-to-end through the real shaded
  agent (`CondyE2E` / `e2eCondyTest`).

Consequence for this design: **there is no Java-8-bytecode limitation.** The README's Spring
Boot 4 (Java 17) example is valid, and the E2E matrix below varies the SUT bytecode level freely.

## 3. Non-goals

- Auto-attaching the agent to JVMs the build does not launch (remote/pre-built containers, prod-like
  deploys). Those remain "add the flag to that process's launch command" — the plugin only exposes a
  ready-made arg string for them.
- Server start/stop lifecycle management by the plugin (rejected during brainstorming).
- Additional HTTP-client baggage adapters beyond REST Assured (OkHttp/Apache/JDK HttpClient) — future
  work; the framework-neutral core hook covers them manually in the meantime.
- Changing the agent's coverage mechanism or output format.

## 4. Architecture — monorepo multi-module

Convert the repo into a Gradle multi-module build. New module layout (existing agent code becomes the
`agent` module; behavior unchanged):

```
parallel-per-test-coverage/                 (root: version, publishing, release wiring)
├─ agent/                 io.pjacoco:pjacoco-agent              (the -javaagent shadowJar; today's code)
├─ testkit-core/          io.pjacoco:pjacoco-testkit            (deps: none; the Pjacoco control API)
├─ testkit-junit5/        io.pjacoco:pjacoco-testkit-junit5     (PjacocoExtension)
├─ testkit-junit4/        io.pjacoco:pjacoco-testkit-junit4     (PjacocoRule)
├─ testkit-restassured/   io.pjacoco:pjacoco-testkit-restassured(baggage Filter)
├─ gradle-plugin/         id io.pjacoco.gradle (pjacoco-gradle-plugin)
├─ maven-plugin/          io.pjacoco:pjacoco-maven-plugin       (goal: prepare-agent)
└─ samples/
   ├─ gradle-sample/      Gradle E2E consumer (TestKit + includedBuild)
   └─ maven-sample/       Maven E2E consumer (maven-invoker + mavenLocal)
```

Rationale: single SemVer, lockstep releases, one release workflow, and E2E that wires every artifact
together in one build. Rejected: separate repos (version-matrix coordination, 4× CI, hard cross-repo
E2E).

**Artifact name mapping (single source of truth):**

| Maven artifactId (= Gradle module) | shadowJar `archiveFileName` | GitHub Release asset |
|---|---|---|
| `io.pjacoco:pjacoco-agent` | `jacocoagent-parallel.jar` (unchanged) | `jacocoagent-parallel-<version>.jar` (+ `.sha256`) |

The artifactId is new (Maven coordinate for plugins/users to resolve); the `-javaagent` filename and
the existing release asset name are unchanged. Plugins resolve `io.pjacoco:pjacoco-agent` and use the
resolved file path directly (filename-agnostic).

**Migration note + step order (riskiest step — do first, gate on AC5):**
moving `agent` into a submodule must preserve the existing source sets (`integrationTest`,
`e2eJakarta`, `condyFixture`), the test tasks (`test`, `integrationTest`, `e2eTest`, `e2eJakartaTest`,
`e2eCondyTest`), the shadowJar name `jacocoagent-parallel.jar`, the relocation rules, and `release.yml`.
Ordered steps:
1. Create `agent/`, move `src/` + `build.gradle.kts` there; add `include("agent")` to `settings.gradle.kts`.
2. Update `release.yml` (the agent build task becomes `:agent:shadowJar`; asset path under `agent/build/libs/`).
3. Update `ci.yml` to run `:agent:test :agent:integrationTest :agent:e2eTest :agent:e2eJakartaTest :agent:e2eCondyTest`.
4. Gate: AC5 (full agent suite) green before adding any other module.

**`spike/`** stays a standalone exploratory build (its own `settings.gradle.kts`); it is NOT folded
into the root multi-module build and remains excluded from CI, as today. Noted so the migration scope
is explicit.

## 5. Component design

### 5.1 `pjacoco-testkit` (core, zero deps; `junit` not required)

```java
package io.pjacoco.testkit;
public final class Pjacoco {
    public static boolean enabled();             // -Dpjacoco.control-url present & non-blank
    public static String  controlUrl();          // resolved control endpoint base, or null
    public static String  currentTestId();       // active test id on this thread (InheritableThreadLocal), or null
    public static String  baggageHeaderValue();  // "test.id=<id>" while a test is active, else null
    public static void    start(String testId, /*@Nullable*/ String shardId);  // POST /__coverage__/test/start  (best-effort)
    public static void    stop(String testId, String result);                  // POST /__coverage__/test/stop   (best-effort)
    public static void    setCurrentTestId(String id);            // for adapters / manual wiring
    public static void    clearCurrentTestId();
}
```

- **Java 8 compatible** (matches the agent's `VERSION_1_8` floor; test suites may run on JDK 8). The
  control-plane HTTP calls therefore use `java.net.HttpURLConnection` (JDK 1.1+), **not**
  `java.net.http.HttpClient` (which is Java 11+). No third-party deps.
- Config via system property `pjacoco.control-url` (e.g. `http://127.0.0.1:6310`). Absent → `enabled()`
  is false and every method is a no-op (a suite with the testkit on the classpath behaves normally
  when routing is off).
- `test.id` default format `ClassName#method` is produced by the framework adapters, not core.
- **URL-encoding (correctness):** `start`/`stop` must `URLEncoder.encode(..., "UTF-8")` `testId`,
  `shardId`, and `result` before placing them in the control-URL query — otherwise the `#` in
  `ClassName#method` is parsed as a URL fragment and the method name is dropped (the agent's
  `ControlEndpoint` URL-decodes query params, so this round-trips). `baggageHeaderValue()` keeps the
  raw `test.id=ClassName#method` (a `#` is legal in a header value).
- `shardId` is nullable — pass `null` for non-sharded suites.
- All network calls best-effort: failures are swallowed (coverage routing must never fail a test).
- `InheritableThreadLocal` so threads a test spawns inherit the active id.

### 5.2 `pjacoco-testkit-junit5` — `PjacocoExtension`

`BeforeEachCallback`/`AfterEachCallback`. `beforeEach`: compute `testId = ClassName#method`,
`Pjacoco.setCurrentTestId(id)`, `Pjacoco.start(id, shardId?)`. `afterEach`:
`Pjacoco.stop(id, passed|failed)` then `clearCurrentTestId()`. Depends `junit-jupiter-api` (compileOnly
+ test). Usable via `@ExtendWith(PjacocoExtension.class)` or auto-registration
(`/META-INF/services/...` + `junit.jupiter.extensions.autodetection.enabled`).

### 5.3 `pjacoco-testkit-junit4` — `PjacocoRule`

A `TestRule` implemented over `TestWatcher` semantics: `starting(Description)` → start + set id;
`finished(Description)` → stop + clear; `failed(...)`/`succeeded(...)` capture the result. `testId =
Description.getClassName()#getMethodName()`. Depends `junit:junit:4.13.2` (compileOnly + test). Used as
`@Rule public final PjacocoRule pjacoco = new PjacocoRule();`.

### 5.4 `pjacoco-testkit-restassured` — baggage filter

```java
public final class PjacocoRestAssured {
    public static io.restassured.filter.Filter baggageFilter(); // stamps `baggage: test.id=<id>` when active
    public static void enable();                                 // RestAssured.filters(baggageFilter())
}
```

Reads `Pjacoco.currentTestId()` / `baggageHeaderValue()`; depends `rest-assured` (compileOnly + test).
The petclinic `PerTestCoverageExtension` is replaced by `@ExtendWith(PjacocoExtension.class)` +
`PjacocoRestAssured.enable()`.

### 5.5 Gradle plugin — `io.pjacoco.gradle` (convention + escape hatch)

```kotlin
plugins { id("io.pjacoco.gradle") version "1.1.0" }
pjacoco {
    agentVersion.set("1.1.0")                       // default: the plugin's own version
    port.set(6310)
    includes.set(listOf("com.example.*"))
    excludes.set(emptyList())
    destfile.set(layout.buildDirectory.dir("pjacoco"))
    attachTo.set(tasks.named("integrationTest"))    // optional: auto-inject into this JVM fork
}
```

- Resolves `io.pjacoco:pjacoco-agent:<agentVersion>` (the **shaded** jar — see §7) via a dedicated
  `pjacocoAgent` configuration.
- Composes `-javaagent:<resolved>=destfile=...,port=...,includes=...,excludes=...`. (Note: pjacoco's
  `destfile` is re-interpreted by the agent as an output **directory**, unlike standard jacoco's
  single-file `destfile`; the plugin DSL field is `destfile` for jacoco-CLI familiarity but documents
  the directory meaning.)
- Exposes it two ways: (1) extension property `pjacoco.agentJvmArg` (a `Provider<String>`) for the
  user to wire onto a separately-launched server (`bootRun`, Testcontainers `JAVA_TOOL_OPTIONS`,
  `JavaExec`); (2) if `attachTo` is set, **appends** it to that task's `jvmArgs` (append, not prepend,
  so it sits after any user args; ordering is not significant for `-javaagent`).
- **Control-url propagation (connection contract — required for zero-config):** whenever the plugin
  attaches the agent to a JVM via `attachTo`, it ALSO sets `-Dpjacoco.control-url=http://127.0.0.1:<port>`
  on that task, because the testkit (running in the test JVM) activates only on that property. For the
  in-JVM/embedded case the test JVM and the SUT JVM are the same task, so one `attachTo` wires both. For
  a separately-launched server, the user points `attachTo` at the **test** task (testkit side → gets
  `pjacoco.control-url`) and wires `agentJvmArg` onto the **server** launch (SUT side → gets the agent).
- `attachTo` type: a `Property<TaskProvider<*>>` accepting any `JavaForkOptions` task (`Test`/`JavaExec`).
  Unset → no auto-injection (safe default); `agentJvmArg`/`pjacoco.controlUrlArg` are always available.
- Baseline: plugin targets **Gradle ≥ 7.6** and runs on **Java 11+** (Gradle's JVM); the agent jar it
  resolves stays Java 8 compatible. Documents `JAVA_TOOL_OPTIONS` for containerized SUTs.

### 5.6 Maven plugin — `pjacoco-maven-plugin` (mirrors jacoco)

```xml
<plugin>
  <groupId>io.pjacoco</groupId><artifactId>pjacoco-maven-plugin</artifactId><version>1.1.0</version>
  <executions><execution><goals><goal>prepare-agent</goal></goals></execution></executions>
  <configuration>
    <port>6310</port>
    <propertyName>pjacoco.argLine</propertyName>
    <destfile>${project.build.directory}/pjacoco</destfile>   <!-- default: under target/, not project root -->
    <includes><include>com.example.*</include></includes>
  </configuration>
</plugin>
```

- `prepare-agent` (default phase `initialize`): resolve the **shaded** agent artifact, compose
  `-javaagent:...`, set the `pjacoco.argLine` project property. The user references `${pjacoco.argLine}`
  from surefire/failsafe `argLine` (forked test JVM) or from the server-launch plugin's `jvmArguments`
  (separate process) — exactly the jacoco model. No auto server launch.
- **`destfile` defaults to `${project.build.directory}/pjacoco`** so per-test `.exec` land under
  `target/`, honoring Maven convention (the agent's bare default is `coverage` relative to CWD).
- **Control-url propagation:** `prepare-agent` also exposes `-Dpjacoco.control-url=http://127.0.0.1:<port>`
  — included in `pjacoco.argLine` for the forked test JVM (so the testkit activates), and documented for
  the surefire/failsafe `systemPropertyVariables` route when the SUT runs in a separate JVM.

## 6. UX after this work

- In-JVM / embedded-server suites (e.g. `@SpringBootTest(RANDOM_PORT)`): apply plugin with
  `attachTo = test task` + add the testkit dependency + extension → per-test `.exec` with no manual
  flags.
- Separate-process server launched by the build (`bootRun`, `JavaExec`, exec/spring-boot:run): plugin
  exposes the arg; wire one line into the launcher. Test side via the testkit.
- Remote/pre-built container: copy the exposed arg string into that process's launch / `JAVA_TOOL_OPTIONS`.

## 7. Versioning, compatibility, publishing

- Root single SemVer; this work ships as **1.1.0** (additive). `agent` keeps producing
  `jacocoagent-parallel.jar` and the existing release artifacts.
- **Agent publication = the shaded jar.** `pjacoco-agent` must publish the relocated `shadowJar`
  (with the `io.pjacoco.shaded.*` relocations) as its **primary** Maven artifact — NOT the default
  unshaded `jar` — so consumers/plugins resolving `io.pjacoco:pjacoco-agent` get the self-contained
  `-javaagent`. (Shadow's `component.shadow` / disabling the plain `jar` publication.)
- Libraries: published via the **`com.vanniktech.maven.publish`** plugin (handles POM metadata —
  name, description, MIT, scm, developers — signing, and the **Sonatype Central Portal** upload API at
  `central.sonatype.com`; the legacy OSSRH/nexus-staging JIRA flow is NOT used). Local validation via
  `publishToMavenLocal`. Required secrets (exact names, matched to the workflow):
  `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` (Central Portal token), `SIGNING_KEY` (ASCII-armored
  GPG private key), `SIGNING_PASSWORD`.
- Gradle plugin: `java-gradle-plugin` + `com.gradle.plugin-publish`; local via `includedBuild`; public
  via Gradle Plugin Portal gated on `GRADLE_PUBLISH_KEY` / `GRADLE_PUBLISH_SECRET`.
- Namespace: confirm ownership of `io.pjacoco` on Central, else fall back to `io.github.baekchangjoon`
  (decision needed before public publish; does not block local-first work).
- `release.yml`: extend to build/validate all modules; public publish steps guarded by `if:` secret
  presence (no silent publish). `ci.yml`: run the new modules' tests + the sample E2Es (AC1–AC4) so
  they are validated on every PR.

## 8. E2E / acceptance tests (definition of done)

Outer-loop acceptance tests authored first (red), driven green by inner-loop unit TDD per module.

- **AC1 — testkit (JUnit 5) + REST Assured:** a sample app + parallel REST Assured black-box suite,
  agent attached, produces one vanilla-JaCoCo `.exec` per test case with branch-exclusive (no
  cross-contamination) coverage.
- **AC2 — Gradle plugin:** `samples/gradle-sample` consumed via `includedBuild` + Gradle TestKit;
  applying the plugin (with `attachTo`) auto-attaches the agent and yields per-test `.exec`. Asserts
  the plugin resolves the agent, composes the arg, injects it, and `.exec` files appear & carry real
  probes.
- **AC3 — Maven plugin:** `samples/maven-sample` run via maven-invoker against mavenLocal-installed
  plugin + agent; `prepare-agent` sets `argLine`, the forked JVM gets the agent, per-test `.exec` is
  produced.
- **AC4 — testkit (JUnit 4):** a JUnit 4 suite in `samples/gradle-sample` (its own test task using the
  **JUnit Vintage** engine, or a separate `junit4Test` source set so it does not share a JVM with the
  Jupiter tests) uses `@Rule PjacocoRule` and produces per-test `.exec`. The agent is attached the same
  way as AC2 (plugin `attachTo` that task).
- **AC6 — separate-process path (the "(b)" UX):** `samples/gradle-sample` launches the SUT in a
  separate JVM via `JavaExec`, injecting the plugin's `agentJvmArg`, with the test task getting only
  `-Dpjacoco.control-url`. Assert the control endpoint is reachable and per-test `.exec` are produced —
  so the documented separate-process UX is actually CI-verified, not just asserted in prose.
- **AC5 — agent migration regression:** after the multi-module move, the full existing agent suite
  (`:agent:test :agent:integrationTest :agent:e2eTest :agent:e2eJakartaTest :agent:e2eCondyTest`)
  stays green.

**Feasibility:** for CI determinism, the in-build plugin samples (AC2/AC3/AC4) use an **embedded
server in the test JVM + parallel JUnit execution** as the primary E2E (the plugin's fork
auto-injection applies, exercising baggage routing + control plane end-to-end). AC6 adds the
genuine separate-JVM path via `JavaExec`. The full multi-process/container path remains documented
(not auto-tested) and the agent module's existing out-of-process e2e (AC5) is the lower-level proof.

### 8.1 CI matrix (requested)

A GitHub Actions matrix for the E2E/acceptance jobs (precedent: `jacoco-canary.yml`):

- **JDK (runtime):** `8, 11, 17, 21` (LTS). Build/Gradle itself runs on JDK 17; the SUT + testkit run
  under the matrix JDK via toolchains where needed.
- **JUnit:** `5 (Jupiter latest)` and `4 (4.13.2)`.
- **SUT bytecode level:** the condy fixture's level is `-PcondyRelease` (default 11; matrix raises to
  17/21 where that JDK is available) so both `ClassField` (Java 8) and `Condy` (Java 11+) strategies
  are exercised across the matrix. (No Java-8-bytecode limitation — see §2.)
- **Matrix partition:** because the testkit is Java 8 compatible (§5.1), the JDK 8 row runs AC1/AC4
  (agent + testkit) as well as AC5 — no AC needs a Java 11+ test JVM. The `-PcondyRelease=17/21` cells
  only run where the toolchain JDK ≥ that level is present.
- Infeasible cells are skipped explicitly with a logged reason (e.g. `condyRelease=17` needs JDK ≥ 17),
  never silently dropped.

## 9. Risks / open questions

- Multi-module migration is the riskiest step (build wiring). Mitigation: do it first, gate on AC5.
- Maven Central namespace ownership (`io.pjacoco` vs `io.github.baekchangjoon`) + GPG must be sorted
  before public publish (does not block local-first development).
- Gradle TestKit + `includedBuild` interaction with the shadowed agent must be validated early (AC2).
- REST Assured is the only baggage adapter in MVP; document the manual hook for other clients.

## 10. Definition of done

AC1–AC6 green; each module has unit + the relevant integration/E2E tests; the CI matrix (§8.1) runs;
`publishToMavenLocal` produces all artifacts (agent published as the **shaded** jar) and the samples
consume them; the testkit is Java 8 compatible; both plugins propagate `pjacoco.control-url`; docs
updated (README KO/EN "Download / Use as a dependency", per-module READMEs, required-secrets list); the
public-publish steps exist but are credentials-gated.

## 11. Three-model review log (2026-06-16)

Reviewed by Claude Sonnet (design-doc-reviewer), Google Gemini 3.5 Flash (High), and OpenAI GPT-5.2.
Incorporated (consensus / valid): testkit must be Java 8 compatible → use `HttpURLConnection` not
`java.net.http.HttpClient` (all 3); URL-encode `testId`/`shardId`/`result` in control calls (Gemini);
both plugins must propagate `-Dpjacoco.control-url` to the test JVM (Gemini, GPT); publish the **shaded**
jar as the primary `pjacoco-agent` artifact (Gemini); Maven `destfile` default under `target/` (Gemini);
concrete migration step-order + `settings.gradle` include + `:agent:shadowJar` (Sonnet); `spike/`
handling (GPT); AC4 JUnit4 sample location + Vintage engine (Sonnet); added AC6 separate-process
verification (GPT); pinned publish channel (Central Portal via `com.vanniktech.maven.publish`) + exact
secret names (Sonnet, GPT); plugin Gradle/Java baseline (GPT); artifact coordinate/filename table (GPT);
`destfile` naming-collision note + `shardId` nullable (Sonnet); fixed "four e2e tasks" wording (GPT).
No findings rejected — all were located and verifiable.
