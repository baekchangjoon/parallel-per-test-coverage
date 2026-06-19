# GA-spike results — trace-context per-test coverage (C1)

Recorded PASS/FAIL decisions for the gating architecture spikes. Each entry records the
observed evidence, the exact woven class/method, and the recommended mechanism.

---

## GA-1 (Brave / Spring Cloud Sleuth) — **PASS**

**Date:** 2026-06-19
**Spike target:** `legacy-tram` `reservation` service (Java 8, Spring Boot 2.7.18, Spring Cloud
Sleuth 3.1.9, Eventuate Tram 0.35.0). Brave version: shipped inside Sleuth 3.1.9 (brave 5.13.x).

### Central question

Sleuth builds `brave.propagation.CurrentTraceContext` at startup and it is immutable afterward
(no post-build `addScopeDecorator`). Can a ByteBuddy java-agent weave the scope enter/exit methods
of the concrete `CurrentTraceContext` so an enter/exit callback fires — on the request thread AND on
threads the work is handed off to — carrying the same `traceIdString()`?

### Verdict: PASS — scope weave works on sync AND async threads.

A ByteBuddy java-agent (`premain`, `RedefinitionStrategy.RETRANSFORMATION`,
`disableClassFormatChanges` = body-only advice) successfully wove the **already-loaded, immutable**
Brave scope machinery. `final` on the concrete class does not block body-only advice (it only blocks
subclassing).

### Exact classes/methods woven

Observed at install time (agent stderr):

```
[BRAVE-SPIKE] weaving CurrentTraceContext subtype: brave.propagation.ThreadLocalCurrentTraceContext
[BRAVE-SPIKE] weaving CurrentTraceContext subtype: brave.propagation.CurrentTraceContext$Default
[BRAVE-SPIKE] weaving Scope implementor: brave.baggage.CorrelationUpdateScope
[BRAVE-SPIKE] weaving Scope implementor: brave.baggage.CorrelationUpdateScope$Multiple
[BRAVE-SPIKE] weaving Scope implementor: brave.propagation.ThreadLocalCurrentTraceContext$RevertToNullScope
[BRAVE-SPIKE] weaving Scope implementor: brave.propagation.CurrentTraceContext$Scope$1
```

- **ENTER** advice on `newScope(TraceContext)` and `maybeScope(TraceContext)` of every concrete
  subtype of `brave.propagation.CurrentTraceContext` (matched via
  `hasSuperType(named("brave.propagation.CurrentTraceContext"))`). The concrete runtime instance Sleuth
  3.1.9 builds is **`brave.propagation.ThreadLocalCurrentTraceContext`** (wrapped by baggage/correlation
  decorators). The advice reads `traceContext.traceIdString()` **reflectively** (agent has NO brave
  dependency, `Object`-typed args) and registers the returned `Scope` identity → traceId.
- **EXIT** advice on `Scope.close()` of every concrete implementor of
  `brave.propagation.CurrentTraceContext$Scope`, paired back to its ENTER by scope-instance identity.

### Async propagation: OBSERVED

The B3-injected trace id `bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb` produced ENTER+EXIT on the **request
thread** AND on the **`@Async` pool worker thread** with the **same trace id**:

```
[SCOPE-ENTER tid=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb thread=http-nio-8080-exec-5 scope=5f93af17 ctc=brave.baggage.CorrelationUpdateScope$Multiple]
[SPIKE-REQ] request thread=http-nio-8080-exec-5
[SCOPE-ENTER tid=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb thread=task-1 scope=bcc8e97 ctc=brave.baggage.CorrelationUpdateScope$Multiple]
[SPIKE-WORK] async thread=task-1
[SCOPE-EXIT  tid=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb thread=task-1 scope=bcc8e97]
[SCOPE-EXIT  tid=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb thread=http-nio-8080-exec-5 scope=5f93af17]
```

A second request (no incoming B3 header → Sleuth-generated `b079c0cf3c9a8b33`) showed the same
pattern across `http-nio-8080-exec-6` (sync) + `task-2` (async). ENTER/EXIT are correctly nested and
paired per scope instance across threads.

The handoff path is a spike-only `@Async` endpoint (`/spike/async`) added to `reservation`
(`SpikeAsyncController`, `@EnableAsync`): Sleuth's `LazyTraceExecutor`/async wrapping re-enters the
brave scope on the task-executor pool thread, and our weave fired on that thread.

### Recommended mechanism for REQ-005 / REQ-006 (T10 Brave path)

