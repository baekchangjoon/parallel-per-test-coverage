# Trace Context per-test Coverage — C1 Implementation Plan (in-process consume + async)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **출처:** design spec `docs/superpowers/specs/2026-06-19-trace-context-per-test-coverage-design.md`, 요구사항명세 `docs/superpowers/requirements/2026-06-19-trace-context-per-test-coverage-requirements.md`.
> **분해:** 이 plan은 **C1(in-process 소비 + async 전파)** 만 다룬다. C2(매핑)·C3(분산)는 별도 plan(`-c2.md`, `-c3.md`)으로, C1 green 이후 작성한다. 각 phase는 독립적으로 동작·테스트 가능한 소프트웨어를 산출한다.

**Goal:** 한 서비스 안에서 트레이서(OTel/Brave)가 전파하는 trace context를 coverage key로 소비해, 동기 + async 핸드오프 커버리지를 같은 test로 귀속한다(트레이서 부재 시 현행 폴백 무회귀).

**Architecture:** 핫패스(`CoverageBridge.recordCoverage`)와 store 구조는 무변경. 신규 `TestIdSource`(현재 스레드의 coverage key resolve, reflective/optional)와 `TraceScopeBridge`(트레이서 scope 수명주기에 훅을 걸어 `CoverageContext`를 trace에 동기화)를 추가한다. 트레이서 모드에서 traceId-키 store를 자동 생성하고, inbound advice는 통합 resolver로 라우팅한다.

**Tech Stack:** Java 8, ByteBuddy(현 agent와 동일), JUnit5(agent 모듈 단위/통합), OTel javaagent + extension SPI, Brave/Spring Cloud Sleuth(legacy-tram), Gradle.

## Global Constraints

- 핫패스 `CoverageBridge.recordCoverage`는 `CoverageContext`(ThreadLocal) **1-read** 유지 — probe당 트레이서 조회 0 (REQ-001).
- 트레이서 **런타임 하드 의존 0**, Java 8 호환. 트레이서 보이는 CL 거주 코드만 `compileOnly` 허용; bootstrap/agent-CL 코드는 reflection (REQ-002, GA-3).
- 모든 신규 경로 best-effort: `catch (Throwable)` swallow, SUT로 throw 금지 (REQ-003).
- testId 슬롯 재사용(rename 없음): 트레이서 모드 key=traceId(raw 노출은 의도된 중간 산출물) (design §5.3).
- coverage-key lookup은 scope enter에서만, display 매핑은 C2 병합 시점 — 둘 다 핫패스 밖.

---

## Phase Gate — GA spikes (구현 시작 전 MUST)

> GA spike는 결정 게이트다. **Task 1~3은 코드 산출이 아니라 사실 확정**이며, 결과가 거짓이면 §design 7 degradation 사다리로 내려가고 해당 task(특히 Task 10)를 재-plan한다(요구사항명세 "범위 확장=역전파" 규칙).

### Task 1: GA-1(OTel) spike — ContextStorageProvider extension 주입 가능성
**REQ-IDs:** (gate for REQ-004, REQ-006)

**Files:**
- Create (spike, 폐기 가능): `spike/src/test/java/io/pjacoco/spike/OtelContextStorageSpike.java`
- Create: `spike/otel-ext/` (최소 OTel javaagent extension: `META-INF/services/io.opentelemetry.context.ContextStorageProvider`)

**목표 질문:** OTel javaagent가 붙은 JVM에서, `ContextStorageProvider` SPI를 extension으로 제공하면 그 `ContextStorage`가 활성 SUT의 trace context current 전환(attach/detach)을 관측할 수 있는가? `-Dotel.javaagent.extensions=<jar>`로 로딩되는가? extension CL에서 pjacoco `CoverageContext`에 도달 가능한가(공유 부모/시스템 CL)?

