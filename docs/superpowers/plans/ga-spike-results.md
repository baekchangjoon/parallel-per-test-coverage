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

## GA-3 — classloader reachability + 2-agent ordering + OTel weave green — **PASS**

**Date:** 2026-06-19
**Spike target:** same minimal standalone Spring Boot 2.7.18 app (Java 8) + OTel javaagent v2.11.0 as
the GA-1(OTel) spike, run on Temurin JDK 8 (`1.8.0_412`). `/spike/async` hands off to a plain
`java.util.concurrent.ExecutorService`; a servlet `Filter` reads the active span at `service()` entry.

### Verdict: PASS on all three questions. This run also **closes the GA-1 (OTel) weave gap** — the
recommended shaded-`attach` weave is now proven GREEN, not just inferred from bytecode.

### Q1 — OTel shaded-`attach` weave: PASS (GREEN)

A second ByteBuddy java-agent (`premain`, `RedefinitionStrategy.RETRANSFORMATION`,
`disableClassFormatChanges`) attached alongside the OTel javaagent **matches + weaves the shaded
storage** `io.opentelemetry.javaagent.shaded.io.opentelemetry.context.ThreadLocalContextStorage`:
ENTER on `attach(Context)`, EXIT on `ThreadLocalContextStorage$ScopeImpl#close()`, reading
`Span.fromContext(ctx).getSpanContext().getTraceId()` reflectively (no OTel compile dep).

```
[OTEL-WEAVE] weaving storage io.opentelemetry.javaagent.shaded.io.opentelemetry.context.ThreadLocalContextStorage loader=bootstrap
[OTEL-WEAVE] weaving scope io.opentelemetry.javaagent.shaded.io.opentelemetry.context.ThreadLocalContextStorage$ScopeImpl loader=bootstrap
[OTEL-WEAVE attach tid=11111111111111111111111111111111 thread=http-nio-8080-exec-1 scope=520d010b covCtx=11111111111111111111111111111111]
[SPIKE-REQ]  request thread=http-nio-8080-exec-1 span.tid=11111111111111111111111111111111
[OTEL-WEAVE attach tid=11111111111111111111111111111111 thread=pool-1-thread-1 scope=41ddb2da covCtx=11111111111111111111111111111111]
[SPIKE-WORK] async thread=pool-1-thread-1 span.tid=11111111111111111111111111111111
[OTEL-WEAVE detach tid=11111111111111111111111111111111 thread=pool-1-thread-1 scope=41ddb2da]
[OTEL-WEAVE detach tid=11111111111111111111111111111111 thread=http-nio-8080-exec-1 scope=520d010b]
```

`attach`/`detach` fire on the **request thread AND the executor handoff thread, same traceId**, paired by
scope identity (second request `aaaa…` repeated the pattern on `exec-4`+`pool-1-thread-2`).

**Two blockers found + fixed (both load-bearing for T10):**
1. `NoSuchTypeException: Cannot resolve type description for ...shaded...ContextStorage` — the shaded
   classes are bootstrap-loaded; ByteBuddy's default `TypePool` reads supertypes through the target
   (bootstrap) CL and can't find the shaded interface bytes, so the transform applied NO advice. Fix: a
   custom `PoolStrategy` compounding a `ClassFileLocator.ForJarFile` over the OTel agent jar (path
   auto-discovered from the JVM `-javaagent:` args).
2. `IllegalAccessException ...SdkSpan` — concrete `Span`/`SpanContext` impls are non-public; resolve the
   getters from the PUBLIC shaded interfaces and invoke through them.

**Already-loaded RETRANSFORM proven:** a watcher retransformed the shaded classes after warm-up:
`already-loaded ...ThreadLocalContextStorage loader=bootstrap modifiable=true; ...$ScopeImpl
modifiable=true` → `RETRANSFORM OK`, and a fresh request still produced attach on request + pool threads.
So the shaded bootstrap classes are modifiable + retransformable and re-weaving is idempotent.

### Q2 — Classloader placement of shared state: **bootstrap**