**Primary: scope weave (this spike's mechanism).** Weave `newScope`/`maybeScope` of every concrete
`brave.propagation.CurrentTraceContext` subtype for ENTER, and `Scope.close()` of every concrete
`CurrentTraceContext$Scope` implementor for EXIT, pairing by scope-instance identity. This is the
zero-touch, Sleuth-version-agnostic mechanism: it does NOT require post-build `addScopeDecorator`, no
Spring/Sleuth bean customization, and works whether or not the app re-enters the scope on async
threads (it observes whatever brave actually does).

Notes / refinements for the production `TraceScopeBridge` (T10):
- The `Scope.close()` that fires at EXIT is the **outermost decorator** (`CorrelationUpdateScope$Multiple`)
  rather than the base `ThreadLocalCurrentTraceContext` scope; this is fine because the trace id is
  captured from the `newScope`/`maybeScope` argument at ENTER and paired by identity — the bridge never
  needs the context at close time.
- `maybeScope(null)` returns the shared NOOP scope; the bridge must skip null-context enters (done) to
  avoid registering the shared singleton.
- The pairing map (scope identity → key) must be a leak-safe structure in production (scopes are
  short-lived and always closed, but a weak/identity map + defensive cleanup is advisable).

**Not needed: `CurrentTraceContextCustomizer`.** It is a viable Spring-integration alternative
(register a `ScopeDecorator` at build time via a `CurrentTraceContextCustomizer` bean), but it requires
owning Spring config in the target app (not zero-touch) and only covers the Spring-built context. The
scope weave is strictly more general, so we adopt the weave and keep the customizer as a documented
fallback only.

**Choke-point fallback (REQ-024): not required for Brave.** The scope weave covers the async handoff,
so the choke-point fallback is not needed on the Brave path.

### Reproduction

- Agent: `spike/src/main/java/io/pjacoco/spike/BraveScopeHookAgent.java` (+ `ScopeEnterAdvice`,
  `ScopeCloseAdvice`, `BraveScopeBridge`). Build: `cd spike && ../gradlew braveSpikeAgent` →
  `spike/build/libs/brave-scope-spike-agent.jar` (byte-buddy bundled, `Premain-Class`/`Agent-Class`/
  `Can-Retransform-Classes` manifest).
- Attach (legacy-tram, spike-only overlay): `docker-compose.bravespike.yml` mounts the jar and sets
  `JAVA_TOOL_OPTIONS=-javaagent:/agent/brave-scope-spike-agent.jar` on `reservation`, publishing
  `:58081`.
- Boot: `docker compose -f docker-compose.yml -f docker-compose.e2e.yml -f docker-compose.bravespike.yml up -d --build --wait order-web reservation ledger eventuate-cdc-service`
- Exercise: `curl -s http://localhost:58081/spike/async -H 'X-B3-TraceId: bbbb...' -H 'X-B3-SpanId: cccc...' -H 'X-B3-Sampled: 1'`

---

## GA-3 — (pending, Task 3)

## GA-1 (OpenTelemetry) — **FAIL (primary candidate) → adopt shaded-ContextStorage weave**

**Date:** 2026-06-19
**Spike target:** minimal standalone Spring Boot 2.7.18 app (Java 8) with `opentelemetry-api:1.45.0`
on its classpath and a `/spike/async` endpoint that hands off to a plain `java.util.concurrent`
`ExecutorService`. OTel javaagent: `opentelemetry-javaagent.jar` **v2.11.0** (Java 8-capable),
`-Dotel.*.exporter=none`. (No tainted service / Docker needed — the question is pure OTel mechanics.)

### Central question

With the OTel javaagent attached, can an `io.opentelemetry.context.ContextStorageProvider` extension's
`ContextStorage` wrapper observe attach()/detach() (context becoming current) on the request thread
AND an executor/@Async handoff thread, reading `Span.fromContext(ctx).getSpanContext().getTraceId()` —
so pjacoco can bind a coverage key = traceId? Loaded via `-Dotel.javaagent.extensions=<jar>` /
`OTEL_JAVAAGENT_EXTENSIONS`.

### Verdict: FAIL for the `ContextStorageProvider` extension SPI. Trace context DOES propagate (same traceId sync+async), but the SPI extension is the wrong observation point.

The PRIMARY candidate cannot work, for two independently-proven reasons:

1. **Loaded as a true extension, the provider is invisible to the app-side SPI.** The OTel
   `ExtensionClassLoader` is a child of the agent classloader and is NOT on the application
   classloader's parent chain. The application-side `io.opentelemetry.context.LazyStorage.get()` runs
   `ServiceLoader.load(ContextStorageProvider.class)` with the **application** TCCL and never finds the
   extension's provider, so `SpikeContextStorageProvider.get()` is never called. (`-Dotel.javaagent.extensions=`
   and `OTEL_JAVAAGENT_EXTENSIONS=` both DO load the jar — agent logs `Installed 1 extension(s)` — but
   loading ≠ SPI discovery.)

2. **Even forced onto the app classloader, attach/detach are never observed.** When the ext jar is
   injected into the Boot fat jar's `BOOT-INF/lib` (stored/uncompressed), the app-TCCL ServiceLoader
   finds the provider and `get()` runs — but the agent's `ContextStorageWrappersInstrumentation`
   **prepends `AgentContextStorage.wrap()`** to the wrapper chain, so the live `ContextStorage.get()`
   returns `AgentContextStorage`. `AgentContextStorage.attach()/current()` store the application context
   inside the agent's **shaded** context (`APPLICATION_CONTEXT` `ContextKey`) and attach via the shaded
   machinery; they do **not** delegate to the wrapped storage (which is consulted only for `root()` in
   the constructor). The wrapped `DelegatingContextStorage.attach()` is therefore **never invoked**.