- [ ] **Step 1: 최소 extension 작성** — `ContextStorageProvider` 구현이 `ContextStorage.get()`을 위임 래핑하고 attach/detach마다 stderr에 traceId를 찍는다.
- [ ] **Step 2: tainted-spring 1개 서비스(또는 최소 Boot+otel-javaagent 앱)에 `-javaagent:opentelemetry-javaagent.jar -Dotel.javaagent.extensions=<spike-ext.jar>`로 기동**, HTTP 요청 1건.
- [ ] **Step 3: 관측 확인** — 요청 스레드 + (executor로 넘긴) async 스레드 양쪽에서 attach 로그에 같은 traceId가 찍히는지 확인.
- [ ] **Step 4: 결정 기록** — `docs/superpowers/plans/ga-spike-results.md`에 PASS/FAIL + 채택 메커니즘(extension SPI vs ByteBuddy weave 폴백) 기록.

**Exit criteria:** extension의 ContextStorage가 attach/detach를 관측 → PASS(1순위 채택). 불가 → FAIL → Task 10을 OTel scope 메서드 ByteBuddy weave로 재설계.

### Task 2: GA-1(Brave) spike — Sleuth `CurrentTraceContext` scope 후킹
**REQ-IDs:** (gate for REQ-005, REQ-006)

**Files:**
- Create (spike): `spike/src/test/java/io/pjacoco/spike/BraveScopeHookSpike.java`

**목표 질문:** Sleuth(legacy-tram, Java8, Sleuth 3.1.9)가 build한 `CurrentTraceContext`의 scope 진입/탈출(`newScope`/`maybeScope` → `Scope.close`)을 ByteBuddy로 weave해 enter/exit 콜백을 받을 수 있는가? (build 후 `addScopeDecorator` 불가가 전제.)

- [ ] **Step 1: ByteBuddy advice를 `brave.propagation.CurrentTraceContext+`의 `newScope(TraceContext)`에 weave**, enter/exit에서 `context.traceIdString()`을 stderr로.
- [ ] **Step 2: legacy-tram reservation 서비스에 spike agent attach**, HTTP 요청 1건 + `@Async`/executor 핸드오프 경로.
- [ ] **Step 3: 관측 확인** — 동기 + async 스레드에서 같은 traceId로 enter/exit 콜백 수신.
- [ ] **Step 4: 결정 기록** — ga-spike-results.md 갱신. PASS → weave 채택. FAIL → `CurrentTraceContextCustomizer`(Spring 통합) 평가, 그것도 불가면 choke-point 폴백(REQ-024)만.

**Exit criteria:** scope 메서드 weave로 enter/exit 콜백 수신 → PASS. 불가 → Task 10 Brave 경로 폴백 재설계.

### Task 3: GA-3 spike — classloader 도달성 + 2-agent 설치 순서
**REQ-IDs:** (gate for REQ-002, REQ-008, REQ-024)

**Files:**
- Create (spike): `spike/src/test/java/io/pjacoco/spike/ClassloaderReachabilitySpike.java`

**목표 질문:** (a) bootstrap/agent-CL의 코드가 thread context CL 경유로 `brave.*`/`io.opentelemetry.*`를 reflective 접근 가능한가? extension CL에서 `CoverageContext`(공유 부모/시스템 CL) 도달 가능한가? (b) pjacoco premain·OTel javaagent·Sleuth auto-config 설치 순서를 choke-point 폴백이 의존 가능한 형태로 보장/감지 가능한가?

- [ ] **Step 1:** bootstrap-CL helper에서 `Thread.currentThread().getContextClassLoader().loadClass("brave.Tracing")` 성공 여부 확인(legacy-tram), 동일하게 otel 클래스(tainted).
- [ ] **Step 2:** `CoverageContext`를 어느 CL에 둘지 결정(현 위치 vs 공유 부모로 승격 필요 여부) 확정.
- [ ] **Step 3:** servlet `service()` 진입 시 `Span.current()`가 이미 set인지(OTel advice가 먼저 실행됐는지) 관측 → 순서 의존 기록.
- [ ] **Step 4: 결정 기록** — ga-spike-results.md에 CL 배치/순서 결론.

**Exit criteria:** reflective 도달 + CL 배치 확정. 불가 → extension 패키징/공유 CL 재설계.

---

## C1 구현 — spike-독립 기반 (Task 4~9, 14)

### Task 4: 핫패스 불변 가드 테스트
**REQ-IDs:** REQ-001

