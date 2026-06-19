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

## GA-1 (OTel) — (pending, Task 1)
