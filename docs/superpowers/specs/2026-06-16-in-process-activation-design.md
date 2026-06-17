# Design: in-process per-test activation (no servlet boundary)

Status: draft for review
Date: 2026-06-16
Branch: `worktree-in-process-activation`

## 1. Problem & goal

Today the **only production** code that sets the agent's per-thread `CoverageContext` is the servlet
inbound advice (`ServletAdvice.java:29`), which fires when a request passes through
`HttpServlet.service(...)` carrying a `baggage: test.id=...` header. (Tests can set it directly — e.g.
`ProbeRoutingIT` — but no shipped activator does so off the servlet path.) A pure in-JVM unit test that
calls the SUT directly (no HTTP/servlet boundary) never activates a context, so its probe hits are
dropped (`CoverageBridge.recordCoverage` returns when `CoverageContext.get() == null`). The exact
symptom depends on whether a boundary was opened: with no boundary opened, **no per-test files are
written**; if a boundary is opened (e.g. via the testkit) but no probes land, the `.exec`/`.json` is
written with `classCount=0`. Either way the failure is **silent**.

Goal: support per-test, **parallel** coverage for **in-process** tests (pure unit tests, MockMvc,
in-JVM integration) where the SUT runs on the test's own thread, **without** requiring a servlet
boundary, while keeping vanilla-JaCoCo `.exec` backward compatibility. The mechanism is already proven
in-repo: `ProbeRoutingIT.firedProbesLandInActiveStore` sets `CoverageContext` directly, calls the SUT
method directly (no servlet), and the probes route to the active store. This spec productizes that.