**Files:**
- Test: `agent/src/test/java/io/pjacoco/agent/probe/HotPathInvariantTest.java`

**Interfaces:**
- Consumes: 기존 `CoverageBridge.recordCoverage(Class, long, int)`, `CoverageContext`.
- Produces: 회귀 가드(다른 task가 핫패스에 트레이서 조회를 추가하면 실패).

- [ ] **Step 1: 실패 테스트 작성** — `CoverageContext`에 spy `TestStore`를 set하고 `recordCoverage`가 정확히 1회 `record(...)`를 호출하며, 그 호출 전 `CoverageContext.get()` 외 정적 조회가 없음을 단언. (구현이 이미 1-read이므로 이 테스트는 처음부터 PASS — "가드"로서 미래 회귀를 잡는다. 명시적으로 그렇게 기록.)

```java
@Test
void recordCoverageReadsThreadLocalOnceAndRecordsOnce() {
    TestStore spy = Mockito.spy(new TestStore("k", 0L, null));
    CoverageContext.set(spy);
    try {
        CoverageBridge.setTotalProbeCount("io/pjacoco/agent/probe/WarmupTarget", 4);
        CoverageBridge.recordCoverage(WarmupTarget.class, 123L, 0);
        Mockito.verify(spy, Mockito.times(1)).record(eq(123L), anyString(), eq(0), anyInt());
    } finally {
        CoverageContext.clear();
    }
}
```

- [ ] **Step 2: 실행해 통과 확인** — `./gradlew :agent:test --tests '*HotPathInvariantTest*'` → PASS(가드 의도).
- [ ] **Step 3: 커밋** — `git add agent/src/test/.../HotPathInvariantTest.java && git commit -m "test(agent): hot-path invariant guard (REQ-001)"`

### Task 5: TestIdSource SPI + LocalTestIdSource
**REQ-IDs:** REQ-010 (local fallback 축), 기반

**Files:**
- Create: `agent/src/main/java/io/pjacoco/agent/trace/TestIdSource.java`
- Create: `agent/src/main/java/io/pjacoco/agent/trace/LocalTestIdSource.java`
- Test: `agent/src/test/java/io/pjacoco/agent/trace/TestIdSourceTest.java`

**Interfaces:**
- Produces: `interface TestIdSource { String currentKey(); }` — null이면 "키 없음(폴백)". `LocalTestIdSource`는 현 baggage/ThreadLocal에서 얻은 testId를 반환(없으면 null).

- [ ] **Step 1: 실패 테스트** — `LocalTestIdSource`가 주어진 공급자에서 testId를 반환하고, 없으면 null.

```java
@Test
void localReturnsTestIdFromSupplierElseNull() {
    LocalTestIdSource src = new LocalTestIdSource(() -> "com.x.T#m");
    assertEquals("com.x.T#m", src.currentKey());
    assertNull(new LocalTestIdSource(() -> null).currentKey());
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :agent:test --tests '*TestIdSourceTest*'` → FAIL(클래스 없음).
- [ ] **Step 3: 구현**

```java
package io.pjacoco.agent.trace;
public interface TestIdSource {
    /** @return current coverage key, or null if this source cannot resolve one (fall through). */
    String currentKey();
}
```
```java
package io.pjacoco.agent.trace;
import java.util.function.Supplier;
public final class LocalTestIdSource implements TestIdSource {
    private final Supplier<String> supplier;   // baggage/ThreadLocal-derived testId
    public LocalTestIdSource(Supplier<String> supplier) { this.supplier = supplier; }
    @Override public String currentKey() {
        try { return supplier.get(); } catch (Throwable t) { return null; }
    }
}
```

- [ ] **Step 4: 통과 확인** — `./gradlew :agent:test --tests '*TestIdSourceTest*'` → PASS.
- [ ] **Step 5: 커밋** — `git commit -m "feat(agent): TestIdSource SPI + LocalTestIdSource (REQ-010)"`

### Task 6: OtelTestIdSource (reflective, valid-span only)
**REQ-IDs:** REQ-004, REQ-010(OTel)

**Files:**
- Create: `agent/src/main/java/io/pjacoco/agent/trace/OtelTestIdSource.java`
- Test: `agent/src/test/java/io/pjacoco/agent/trace/OtelTestIdSourceTest.java`