### Evidence (verbatim)

```
[otel.javaagent ...] DEBUG ...AgentInstaller - Installed 1 extension(s)
Class.forName(SpikeProvider) FAILED: java.lang.ClassNotFoundException: io.pjacoco.spike.otel.SpikeContextStorageProvider   # extension-only: invisible to app CL
```
```
[OTEL-SPIKE] SpikeContextStorageProvider.get() called -> installing DelegatingContextStorage (classloader=...LaunchedURLClassLoader@628b819d)
[OTEL-SPIKE] DelegatingContextStorage constructed, delegate=io.opentelemetry.context.ThreadLocalContextStorage
ContextStorage.get()=io.opentelemetry.javaagent.instrumentation.opentelemetryapi.context.AgentContextStorage ...   # AgentContextStorage dominates; NO [OTEL-SPIKE attach/detach] lines fire for any request
```
Trace context propagation itself works — injected `traceparent: 00-1111…1111-2222…2222-01`:
```
[SPIKE-REQ]  request thread=http-nio-8080-exec-2 span.tid=11111111111111111111111111111111
[SPIKE-WORK] async thread=pool-1-thread-1       span.tid=11111111111111111111111111111111   # same traceId across the executor handoff
```
Bytecode confirmation (decompiled from the agent jar): `LazyStorage.get()` applies
`ContextStorageWrappers.getWrappers()`; `ContextStorageWrappersInstrumentation$AddWrapperAdvice`
inserts `AgentContextStorage.wrap()` at index 0; `AgentContextStorage.attach()` operates on the shaded
`Context`/`APPLICATION_CONTEXT` and never calls the wrapped delegate's `attach`.

### Classloader findings

- Extension provider class loads into `ExtensionClassLoader`, **not reachable** from the app
  `org.springframework.boot.loader.LaunchedURLClassLoader` (ClassNotFoundException from app code).
- The real propagation path is the agent's **shaded** API
  (`io.opentelemetry.javaagent.shaded.io.opentelemetry.context.*`), resident in the OTel
  `AgentClassLoader` — not the app or bootstrap CL. The production observation hook and pjacoco's
  shared `CoverageContext` state must live where they can reach that (keep the bridge state on the
  **bootstrap/system CL**, reached reflectively from woven advice — same posture as the Brave spike).

### Recommended mechanism for REQ-005 / REQ-006 (T10 OTel path)

**Adopt: ByteBuddy weave of the agent's SHADED `ContextStorage` — the OTel analogue of the (PASSED)
Brave scope-weave.**
- ENTER: body-only advice on
  `io.opentelemetry.javaagent.shaded.io.opentelemetry.context.ThreadLocalContextStorage#attach(Context)`;
  read the trace id reflectively from the shaded `Context` arg
  (`Span.fromContext(ctx).getSpanContext().getTraceId()`), agent has no OTel compile dep (`Object` arg +
  reflection, like `BraveScopeBridge`).
- EXIT: body-only advice on `close()` of the returned shaded `Scope` impl
  (`ThreadLocalContextStorage$ScopeImpl`), paired to ENTER by scope-instance identity.
- This is on the real per-thread propagation path the agent uses (request + executor/@Async re-attach),
  so it observes the same traceId on every thread (Evidence above confirms the context is current on the
  handoff thread). Use `RedefinitionStrategy.RETRANSFORMATION` + `disableClassFormatChanges` to weave the
  already-loaded shaded classes (OTel `premain` loads them before pjacoco).

**Rejected:** `ContextStorageProvider` extension SPI (this spike — FAIL); `ContextStorage.addWrapper`
(not public/stable, pre-`get()`-only, and bypassed by `AgentContextStorage` anyway); requiring the app
to ship its own provider (not zero-touch and still does not observe attach/detach).

**Carry to GA-3 / T3:** confirm pjacoco can match + retransform shaded classes resident in the OTel
`AgentClassLoader`, and that OTel-premain-then-pjacoco agent ordering still permits retransformation.

### Reproduction

- Extension: `spike/otel-ext/` (`SpikeContextStorageProvider`, `DelegatingContextStorage`,
  `META-INF/services/io.opentelemetry.context.ContextStorageProvider`; OTel API `compileOnly`).
  Build: `cd spike && ../gradlew :otel-ext:jar` → `spike/otel-ext/build/libs/otel-context-spike-ext.jar`.
- Target app + OTel agent v2.11.0 were scratch (`/tmp/otel-spike`, discarded). Run:
  `java -javaagent:opentelemetry-javaagent.jar -Dotel.javaagent.extensions=otel-context-spike-ext.jar
   -Dotel.traces.exporter=none -Dotel.metrics.exporter=none -Dotel.logs.exporter=none -jar app.jar`;
  exercise `curl -H 'traceparent: 00-1111…1111-2222…2222-01' http://localhost:8080/spike/async`.
- Full investigation: `sdd/task-1-report.md`.
