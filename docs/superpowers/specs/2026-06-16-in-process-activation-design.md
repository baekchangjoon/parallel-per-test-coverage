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
  io.pjacoco.agent.api.CoverageControl     # NEW: in-JVM activation API (not relocated; stable FQN contract)

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

**Known limitation (documented):** parameterized / repeated / dynamic tests share one `testId` per
method, so each invocation overwrites the previous store (`TestStoreRegistry` retry-overwrite) and only
the last invocation's coverage is kept in the `.exec`. A per-invocation id strategy is deferred.

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

## 8. E2E / acceptance tests (definition of done)

Authored first (red), driven green by inner-loop unit TDD.

- **AC-IP1 (unit, isolation):** a pure unit suite (no servlet/HTTP) with
  `@ExtendWith(PjacocoInProcessExtension.class)`, JUnit 5 **parallel**. The sample sets
  `pjacoco { includes.set(listOf("<SutA FQN>", "<SutB FQN>")) }` so only the two SUT classes are
  instrumented. Two tests call **different** SUT classes directly; assert (from each `<testId>.json`
  sidecar / `.exec`): testA's store contains **SutA and not SutB**, testB's contains **SutB and not
  SutA** (mutual exclusion — proves routing correctness, not just absence of leakage; mirrors
  `SpecAcceptanceE2E.perTestIsolation`). The agent is attached to the test JVM via the plugin `attachTo`.
- **AC-IP2 (JUnit 4):** a `PjacocoInProcessRule` (Vintage) variant produces per-test `.exec`.
- **AC-IP3 (unit):** agent `CoverageControl` (activate sets context + creates store, null-guarded;
  deactivate flushes) and the testkit `InProcessBridge` (no-op + one-time warn when the agent is absent)
  have unit tests.
- **AC-IP4 (build guard):** a test asserts `io/pjacoco/agent/api/CoverageControl.class` is present in the
  built shaded agent jar (FQN-stability regression guard).
- **Regression:** the full agent suite + existing testkit/plugin/sample suites stay green.

**Feasibility:** AC-IP1/IP2 run as a real consumer (Gradle plugin + testkit from mavenLocal, like the
existing `samples/gradle-sample`), in CI, asserting the per-test `.exec` + `classCount`. The in-process
mechanism itself is already demonstrated by `ProbeRoutingIT` at the unit level.

## 9. Definition of done

AC-IP1–IP3 green; full regression green; docs updated — README "Scope" notes in-process per-test
coverage is now supported (synchronous in-JVM tests), the testkit usage section documents
`PjacocoInProcessExtension`/`PjacocoInProcessRule`, and the async/thread-pool limitation is stated.

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