**Interfaces:**
- Consumes: `TestIdSource`. OTel API는 reflective(`Class.forName` via context CL) — `io.opentelemetry.api.trace.Span#current`, `Span#getSpanContext`, `SpanContext#isValid`, `SpanContext#getTraceId`.
- Produces: valid SpanContext일 때 traceId, 아니면 null.

- [ ] **Step 1: 실패 테스트** — reflective 호출을 추상화한 protected seam(`resolveSpanContext()`)을 fake로 주입: invalid면 null, valid면 traceId. (실제 OTel 클래스는 단위에서 없으므로 seam 주입으로 검증.)

```java
@Test
void invalidSpanFallsBackToNull() {
    OtelTestIdSource src = new OtelTestIdSource() {
        @Override protected boolean valid() { return false; }
        @Override protected String traceId() { return "00000000000000000000000000000000"; }
    };
    assertNull(src.currentKey());
}
@Test
void validSpanReturnsTraceId() {
    OtelTestIdSource src = new OtelTestIdSource() {
        @Override protected boolean valid() { return true; }
        @Override protected String traceId() { return "4bf92f3577b34da6a3ce929d0e0e4736"; }
    };
    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", src.currentKey());
}
```

- [ ] **Step 2: 실패 확인** — FAIL(클래스 없음).
- [ ] **Step 3: 구현** — reflective 호출은 protected 메서드로 분리(단위 테스트가 override). 실패 시 null.

```java
package io.pjacoco.agent.trace;
import java.lang.reflect.Method;
public class OtelTestIdSource implements TestIdSource {
    @Override public String currentKey() {
        try { return valid() ? traceId() : null; }
        catch (Throwable t) { return null; }   // best-effort (REQ-003)
    }
    /** reflective seam: Span.current().getSpanContext().isValid() */
    protected boolean valid() throws Exception {
        Object sc = spanContext();
        Method isValid = sc.getClass().getMethod("isValid");
        return Boolean.TRUE.equals(isValid.invoke(sc));
    }
    /** reflective seam: SpanContext.getTraceId() */
    protected String traceId() throws Exception {
        Object sc = spanContext();
        Method getTraceId = sc.getClass().getMethod("getTraceId");
        return (String) getTraceId.invoke(sc);
    }
    private Object spanContext() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Class<?> span = Class.forName("io.opentelemetry.api.trace.Span", false, cl);
        Object current = span.getMethod("current").invoke(null);
        return current.getClass().getMethod("getSpanContext").invoke(current);
    }
}
```

- [ ] **Step 4: 통과 확인** — `./gradlew :agent:test --tests '*OtelTestIdSourceTest*'` → PASS.
- [ ] **Step 5: 커밋** — `git commit -m "feat(agent): OtelTestIdSource reflective, valid-span only (REQ-004, REQ-010)"`

### Task 7: BraveTestIdSource (reflective, null/invalid fallback)
**REQ-IDs:** REQ-005, REQ-010(Brave)

**Files:**
- Create: `agent/src/main/java/io/pjacoco/agent/trace/BraveTestIdSource.java`
- Test: `agent/src/test/java/io/pjacoco/agent/trace/BraveTestIdSourceTest.java`

**Interfaces:**
- Consumes: `TestIdSource`. Brave API reflective — `brave.Tracing#current`, `Tracing#currentTraceContext`, `CurrentTraceContext#get`, `TraceContext#traceIdString`.
- Produces: 유효 TraceContext면 `traceIdString()`, null/invalid이면 null.

- [ ] **Step 1: 실패 테스트** — protected seam(`resolveTraceId()`) override: null이면 null, 값이면 그 값.

```java
@Test
void nullContextFallsBack() {
    assertNull(new BraveTestIdSource() { @Override protected String resolveTraceId() { return null; } }.currentKey());
}
@Test
void validContextReturnsTraceId() {
    assertEquals("abc123", new BraveTestIdSource() { @Override protected String resolveTraceId() { return "abc123"; } }.currentKey());
}
```