Two adjacent additions narrow the "use it like jacoco" gap: a **whole-run aggregate mode** (§7a) that
also emits jacoco's single whole-run `.exec`, and **JUnit 5 auto-registration** (§7b) so a unit suite
needs no per-test annotation. (pjacoco's per-test output remains its distinguishing artifact — these
make the *usage* closer to jacoco's, not identical.)

## 2. Non-goals

- Replacing or changing the existing out-of-process / in-JVM **servlet** black-box path (HTTP control +
  baggage + `ServletAdvice`). It stays as-is.
- Async / thread-pool context propagation (a SUT that hands work to a pool thread that does not inherit
  the context). Same phase-2 limitation as today; documented, not solved here.
- Changing the `.exec` output format or the additive probe mechanism.

**Alternative considered — reuse the servlet path for MockMvc.** MockMvc dispatches through
`DispatcherServlet` (a `HttpServlet`), so the existing `ServletAdvice` *does* fire for it — meaning
MockMvc could be served by the existing path if a MockMvc-specific adapter injected the `baggage` header
(only a REST Assured adapter ships today). We still add the in-process path because (a) it is the only
option for **pure** unit tests (no servlet at all), and (b) it gives one uniform, client-agnostic model
(no per-HTTP-client baggage adapter) for everything that runs on the test thread. MockMvc users may use
either path.

## 3. Architecture & components

```
agent/
  io.pjacoco.agent.api.CoverageControl                 # NEW: in-JVM activation API (not relocated; stable FQN)
  io.pjacoco.agent.inbound.junit4.JUnit4InboundActivator  # NEW: ByteBuddy advice on JUnit4 runLeaf (§7c)

testkit-core/
  io.pjacoco.testkit.inprocess.InProcessBridge   # NEW: reflectively calls CoverageControl (best-effort)
testkit-junit5/
  io.pjacoco.testkit.junit5.PjacocoInProcessExtension   # NEW
testkit-junit4/
  io.pjacoco.testkit.junit4.PjacocoInProcessRule        # NEW
samples/
  unit-sample (or a source set in gradle-sample)         # NEW: pure-unit per-test E2E
```

The testkit never compile-depends on the agent: it reaches `CoverageControl` reflectively across the
classloader boundary (the agent is loaded by the system classloader; test code is a child and sees it
via parent delegation). If the class is not loadable (agent not attached, or out-of-process where the
agent lives in a different JVM), the bridge is a no-op.

## 4. Agent API — `io.pjacoco.agent.api.CoverageControl`

A small public class with a **stable, reflectively-invoked contract** (method names and signatures must
not change without a version bump — documented in the class):

```java
package io.pjacoco.agent.api;

public final class CoverageControl {
    public static void bindRegistry(TestStoreRegistry registry);  // called once at premain (wiring)
    public static boolean isReady();                              // registry bound (agent installed)?
    public static void activate(String testId, String shardId);   // registry.start(testId, shardId, null);
                                                                  //   store = registry.active(testId);
                                                                  //   if (store != null) CoverageContext.set(store);
                                                                  //   else log.warn(...)  // start() may have failed
    public static void deactivate(String testId, String result);  // CoverageContext.clear()
                                                                  //   + registry.stop(testId, result)  (flushes .exec)
}
```

- `activate` registers the per-test store and sets the active context **on the calling thread**.
  It **guards the null case**: `registry.active(testId)` is only `null` if `start()` was swallowed by
  the best-effort wrapper; in that case it logs a warning and leaves the context unset (mirrors
  `ServletAdvice.java:29`). In normal operation `start()` registered the store so `active()` returns it
  (works in both strict and auto-register modes).
- **Null-safety:** if `bindRegistry` was never called (registry reference null — agent classes present
  but premain wiring did not run), `isReady()` returns false and `activate`/`deactivate` are no-ops (no
  `NullPointerException` into test code). `deactivate` also applies the empty-store guard (§7b).
- **Thread premise:** `activate`/the test body/`deactivate` must run on the **same thread** (the
  `CoverageContext` ThreadLocal is per-thread). JUnit 5/4 run a test's `beforeEach`/body/`afterEach`
  (and `starting`/`finished`) on one thread per test even under parallel execution, so the premise
  holds; AC-IP1 records the thread id across the three phases and asserts equality as a guard.
- `deactivate` clears the thread context and flushes the per-test `.exec` (+ sidecar json).
- All methods are best-effort and never throw into test code. To avoid the silent-no-op trap, the
  testkit bridge logs **one** warning the first time it cannot reach a ready agent (see §5).
- **Relocation:** the shadowJar relocates only `org.jacoco.*` and `net.bytebuddy.*`
  (`agent/build.gradle.kts`); `io.pjacoco.agent.*` is **not relocated**, so `CoverageControl`'s FQN is
  stable **inside the shaded jar** (it is bundled, just not renamed). A regression guard asserts
  `io/pjacoco/agent/api/CoverageControl.class` is present in the built shaded jar; if relocation rules
  ever broaden, `io.pjacoco.agent.api` must be excluded explicitly.

### Changes to existing files (agent)

- `Bootstrap.premain()` gains one line after the `TestStoreRegistry` is constructed and before
  `ProbeInstrumentation.install` / `ServletInboundActivator.install`:
  `CoverageControl.bindRegistry(registry);`. No other change to the servlet path.

## 5. testkit — `InProcessBridge` + extension/rule

`InProcessBridge` is a **new class in the existing `testkit-core` module** (no new submodule;
`testkit-junit5`/`testkit-junit4` already depend on `testkit-core`). Zero deps, Java 8. It resolves
`CoverageControl` once, trying loaders in order — **system classloader → thread context classloader →
`Class.forName`** — caches the `Method` handles, and exposes `available()` / `activate(testId, shardId)`
/ `deactivate(testId, result)`. On any reflective failure (class absent, agent not ready) it degrades
to a no-op AND logs **one** warning (`[pjacoco] in-process agent not reachable; per-test coverage
disabled`) so an unexpectedly-empty `.exec` is diagnosable; subsequent failures are suppressed. (Only
the standard forked Gradle test JVM is supported — `-javaagent` puts the agent on the system classpath.
Gradle Worker-API / custom-classloader isolation can break the lookup; the bridge no-ops there.)

- **`PjacocoInProcessExtension`** (JUnit 5): `beforeEach` → `InProcessBridge.activate(testId, null)` on
  the test thread; `afterEach` → `deactivate(testId, passed|failed)`.
- **`PjacocoInProcessRule`** (JUnit 4, `TestWatcher`): a `volatile String result` (default `"skipped"`)
  is set to `"passed"`/`"failed"` in `succeeded`/`failed`; `starting` → activate; `finished` →
  `deactivate(testId, result)` (so assumption-violated/skipped tests still flush, with `result=skipped`).

**testId derivation:** the **fully-qualified** class name + method —
`getRequiredTestClass().getName() + "#" + getRequiredTestMethod().getName()` (JUnit 5) /
`Description.getClassName() + "#" + getMethodName()` (JUnit 4). The FQN (vs the existing HTTP
extensions' `getSimpleName()`, kept short for headers) avoids collisions between same-named test classes
in different packages — the in-process path has no header-length constraint.

**Known limitation (JUnit 5 only):** `@ParameterizedTest` / `@RepeatedTest` / `@TestFactory` share one
method-name `testId` per method, so each invocation overwrites the previous store (`TestStoreRegistry`
retry-overwrite) and only the last invocation's coverage is kept. A per-invocation id strategy is
deferred. **JUnit 4 `@Parameterized` is unaffected:** `Description.getMethodName()` returns
`test[0]`/`test[1]`/…, so the agent-side path (§7c) gets a distinct `testId` (and a separate `.exec`)
per parameter set.

Distinct from the existing `PjacocoExtension`/`PjacocoRule` (HTTP control plane + baggage for the
black-box path). One extension = one activation model; the user picks by test type.

## 6. Threading / isolation / parallelism

- Activation is per **calling thread**. JUnit 5 parallel execution runs each test method on its own
  thread, so each gets its own `CoverageContext` (`ThreadLocal`) → parallel per-test isolation.
- `CoverageContext` stays a **plain `ThreadLocal`** (unchanged). It is deliberately NOT
  `InheritableThreadLocal`: the servlet path runs on a Jetty thread pool, and inheritance would leak a
  test's context onto pooled threads. Keeping the semantics unchanged means zero regression for the
  servlet path.
- Consequence (phase-2 limitation, same as today): a SUT that offloads work to a pool thread that does
  not inherit the context is not attributed. Synchronous unit tests are fine.

## 7. Plugin integration & backward compatibility

- **No plugin change.** For unit tests the SUT runs in the test JVM, so the existing `attachTo`
  (inject `-javaagent` into the test task) already attaches the agent. The in-process bridge
  auto-detects the agent (`CoverageControl` loadable + `isReady()`); out-of-process JVMs lack it and the
  bridge no-ops.
- The agent still binds its HTTP control endpoint (unused by the in-process path). A fixed-port bind
  clash across concurrent agent JVMs only logs a warning — harmless for the in-process path (it never
  calls the endpoint), but note that for the **HTTP/servlet** path a failed bind would silently lose
  coverage (the control-plane calls go nowhere). A build that runs both an in-process and a servlet
  suite should give them distinct `port` values (`pjacoco { port.set(...) }`). A "no endpoint" option
  is future work.
- Output `.exec` is unchanged (vanilla-equivalent); the servlet path and `CoverageContext` semantics are
  untouched; everything new is additive.

## 7a. Whole-run aggregate mode (jacoco-parity single `.exec`)

The agent wires a `LoggerRuntime` + `RuntimeData` that the instrumented classes write to
(`ProbeInstrumentation.install`), but today the `RuntimeData` is a **throwaway local**
(`runtime.startup(new RuntimeData())`) — it is never retained or dumped. (`GoldenEquivalenceIT`
demonstrates the *collect-from-RuntimeData* pattern, but against its own test-local instance, not the
agent's.) **Whole-run mode** retains that global `RuntimeData` and dumps it once at JVM shutdown as a
single **vanilla-JaCoCo-format** `.exec` representing the whole-run coverage — so a user who wants
jacoco's single whole-run artifact can get it alongside the per-test files. (It is the same `.exec`
format and semantically the whole-run coverage; we do not claim byte-for-byte identity with a separate
stock-jacoco run — session name/timestamp/visit order are not normalized.)

- New agent option `aggregateFile=<path>` (default unset = **off**, no behavior change).
- **Resolution:** absolute path → used as-is; otherwise resolved under the `destfile` output directory
  (`<destfile>/<aggregateFile>`). The path **must not contain `,` or `=`** (the agent option string is
  comma-delimited `key=value` with no quoting). `AgentOptions` gains an `aggregateFile()` accessor
  (default null).
- **Write path:** `runtimeData.collect(store, new SessionInfoStore(), false)` yields a jacoco
  `ExecutionDataStore`; a new `AggregateWriter` writes it via `org.jacoco.core.data.ExecutionDataWriter`
  — **authored against `org.jacoco.*` in source exactly like `ExecWriter`**, so the shadow plugin
  relocates it to `io.pjacoco.shaded.jacoco.*` matching the retained `RuntimeData` instance type. (The
  existing per-test `ExecWriter` takes a `TestStore`, so a separate writer is needed.)
- **Wiring & ordering:** `ProbeInstrumentation.install` is changed to **retain** the `RuntimeData` (return
  it so `Bootstrap` holds it). The aggregate write is added to the **same** existing shutdown hook,
  **after** `reg.dumpRemainingAsPartial()` and before `endpoint.stop()` — do not register a second hook.
  No change to instrumentation behavior.
- **Independent of / additive to** the per-test path: the global `RuntimeData` accumulates every probe
  that fires (all tests + class init + untagged code), exactly like stock jacoco; per-test stores are
  unaffected. It is **orthogonal to context and threading**, so it works in **all combinations** —
  sequential or parallel, in-process or out-of-process (the per-test routing is the additive layer; the
  aggregate is jacoco's always-populated base layer).
- This is the **jacoco-parity, zero-touch** path for any framework (JUnit 4/5/TestNG): no test-code
  change, single whole-run `.exec`.
- **Caveats:** (1) **one aggregate per agent JVM** — if multiple SUT JVMs run (sharded out-of-process),
  each writes its own aggregate; `jacococli merge` combines them (same as stock jacoco). (2) It is a
  **shutdown-hook dump** (jacoco `dumponexit` equivalent); a hard kill (SIGKILL) that skips shutdown
  hooks produces no aggregate. A mid-run / tcpserver dump is out of scope.
- **Plugins:** Gradle `pjacoco { aggregateFile.set("jacoco.exec") }` and Maven
  `<configuration><aggregateFile>...</aggregateFile></configuration>` (PrepareAgentMojo gains the param)
  both compose it into the agent option string.

## 7b. JUnit 5 auto-registration (zero per-test annotation)

So a unit suite needs no `@ExtendWith` on every class:

- **Services file:** `testkit-junit5` ships `/META-INF/services/org.junit.jupiter.api.extension.Extension`
  listing **exactly one** entry — `PjacocoInProcessExtension`. The HTTP-path `PjacocoExtension` is
  deliberately NOT listed (a test/review gate verifies the single entry).
- **Plugin change:** `PjacocoGradleExtension` gains a boolean `autoDetectExtensions` (default **true**,
  honoring "dependency present → auto-applies"; set `false` to opt out). When true, `PjacocoPlugin`'s
  `AgentArgs.asArguments()` adds `-Djunit.jupiter.extensions.autodetection.enabled=true` alongside the
  agent arg on the attached task. With plugin + `testkit-junit5` dep, the in-process extension applies
  suite-wide.
- **Trade-off (documented):** `autodetection.enabled` is a global JUnit switch — it auto-applies *all*
  service-registered extensions on the classpath, not only pjacoco's. `autoDetectExtensions.set(false)`
  is the escape hatch.
- **Safety guard for accidental activation:** `CoverageControl.deactivate` checks `store.classCount()`:
  if **0**, it **discards the store without flushing** (a new `registry.discard(testId)` that removes
  the entry but writes nothing) instead of `registry.stop` (which always flushes). So if the
  auto-registered extension fires on a test thread where no instrumented SUT runs (e.g. a servlet
  black-box test, SUT on a server worker thread), it produces **no garbage file**. (The guard lives in
  `CoverageControl.deactivate` only — NOT in the shared writer — so the existing servlet e2e that
  intentionally writes an empty `.exec` for a registered-but-untagged test, `SpecAcceptanceE2E.untagged
  Request_notRecorded_andNoThreadLeak`, is unaffected.)
- **Bridge warning:** `InProcessBridge` suppresses its one-time "agent not reachable" warning when
  `pjacoco.control-url` is set — that means the user is on the black-box path and a local agent is
  expected to be absent (no in-process bridge), so the warning would be misleading.
- **Mixed-mode caveat (documented):** a single test task using **both** the auto in-process path and the
  explicit servlet `PjacocoExtension` runs both `afterEach` callbacks (JUnit 5 LIFO order): the HTTP
  `PjacocoExtension` stops the testId first, then the in-process `deactivate` calls
  `registry.stop`/`discard` for an already-removed testId → a **harmless** `stop-missing` warning per
  test (no data loss — the empty-store guard already suppressed the in-process write). Use separate
  tasks, or set `autoDetectExtensions.set(false)` for the black-box task. README states this.
- **JUnit 4:** zero-touch is provided **agent-side** (no JUnit 5-style services/autodetection for
  JUnit 4) — see §7c. The explicit `@Rule PjacocoInProcessRule` remains available for explicit control
  (or when the agent-side path is turned off).

## 7c. JUnit 4 zero-touch (agent-side `runLeaf` activation)

JUnit 4 has no dependency-only auto-apply mechanism (a `RunListener` needs build config + has weaker
test-thread guarantees). Instead the agent brackets each JUnit 4 test by weaving JUnit 4's per-test
choke point — exactly the existing `ServletInboundActivator` pattern, but for JUnit 4's runner.

- **New agent inbound activator** `io.pjacoco.agent.inbound.junit4.JUnit4InboundActivator` (an
  `InboundActivator`, installed by `Bootstrap`): ByteBuddy advice on
  `org.junit.runners.ParentRunner.runLeaf(Statement, Description, RunNotifier)` — the single method that
  runs one leaf test (its `@Before`/`@Test`/`@After`/rules) via `statement.evaluate()` **inline on the
  calling thread**.
- **Advice** (`RunLeafAdvice`, `suppress = Throwable`): `@OnMethodEnter` reads the `Description` arg
  **reflectively** (`getClassName()`/`getMethodName()` — no JUnit 4 dependency on the agent, like
  `ServletAdvice` reading the baggage header) and calls `CoverageControl.activate("Class#method", null)`;
  `@OnMethodExit` calls `CoverageControl.deactivate("Class#method", "unknown")`. The result is the fixed
  string `"unknown"` because `runLeaf` catches all test exceptions internally (`EachTestNotifier`), so the
  advice has no pass/fail signal (the `@Rule` path, via `TestWatcher`, does and writes `passed`/`failed`).
  Because `runLeaf` calls `evaluate()` inline, activate/deactivate bracket the test on its own thread
  (correct under sequential, surefire-parallel, and the JUnit Vintage engine, which still drives
  `ParentRunner`).
- **Limitation — `@Test(timeout=X)` / `@Rule Timeout`:** both delegate to JUnit 4's `FailOnTimeout`,
  which runs the test body on a **newly spawned thread**; `CoverageContext` is set on the runner thread,
  not that one, so coverage for such tests is silently empty under the zero-touch path. (Same class as
  the §2 async limitation, but caused by JUnit's own infrastructure.) Use the explicit `@Rule` won't
  help either; such tests need manual context propagation (phase 2).
- **Zero-touch:** no `@Rule`, no build config, no extra dependency — only the already-attached agent.
- **Opt-out:** agent option `junit4Auto` (default **true**; set `junit4Auto=false` to disable). Gradle
  `pjacoco { junit4Auto.set(false) }` / Maven config compose it. `AgentOptions` gains the accessor.
  `Bootstrap` installs the activator only when `junit4Auto` is true.
- **Safety:** in an out-of-process app JVM `ParentRunner` is never loaded, so the advice never matches
  (no effect). The empty-store guard (§7b) means a test whose SUT runs elsewhere writes no file.
- **Do not combine with `@Rule PjacocoInProcessRule`** on the same test (redundant nested activate →
  retry-overwrite + a harmless `stop-missing` warning). For a JUnit 4 **servlet black-box** suite, set
  `junit4Auto=false` and use the HTTP/servlet path. README states this.

## 8. E2E / acceptance tests (definition of done)

Authored first (red), driven green by inner-loop unit TDD.

- **AC-IP1 (unit, isolation):** a pure unit suite (no servlet/HTTP) with
  `@ExtendWith(PjacocoInProcessExtension.class)`, JUnit 5 **parallel**. The sample sets
  `pjacoco { includes.set(listOf("<SutA FQN>", "<SutB FQN>")) }` so only the two SUT classes are
  instrumented. Two tests call **different** SUT classes directly; assert (from each `<testId>.json`
  sidecar / `.exec`): testA's store contains **SutA and not SutB**, testB's contains **SutB and not
  SutA** (mutual exclusion — proves routing correctness, not just absence of leakage; mirrors
  `SpecAcceptanceE2E.perTestIsolation`). The agent is attached to the test JVM via the plugin `attachTo`.
- **AC-IP2 (JUnit 4):** both JUnit 4 paths produce per-test `.exec` — (a) the **agent-side zero-touch**
  path (no `@Rule`, `junit4Auto` on; sidecar `result="unknown"`) and (b) the explicit `@Rule
  PjacocoInProcessRule` (`result="passed"`). Assert `junit4Auto=false` disables the agent-side path; a
  `@Parameterized` test yields a separate `.exec` per parameter set; a `@Test(timeout=X)` test yields no
  `.exec` (documented limitation — not a failure).
- **AC-IP3 (unit):** agent `CoverageControl` (activate sets context + creates store, null-guarded;
  deactivate flushes) and the testkit `InProcessBridge` (no-op + one-time warn when the agent is absent)
  have unit tests.
- **AC-IP4 (build guard):** a test asserts `io/pjacoco/agent/api/CoverageControl.class` is present in the
  built shaded agent jar (FQN-stability regression guard).
- **AC-IP5 (whole-run aggregate):** with `aggregateFile` set, after a run the single aggregate `.exec`
  exists and, analyzed by jacoco's `Analyzer`, its covered lines are a **superset of** (contain) the
  union of the per-test `.exec` covered lines, and it includes coverage from both SUT classes (it is the
  whole-run jacoco artifact — a superset, since class-init / outside-boundary code is also recorded).
  Default-unset produces no aggregate file (no regression).
- **AC-IP6 (auto-registration):** a unit suite with **no `@ExtendWith`** — only the `testkit-junit5`
  dependency + the plugin (`autoDetectExtensions` on) — produces per-test `.exec`. Assert (a) the
  empty-store guard: an activation that records nothing writes no file; (b) a clean in-process-only run
  emits no `stop-missing` warning.
- **Regression:** the full agent suite + existing testkit/plugin/sample suites stay green.

**Feasibility:** AC-IP1/IP2 run as a real consumer (Gradle plugin + testkit from mavenLocal, like the
existing `samples/gradle-sample`), in CI, asserting the per-test `.exec` + `classCount`. The in-process
mechanism itself is already demonstrated by `ProbeRoutingIT` at the unit level.

## 9. Definition of done

AC-IP1–IP6 green; full regression green; docs updated — README "Scope" notes in-process per-test
coverage is now supported (synchronous in-JVM tests), the testkit usage section documents
`PjacocoInProcessExtension`/`PjacocoInProcessRule` (with auto-registration) and the `aggregateFile`
whole-run option, and the async/thread-pool + mixed-mode limitations are stated.

## 10. Three-model review log (2026-06-16)

Reviewed by Claude Sonnet (design-doc-reviewer), a second Claude Sonnet (fallback for Gemini, which
timed out), and OpenAI GPT-5.2. Incorporated (consensus / valid):

- AC-IP1 strengthened: narrow `includes` to the SUT classes + assert mutual exclusion (which class is in
  each `.exec`), not just `classCount=1` (all three).
- testId collisions: pinned the exact FQN expression; documented the parameterized/repeated-test
  retry-overwrite limitation (all three).
- `Bootstrap.premain()` wiring made explicit (`CoverageControl.bindRegistry(registry)`) (Sonnet).
- `activate()` null-guard + warning when `active()` returns null (Sonnet ×2).
- Classloader lookup: system → context → `Class.forName` fallback + one-time warn + documented
  Worker-API/isolation failure mode (all three).
- `PjacocoInProcessRule` result field (default `skipped`, flush in `finished`) (Sonnet ×2).
- "not relocated (stable FQN inside the shaded jar)" wording + AC-IP4 build guard (Sonnet-fb, GPT).
- §1 "sole caller"/"empty .exec" precision (GPT); port/multi-suite + control-endpoint coverage-loss
  note (Sonnet-fb, GPT); MockMvc alternative rationale (GPT).

No findings rejected — all were located and verifiable.

## 11. Three-model review log — additions (§7a/§7b)

The two added features were reviewed by Claude Sonnet, Gemini 3.5 Flash, and (after GPT-5.2 hung) a
second Claude Sonnet fallback — plus the eventual GPT-5.2 output. Incorporated:

- Aggregate write path: dedicated `AggregateWriter` via `org.jacoco.core.data.ExecutionDataWriter`
  (authored against `org.jacoco.*`, shadow-relocated like `ExecWriter`); `ExecWriter` only takes a
  `TestStore` (all four).
- Corrected the inaccurate "GoldenEquivalenceIT collects from the agent's RuntimeData" framing — it
  uses a test-local instance; the agent's RuntimeData is a throwaway local that must be retained (Sonnet).
- AC-IP5: **superset**, not equality (the global RuntimeData also records class-init / outside-boundary
  code) (Sonnet ×2, GPT); softened the "byte-equivalent" wording (GPT).
- Shutdown-hook ordering: aggregate write in the same hook, after `dumpRemainingAsPartial` (Sonnet-fb).
- `aggregateFile` path resolution + no `,`/`=` + `AgentOptions` accessor (Sonnet, Sonnet-fb).
- Maven plugin parity (`PrepareAgentMojo` aggregateFile param) (Gemini).
- Gradle plugin change for autodetection: `autoDetectExtensions` DSL flag + `AgentArgs` injection +
  opt-out (Sonnet, Gemini, Sonnet-fb).
- Empty-store guard placed in `CoverageControl.deactivate` (`registry.discard` without flush), NOT the
  shared writer — reconciled with the existing untagged-test e2e that intentionally writes empty `.exec`.
- Services file pins exactly one entry (`PjacocoInProcessExtension`) (Sonnet).
- Mixed-mode LIFO → harmless `stop-missing` warning documented; AC-IP6 asserts none for clean runs
  (Sonnet-fb).
- Bridge warning silenced when `pjacoco.control-url` is set (Gemini).
- `CoverageControl` null-safety when registry unbound (Gemini, GPT); thread-premise note + AC guard (GPT).

No findings rejected.

## 12. Review log — §7c JUnit 4 agent-side activation

Focused Claude Sonnet review (verified against JUnit 4.13.2 bytecode): confirmed `ParentRunner.runLeaf`
is the correct `protected final` choke point, ByteBuddy advice can instrument it, all subclasses
(Block/Parameterized/Suite/Vintage) flow through it, and `statement.evaluate()` runs inline on the
calling thread. Incorporated: `deactivate` result fixed to `"unknown"` (runLeaf swallows exceptions, no
pass/fail signal); `@Test(timeout)`/`@Rule Timeout` → `FailOnTimeout` runs on a new thread → silently
empty, documented as a §7c limitation + AC-IP2 negative assertion; corrected the parameterized-test
limitation to JUnit 5 only (JUnit 4 `@Parameterized` gets distinct `test[i]` testIds). No findings
rejected.