The shaded context classes load on the **bootstrap CL** (every weave line: `loader=bootstrap`) in v2.11.0
— this **corrects** the T1 report's tentative "resident in the OTel AgentClassLoader" assumption for the
`context.*` shaded package. pjacoco's shared `CoverageContext` (and the bridge) must therefore live on the
bootstrap CL (the system CL where the `-javaagent` jar lives is NOT on bootstrap's parent chain). The agent
injects them via `Instrumentation.appendToBootstrapClassLoaderSearch`. Confirmed end-to-end:

```
[OTEL-WEAVE Q2] OtelWeaveBridge loader=bootstrap CoverageContext loader=bootstrap shadedContext loader=bootstrap
```

`covCtx=<traceId>` on every attach line proves the woven advice wrote the traceId into the
bootstrap-resident `CoverageContext` and read the same value back (single shared instance). The Brave
advice runs in the app CL, whose parent chain also reaches bootstrap, so a single bootstrap-resident
`CoverageContext` is reachable from BOTH backends. **Decision: place `CoverageContext` + trace-scope bridge
state on the bootstrap CL, injected by pjacoco's agent.**

### Q3 — Two-agent install ordering: **order does NOT matter**; choke-point valid

Both `OTel-first` and `pjacoco-first` CLI orderings produce a green weave (attach/detach on request +
handoff threads, zero errors). pjacoco-first catches the shaded class at its initial load; OTel-first
catches it at initial load too (and the retransform probe proves the already-loaded case). The only
ordering-robust requirement is the OTel-jar TypePool locator (Q1 blocker 1), not the agent order.
Choke-point fallback sees a valid span at servlet entry:
`[SPIKE-CHOKE] servlet service() entry ... span.tid=1111… valid=true`. The weave covers the async handoff,
so the choke-point is a defensive sync-HTTP-only fallback (same as the Brave path).

### Reproduction

- Agent: `spike/src/main/java/io/pjacoco/spike/OtelWeaveHookAgent.java` (+ `OtelAttachAdvice`,
  `OtelCloseAdvice`, `OtelWeaveBridge`, `CoverageContext`). Build:
  `cd spike && ../gradlew otelWeaveAgent` → `spike/build/libs/otel-weave-spike-agent.jar`.
- Run (OTel-first): `java -javaagent:opentelemetry-javaagent.jar -javaagent:otel-weave-spike-agent.jar
  -Dotel.{traces,metrics,logs}.exporter=none -jar app.jar`; exercise
  `curl -H 'traceparent: 00-1111…1111-2222…2222-01' http://localhost:8080/spike/async`.
- Full investigation: `sdd/task-3-report.md`. Scratch app/agent (`/tmp/otel-weave-spike`) discarded.

---

## GA-1 (OpenTelemetry) — **FAIL for the SPI primary → PASS via shaded-ContextStorage weave (proven GREEN in GA-3)**

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

**Carry to GA-3 / T3 — RESOLVED (see GA-3 above, PASS):** pjacoco CAN match + weave the shaded storage
and retransform it once already-loaded; the shaded `context.*` classes are **bootstrap-loaded** (not
`AgentClassLoader`-resident as guessed here); both agent orderings work, provided a `PoolStrategy` locator
over the OTel agent jar is supplied so ByteBuddy can resolve the shaded type hierarchy.

### Reproduction

- Extension: `spike/otel-ext/` (`SpikeContextStorageProvider`, `DelegatingContextStorage`,
  `META-INF/services/io.opentelemetry.context.ContextStorageProvider`; OTel API `compileOnly`).
  Build: `cd spike && ../gradlew :otel-ext:jar` → `spike/otel-ext/build/libs/otel-context-spike-ext.jar`.
- Target app + OTel agent v2.11.0 were scratch (`/tmp/otel-spike`, discarded). Run:
  `java -javaagent:opentelemetry-javaagent.jar -Dotel.javaagent.extensions=otel-context-spike-ext.jar
   -Dotel.traces.exporter=none -Dotel.metrics.exporter=none -Dotel.logs.exporter=none -jar app.jar`;
  exercise `curl -H 'traceparent: 00-1111…1111-2222…2222-01' http://localhost:8080/spike/async`.
- Full investigation: `sdd/task-1-report.md`.

---

## GA-2 (C3b 게이트): OTel javaagent의 Kafka 홉 trace 자동 전파 — PASS (C1 cross-JVM 실측 근거)

**질문:** OTel javaagent가 Kafka producer→consumer 홉에서 trace context(traceId)를 자동 전파하는가(HTTP뿐 아니라 Kafka record header)?

**판정: PASS** — 별도 spike 불요. C1 OTel weave Kafka-consumer 갭 작업(`docs/superpowers/decisions/2026-06-20-otel-weave-kafka-consumer-gap.md`, RESOLVED; PR #13)에서 **실측**:
- tainted-spring(real OTel javaagent 2.11.0, Java 11)에서 diary 서비스가 Kafka로 발행한 이벤트를 **별도 JVM** mindgraph 서비스의 Kafka consumer(`DiaryCreatedConsumer`)가 처리.
- pjacoco OTel scope weave 수정 후 mindgraph(consumer JVM)의 per-trace 커버리지 `classCount` **0→14**, `DiaryCreatedConsumer`가 **producer가 시작한 traceId에 귀속**됨.
- consumer 스레드가 producer와 **동일 traceId를 관측**한다는 것은 OTel javaagent가 Kafka record header로 trace context를 자동 전파했음을 의미 = GA-2 PASS의 cross-JVM 실측 증거.

**거짓이었다면:** Kafka 경계 명시 전파(Kafka 인터셉터/헤더, 또는 Brave의 `eventuate-tram-spring-cloud-sleuth-tram-starter` 류) 폴백. 증거상 불필요.

**C3b 재확인:** T6 `TaintedSpringDistributedE2E`가 동일 BFF→Kafka→다운스트림 경로를 **testId 귀속(서비스별 리포트)까지** 확장해 GA-2를 재확인한다.

**참고 반례(주의 유지):** Eventuate Tram(Brave)은 자동 전파가 안 돼 전용 starter가 필요했다(legacy-tram R1). "자동 전파"는 OTel/Kafka 조합에서 입증된 것이며, Brave/Tram 경로(T6 legacy-tram)는 별도 trace 생존이 R1에서 이미 입증됨.