- [ ] **Step 2: 실패 확인** — FAIL.
- [ ] **Step 3: 구현**

```java
package io.pjacoco.agent.trace;
import java.lang.reflect.Method;
public class BraveTestIdSource implements TestIdSource {
    @Override public String currentKey() {
        try { String id = resolveTraceId(); return (id == null || id.isEmpty()) ? null : id; }
        catch (Throwable t) { return null; }
    }
    /** reflective seam: Tracing.current().currentTraceContext().get().traceIdString() */
    protected String resolveTraceId() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Class<?> tracing = Class.forName("brave.Tracing", false, cl);
        Object cur = tracing.getMethod("current").invoke(null);
        if (cur == null) return null;
        Object ctc = cur.getClass().getMethod("currentTraceContext").invoke(cur);
        Object ctx = ctc.getClass().getMethod("get").invoke(ctc);
        if (ctx == null) return null;
        return (String) ctx.getClass().getMethod("traceIdString").invoke(ctx);
    }
}
```

- [ ] **Step 4: 통과 확인** — PASS.
- [ ] **Step 5: 커밋** — `git commit -m "feat(agent): BraveTestIdSource reflective, null fallback (REQ-005, REQ-010)"`

### Task 8: 트레이서 모드 store 자동 생성
**REQ-IDs:** REQ-022

**Files:**
- Modify: `agent/src/main/java/io/pjacoco/agent/store/TestStoreRegistry.java`
- Modify: `agent/src/main/java/io/pjacoco/agent/AgentOptions.java` (플래그 `traceKeyAutoCreate`)
- Test: `agent/src/test/java/io/pjacoco/agent/store/TestStoreRegistryTest.java`

**Interfaces:**
- Consumes: 기존 `TestStoreRegistry.active(String)`, `start(...)`.
- Produces: `TestStore forCoverageKey(String key)` — 트레이서 모드에서 미등록 키면 lazily create+register(strict 모드 null 회피).

- [ ] **Step 1: 실패 테스트** — `traceKeyAutoCreate=true`일 때 미등록 key로 `forCoverageKey`가 non-null store를 만들고 등록한다(strict와 무관). `false`면 기존 strict 동작.

```java
@Test
void forCoverageKeyAutoCreatesWhenEnabled() {
    TestStoreRegistry reg = newRegistry(/*autoRegister*/ false, /*traceKeyAutoCreate*/ true);
    TestStore s = reg.forCoverageKey("4bf92f...");
    assertNotNull(s);
    assertSame(s, reg.peek("4bf92f..."));
}
@Test
void forCoverageKeyStrictReturnsNullWhenDisabled() {
    TestStoreRegistry reg = newRegistry(false, false);
    assertNull(reg.forCoverageKey("unknown"));
}
```

- [ ] **Step 2: 실패 확인** — FAIL(메서드 없음).
- [ ] **Step 3: 구현** — `forCoverageKey`가 `traceKeyAutoCreate`이면 `start(key, null, null)` 후 `active(key)`, 아니면 `active(key)`. `enforceCap()`/idle 정리는 REQ-016/018(별도 task)에서 강화 — 여기선 생성 경로만.
- [ ] **Step 4: 통과 확인** — `./gradlew :agent:test --tests '*TestStoreRegistryTest*'` → PASS.
- [ ] **Step 5: 커밋** — `git commit -m "feat(agent): traceKeyAutoCreate store creation for tracer mode (REQ-022)"`

### Task 9: 통합 resolver + 모드 우선순위
**REQ-IDs:** REQ-008

**Files:**
- Create: `agent/src/main/java/io/pjacoco/agent/trace/CoverageKeyResolver.java`
- Modify: `agent/src/main/java/io/pjacoco/agent/inbound/servlet/ServletAdvice.java`
- Test: `agent/src/test/java/io/pjacoco/agent/trace/CoverageKeyResolverTest.java`

**Interfaces:**
- Consumes: `List<TestIdSource>`(우선순위: Otel, Brave, Local).
- Produces: `String resolve()` — 첫 non-null. `ServletAdvice`는 trace 소스가 valid key를 주면 그것으로 바인딩하고 baggage는 폴백 소스로만; exit가 trace-set 컨텍스트를 clear하지 않도록 "내가 set한 경우만 clear" 가드.

- [ ] **Step 1: 실패 테스트** — resolver가 우선순위대로 첫 non-null을 반환; 모두 null이면 null.

```java
@Test
void resolvePrefersFirstNonNull() {
    CoverageKeyResolver r = new CoverageKeyResolver(asList(
        () -> null, () -> "trace-1", () -> "local-2"));
    assertEquals("trace-1", r.resolve());
}
@Test
void resolveNullWhenAllEmpty() {
    assertNull(new CoverageKeyResolver(asList(() -> null, () -> null)).resolve());
}
```

- [ ] **Step 2: 실패 확인** — FAIL.
- [ ] **Step 3: 구현 resolver** + `ServletAdvice.activate`를 resolver 기반으로: valid key면 set하고 "set 했음" 표식, exit는 표식이 있을 때만 clear(trace가 set한 건 건드리지 않음).
- [ ] **Step 4: 통과 확인** — resolver 단위 + 기존 `ServletAdviceTest` 회귀 green.
- [ ] **Step 5: 커밋** — `git commit -m "feat(agent): unified CoverageKeyResolver + mode precedence (REQ-008)"`

---

## C1 구현 — spike-게이트 (Task 10)

### Task 10: TraceScopeBridge (async 핸드오프 전파) — GA-1 결과로 메커니즘 확정
**REQ-IDs:** REQ-006, REQ-009

> ✅ **GA-1/GA-3 spike 결과로 메커니즘 확정(green 입증, `ga-spike-results.md`):**
> - **Brave** = `ThreadLocalCurrentTraceContext`의 `newScope/maybeScope`(ENTER) + `Scope.close()`(EXIT) ByteBuddy weave, scope identity로 페어링, `traceIdString()` 리플렉션 호출.
> - **OTel** = shaded `ContextStorage.attach()`(OTel 2.11.0 bootstrap-loaded) weave. **필수:** 커스텀 `PoolStrategy` + OTel jar `ClassFileLocator.ForJarFile`(shaded supertype 해소), trace-id는 **public shaded `Span`/`SpanContext` 인터페이스**로 호출(concrete `SdkSpan` non-public), `hasSuperType(named(...shaded...ContextStorage))` matcher + OTel 버전별 shaded prefix 핀, `RedefinitionStrategy.RETRANSFORMATION`.
> - **공유 `CoverageContext`는 bootstrap classloader 배치**(GA-3). 2-agent CLI 순서 무관.
> - scope→key(이전 store) 맵/핸들은 **weak/identity**로 누수 방지. 스파이크 코드(`spike/`의 `BraveScope*`, `OtelWeave*`)를 참조 구현으로 활용.

**Files:**
- Create: `agent/src/main/java/io/pjacoco/agent/trace/TraceScopeBridge.java`
- Create: `agent/src/main/java/io/pjacoco/agent/trace/TraceScope.java` (enter가 반환하는 복원 핸들 — 이전 store를 **객체 필드**로 보관)
- Create (OTel): `agent/otel-ext/...ContextStorageProvider` (GA-1 PASS 시)
- Test: `agent/src/test/java/io/pjacoco/agent/trace/TraceScopeBridgeTest.java`

**Interfaces:**
- Consumes: `CoverageKeyResolver`, `TestStoreRegistry.forCoverageKey`, `CoverageContext`.
- Produces: `TraceScope enter(String key)` / `void exit(TraceScope)` — enter는 이전 `CoverageContext` 값을 scope 객체에 저장 후 `forCoverageKey(key)` 바인딩, exit는 그 저장값 복원(스레드 무관).

- [ ] **Step 1: 실패 테스트 (cross-thread close, REQ-009)** — 스레드 A에서 `enter("k1")`로 받은 `TraceScope`를 스레드 B에서 `exit`해도, B가 별도로 `enter("k2")`로 바인딩한 컨텍스트가 오염되지 않음.

```java
@Test
void closeOnOtherThreadDoesNotCorrupt() throws Exception {
    TraceScopeBridge bridge = new TraceScopeBridge(registry, resolver);
    TraceScope a = bridge.enter("k1");                 // thread main
    CountDownLatch done = new CountDownLatch(1);
    Thread b = new Thread(() -> {
        TraceScope b2 = bridge.enter("k2");
        bridge.exit(a);                                 // close A's scope on B
        assertEquals("k2", CoverageContext.get().testId()); // B unaffected
        bridge.exit(b2);
        done.countDown();
    });
    b.start(); done.await();
}
```

- [ ] **Step 2: 실패 확인** — FAIL.
- [ ] **Step 3: 구현** — `TraceScope`는 `final TestStore previous`를 보관. `enter`: `prev=CoverageContext.get(); CoverageContext.set(registry.forCoverageKey(key)); return new TraceScope(prev)`. `exit(scope)`: `CoverageContext.set(scope.previous)`(null이면 clear). 전부 `try/catch(Throwable)` swallow.
- [ ] **Step 4: 주입 배선 (GA-1 채택 메커니즘)** — OTel extension의 ContextStorage wrapper attach/detach → `bridge.enter/exit`; Brave scope weave enter/exit → `bridge.enter/exit`. (spike 결과 메커니즘으로.)
- [ ] **Step 5: 통과 확인** — `./gradlew :agent:test --tests '*TraceScopeBridgeTest*'` → PASS.
- [ ] **Step 6: 커밋** — `git commit -m "feat(agent): TraceScopeBridge async propagation, cross-thread-safe (REQ-006, REQ-009)"`

---

## C1 구현 — 안전·관측·검증 (Task 11~15)

### Task 11: 트레이서 부재 무회귀
**REQ-IDs:** REQ-007

**Files:**
- Test: `agent/src/integrationTest/java/io/pjacoco/agent/it/TracerAbsentFallbackIT.java`

- [ ] **Step 1: 실패 테스트** — 트레이서 클래스가 없는(또는 비활성) 환경에서 기존 servlet baggage 경로로 per-testId 커버리지가 현행과 동일하게 수집됨(resolver가 local로 폴백).
- [ ] **Step 2: 실패 확인** — 신규 resolver 배선 전이면 FAIL/미존재.
- [ ] **Step 3: 구현 보강** — resolver 체인에 trace 소스가 null 반환 시 local 폴백 보장(Task 9 재사용).
- [ ] **Step 4: 통과 확인** + 기존 `ProbeRoutingIT`/`SpecAcceptanceE2E` 회귀 green.
- [ ] **Step 5: 커밋** — `git commit -m "test(agent): tracer-absent fallback regression (REQ-007)"`

### Task 12: best-effort no-throw
**REQ-IDs:** REQ-003

**Files:**
- Test: `agent/src/integrationTest/java/io/pjacoco/agent/it/TraceConsumeFailureIT.java`

- [ ] **Step 1: 실패 테스트** — TestIdSource/scope 훅이 런타임 예외를 던지도록 fault-inject했을 때, 요청 처리 결과(응답)가 정상이고 예외가 전파되지 않으며 `swallowedExceptions`가 증가.
- [ ] **Step 2: 실패 확인.**
- [ ] **Step 3: 구현 점검** — 모든 신규 경로 `catch(Throwable)` swallow + metrics 증가 확인(Task 13 카운터 사용).
- [ ] **Step 4: 통과 확인.**
- [ ] **Step 5: 커밋** — `git commit -m "test(agent): best-effort no-throw on trace path (REQ-003)"`

### Task 13: 관측성 카운터 (C1 범위)
**REQ-IDs:** REQ-019(부분: scopeHookInjectionFailures, fallbackActivations)

**Files:**
- Modify: `agent/src/main/java/io/pjacoco/agent/observability/Metrics.java`
- Test: `agent/src/test/java/io/pjacoco/agent/observability/MetricsTest.java`

- [ ] **Step 1: 실패 테스트** — `scopeHookInjectionFailures`/`fallbackActivations` 카운터 존재·증가. (unmappedTraceIds·evictedInFlightTraces는 C2/C3 task에서.)
- [ ] **Step 2: 실패 확인** — FAIL(필드 없음).
- [ ] **Step 3: 구현** — `public final AtomicLong scopeHookInjectionFailures = new AtomicLong();` 등 추가, 주입 실패 지점(Task 10 배선)·폴백 지점(Task 9/11)에서 increment.
- [ ] **Step 4: 통과 확인.**
- [ ] **Step 5: 커밋** — `git commit -m "feat(agent): trace metrics counters (REQ-019 partial)"`

### Task 14: no-tracer attach + Java8 build guard
**REQ-IDs:** REQ-002

**Files:**
- Test: `agent/src/integrationTest/java/io/pjacoco/agent/it/NoTracerAttachIT.java`
- Modify: `agent/build.gradle.kts` (shaded jar에 brave/otel 런타임 의존 부재 + Java8 타깃 가드)

- [ ] **Step 1: 실패 테스트** — 트레이서 라이브러리 없는 클래스패스에서 agent attach 시 `NoClassDefFoundError` 없이 부팅·동작. + build에서 shaded runtime 의존에 `brave.`/`io.opentelemetry.` 없음을 assert(기존 `ShadedJarContainsApiTest` 패턴 확장).
- [ ] **Step 2: 실패 확인.**
- [ ] **Step 3: 구현** — trace 소스는 전부 reflective/optional이므로 런타임 의존 없음 확인; OTel/Brave는 `compileOnly`로만(어댑터가 컴파일된다면). build 가드 추가.
- [ ] **Step 4: 통과 확인** — `./gradlew :agent:integrationTest --tests '*NoTracerAttachIT*'` + build guard.
- [ ] **Step 5: 커밋** — `git commit -m "test(agent): no-tracer attach + java8/no-hard-dep guard (REQ-002)"`

### Task 15: C1 E2E — 단일 서비스 동기 + async 귀속
**REQ-IDs:** REQ-004, REQ-005, REQ-006

**Files:**
- Test (Brave): `agent/src/e2e.../BraveSingleServiceE2E.java` (legacy-tram reservation 단일 기동)
- Test (OTel): `agent/src/e2e.../OtelSingleServiceE2E.java` (tainted 1개 서비스 + otel javaagent + pjacoco extension)
- Test (async): `AsyncHandoffCoverageE2E` — executor/@Async 경로

- [ ] **Step 1: 실패 E2E 작성(red)** — `@DisplayName("REQ-006: async work attributed to same test")`. 단일 서비스 기동 → 한 traceId로 동기 + executor 핸드오프 코드 실행 → per-trace `.exec`에 동기 클래스 **그리고 async 클래스** 커버리지가 같은 key에 존재. (현재 async=0이라 red.)
- [ ] **Step 2: red 확인** — async 커버리지 0 → FAIL.
- [ ] **Step 3: Task 1~13 통합으로 green 드라이브** — 단위가 다 green이면 E2E가 통과해야 함. 안 되면 systematic-debugging.
- [ ] **Step 4: green 확인** — Brave E2E(REQ-005) + OTel E2E(REQ-004) + async(REQ-006) PASS. 매트릭스 🟡→🟢 갱신.
- [ ] **Step 5: 커밋** — `git commit -m "test(e2e): C1 single-service sync+async attribution (REQ-004,005,006)"`

---

## C1 완료 정의

C1 대상 REQ가 매트릭스에서 🟢:
- Must: REQ-001, 002, 003, 004, 005, 006, 007, 008, 009, 010, 022
- Should: REQ-019(부분; 전체는 C3에서 완성), REQ-024(GA-1 FAIL 시에만 활성 — PASS면 해당 폴백 경로 미발동이나 단위로 검증)

C2(매핑: REQ-011,012,013,014)·C3(분산: REQ-015,016,017,018,023 + REQ-019 잔여)는 별도 plan. C1 green + GA spike 결과 기록(ga-spike-results.md) 후 C2 plan 작성.

> **REQ-024 주의:** GA-1 PASS(scope 브리지 동작) 시 choke-point 폴백은 주 경로가 아니다. 그래도 "주입 비활성" 토글로 단위/통합 검증해 두어 GA-1 회귀 시 안전망을 확인한다. GA-1 FAIL 시 REQ-024가 C1의 주 경로가 되며 Task 10을 재설계한다.
