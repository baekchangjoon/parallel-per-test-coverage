# Micrometer Tracing / B3 지원 (v2.1.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Boot 3/Micrometer/B3 스택에서 pjacoco를 쓸 수 있게 한다 — 인바운드 baggage 헤더 3종 기본 인식, testkit HeaderStyle, JDK 프록시 계측 제외(Boot 3 부팅 결함 수정), Boot 3 E2E 벡터.

**Architecture:** 핫패스·트레이서 경로 무변경. `ServletAdvice`의 폴백 블록만 3-헤더 루프로 확장하고 폴백 키는 `active()`(레지스트리 계약)로, 트레이서 키는 기존 `forCoverageKey()`(auto-create)로 분리. 프록시 제외는 transform의 자기-제외 pre-check 층에 추가. Boot 3 E2E는 JDK 17 toolchain의 독립 Gradle 빌드.

**Tech Stack:** Java 8(agent/testkit), ByteBuddy weave(기존), JUnit5, Spring Boot 3.3 + micrometer-tracing-bridge-brave(E2E만), Gradle.

## Global Constraints

- 출처: design spec `docs/superpowers/specs/2026-08-03-micrometer-b3-baggage-design.md`, 요구사항명세 `docs/superpowers/requirements/2026-08-03-micrometer-b3-baggage-requirements.md` (REQ-MM-001..015)
- 신규 에이전트 옵션 금지. `CoverageBridge.recordCoverage` 핫패스 무변경. agent/testkit Java 8 유지 (REQ-MM-015)
- 전 신규 경로 best-effort(`catch (Throwable)` swallow, SUT로 throw 금지)
- 각 task 완료 시 요구사항명세의 추적 매트릭스 상태(🔴→🟡→🟢)를 갱신한다
- 커밋 메시지 말미: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` + `Claude-Session: https://claude.ai/code/session_01FavJfhdcNAHZKWvmSBVLYZ`
- worktree: `.claude/worktrees/mm-b3` (branch `feat/micrometer-b3-baggage`), `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`

---

### Task 1: MmBaggageHeadersE2E — 외부 루프 E2E 먼저 (red)

**REQ-IDs:** REQ-MM-001, REQ-MM-002, REQ-MM-003, REQ-MM-004 (E2E 측면)

**Files:**
- Create: `agent/src/integrationTest/java/io/pjacoco/agent/it/MmBaggageHeadersE2E.java`
- 참고(패턴 복사원): `agent/src/integrationTest/java/io/pjacoco/agent/it/SpecAcceptanceE2E.java` — **인프로세스** Jetty SUT. `-javaagent`는 자식 JVM이 아니라 **`e2eTest` Gradle 태스크가 테스트 JVM 자체에 부착**한다(agent/build.gradle.kts의 e2eTest jvmArgs, 제어 포트 6310, `@Tag("e2e")` + includeTags 선택). 신규 클래스도 **`@Tag("e2e")`를 달아 같은 e2eTest 태스크·fixture(strict 모드, TargetService)를 공유**한다. `control()`/`app()` 헬퍼 구조를 그대로 따른다. (주의: "자식 JVM + pjacoco.shadedJar" 패턴은 `WildcardIncludesCrashE2E`(:agent:test) 계열로 이 태스크와 무관 — 혼동 금지.)

**Interfaces:**
- Consumes: 기존 `SpecAcceptanceE2E`의 인프로세스 하니스 패턴(`e2eTest` 태스크의 -javaagent + 제어 포트 6310, `POST /test/start|stop`·앱 요청 헬퍼)
- Produces: Task 2가 green으로 만들 E2E. 테스트 메서드명 = 요구사항명세 매트릭스의 것: `fieldHeaderProducesPerTestExec`, `legacyHeaderProducesPerTestExec`, `w3cBaggageUnchanged`, `conflictW3cWins`

- [ ] **Step 1: E2E 작성** — SpecAcceptanceE2E의 기동/헬퍼 구조를 복사해 다음 4 테스트를 작성한다. SUT는 **strict 모드**(옵션 없음 = 기본), `traceKeyAutoCreate` 미설정, 트레이서 없음. 각 테스트: `POST /test/start?testId=<id>` → 헤더 붙여 앱 요청 → `POST /test/stop?testId=<id>` → `<id>.exec` 존재+내용 단언.

```java
package io.pjacoco.agent.it;

// imports는 SpecAcceptanceE2E와 동일 + @DisplayName용 org.junit.jupiter.api.DisplayName

/** MM-E2E-2 (design §4.5): 인바운드 baggage 헤더 3종 — 필드 헤더 2종 신규 인식 + W3C 무회귀 +
 *  우선순위 충돌. SUT: strict 모드, 트레이서 없음(폴백 경로만). */
@Tag("e2e")   // e2eTest 태스크 선택 조건 — SpecAcceptanceE2E와 같은 agent-부착 JVM/fixture 공유
class MmBaggageHeadersE2E {   // 기동/종료/헬퍼는 SpecAcceptanceE2E와 동일 구조로 구성

    @Test @DisplayName("REQ-MM-001: test.id 필드 헤더로 per-test exec 산출")
    void fieldHeaderProducesPerTestExec() throws Exception {
        control("/__coverage__/test/start?testId=T1");
        appWithHeader("test.id", "T1");                        // 헤더명=test.id, 값=T1 (W3C 형식 아님)
        control("/__coverage__/test/stop?testId=T1&result=passed");
        assertTrue(Files.exists(COVERAGE.resolve("T1.exec")), "field header must route coverage to T1");
        assertCovered("T1.exec");                              // TargetService probe가 실제 기록됐는지
    }

    @Test @DisplayName("REQ-MM-002: baggage-test.id legacy 헤더로 per-test exec 산출")
    void legacyHeaderProducesPerTestExec() throws Exception {
        control("/__coverage__/test/start?testId=T2");
        appWithHeader("baggage-test.id", "T2");
        control("/__coverage__/test/stop?testId=T2&result=passed");
        assertTrue(Files.exists(COVERAGE.resolve("T2.exec")));
        assertCovered("T2.exec");
    }

    @Test @DisplayName("REQ-MM-003: W3C baggage 헤더 기존 동작 무회귀")
    void w3cBaggageUnchanged() throws Exception {
        control("/__coverage__/test/start?testId=W1");
        appWithHeader("baggage", "test.id=W1");
        control("/__coverage__/test/stop?testId=W1&result=passed");
        assertTrue(Files.exists(COVERAGE.resolve("W1.exec")));
        assertCovered("W1.exec");
    }

    @Test @DisplayName("REQ-MM-004: 3-way 충돌 시 W3C baggage 승리")
    void conflictW3cWins() throws Exception {
        control("/__coverage__/test/start?testId=WINNER");
        control("/__coverage__/test/start?testId=LOSER1");
        control("/__coverage__/test/start?testId=LOSER2");
        appWithHeaders(new String[][] {                        // 서로 다른 값의 3중 헤더
            {"baggage", "test.id=WINNER"}, {"test.id", "LOSER1"}, {"baggage-test.id", "LOSER2"}});
        control("/__coverage__/test/stop?testId=WINNER&result=passed");
        control("/__coverage__/test/stop?testId=LOSER1&result=passed");
        control("/__coverage__/test/stop?testId=LOSER2&result=passed");
        assertCovered("WINNER.exec");
        assertEmptyOrAbsent("LOSER1.exec");                    // strict라 빈 store는 파일 미생성
        assertEmptyOrAbsent("LOSER2.exec");
    }
}
```
`appWithHeader(name, value)`/`appWithHeaders(pairs)`는 SpecAcceptanceE2E의 `app()` 헬퍼를 헤더 파라미터화한 사본. `assertCovered`는 jacoco `ExecutionDataReader`(SpecAcceptanceE2E의 기존 검증 로직 재사용)로 TargetService probe true 개수>0 단언. `assertEmptyOrAbsent`는 파일 부재 또는 대상 클래스 probe 전부 false 단언.

- [ ] **Step 2: red 확인** — `./gradlew --no-daemon :agent:e2eTest --tests '*MmBaggageHeadersE2E*'` → `fieldHeaderProducesPerTestExec`/`legacyHeaderProducesPerTestExec` FAIL(신규 헤더 미인식 → exec 없음), `w3cBaggageUnchanged` PASS(기존 동작), `conflictW3cWins` PASS(현재도 W3C만 읽으므로). **FAIL 2건이 정확히 신규 요구를 가리키는지 확인.** (integrationTest 태스크는 -javaagent 미부착이라 절대 사용 금지 — 전 테스트가 엉뚱한 이유로 실패한다.)

- [ ] **Step 3: 매트릭스 갱신+커밋** — 요구사항명세 매트릭스에서 REQ-MM-001/002를 🟡 red로. `git add agent/src/integrationTest docs/ && git commit -m "test(mm): MM-E2E-2 baggage header vectors (red for field headers)"`

---

### Task 2: 인바운드 3-헤더 폴백 구현 (ServletAdvice + Metrics)

**REQ-IDs:** REQ-MM-001, REQ-MM-002, REQ-MM-003, REQ-MM-004, REQ-MM-005, REQ-MM-007

**Files:**
- Modify: `agent/src/main/java/io/pjacoco/agent/inbound/servlet/ServletAdvice.java:74-81` (**폴백 블록만** — 83-87의 store 생성부는 Task 3 몫, 88-102의 missing-id 경고 블록은 무변경)
- Modify: `agent/src/main/java/io/pjacoco/agent/observability/Metrics.java` (카운터 3종 + summary)
- Test: `agent/src/test/java/io/pjacoco/agent/inbound/servlet/BaggageHeadersTest.java` (신규)

**Interfaces:**
- Consumes: `BaggageParser.testId(String)`(기존, W3C 전용 유지), `TestStoreRegistry.active(String)`(기존)
- Produces: `ServletAdvice.fallbackTestId(Object request)` — package-private static, 테스트 시임. Metrics 신규 필드명: `testIdFromW3cBaggage`/`testIdFromFieldHeader`/`testIdFromLegacyFieldHeader`

- [ ] **Step 1: 단위 테스트 작성 (red)** — `BaggageHeadersTest`: 기존 `ServletAdviceTest` 관례대로 Mockito mock(HttpServletRequest)으로 다음을 단언:

```java
package io.pjacoco.agent.inbound.servlet;
// junit + Metrics/TestStoreRegistry 픽스처는 기존 ServletAdvice 테스트(있다면) 패턴 준용

/** REQ-MM-004/005/007 단위 계약. 스텁 request는 Map<String,String> 기반 getHeader(String) 제공. */
class BaggageHeadersTest {
    // 기존 ServletAdviceTest 관례를 따라 Mockito mock(HttpServletRequest) + when(getHeader) 사용.
    private static String resolve(java.util.Map<String, String> headers) throws Exception {
        javax.servlet.http.HttpServletRequest req = org.mockito.Mockito.mock(javax.servlet.http.HttpServletRequest.class);
        for (java.util.Map.Entry<String, String> e : headers.entrySet())
            org.mockito.Mockito.when(req.getHeader(e.getKey())).thenReturn(e.getValue());
        java.lang.reflect.Method m = ServletAdvice.class.getDeclaredMethod("fallbackTestId", Object.class);
        m.setAccessible(true);
        return (String) m.invoke(null, req);
    }

    @Test void priority3Way() throws Exception {          // REQ-MM-004
        assertEquals("W", resolve(map("baggage","test.id=W","test.id","F","baggage-test.id","L")));
    }
    @Test void pairwiseAll() throws Exception {           // REQ-MM-004 pairwise 전수
        assertEquals("W", resolve(map("baggage","test.id=W","test.id","F")));
        assertEquals("F", resolve(map("test.id","F","baggage-test.id","L")));
        assertEquals("W", resolve(map("baggage","test.id=W","baggage-test.id","L")));
    }
    @Test void malformedBaggageFallsThrough() throws Exception {   // REQ-MM-004: 유효 값 추출 기준
        assertEquals("F", resolve(map("baggage","other=x","test.id","F")));
    }
    @Test void w3cParserOnlyForBaggageHeader() throws Exception {  // REQ-MM-003
        assertEquals("a=b", resolve(map("test.id"," a=b ")));      // trim + 값 전체
    }
    @Test void emptyValueFallsThrough() throws Exception {         // REQ-MM-005
        assertEquals("T3", resolve(map("test.id","  ","baggage-test.id","T3")));
    }
    @Test void allEmptyGoesMissingPath() throws Exception {        // REQ-MM-005
        assertNull(resolve(map("test.id","","baggage-test.id","  ")));
    }
    @Test void countersPartitionFallbackActivations() { /* Metrics 주입 후 activate() 3회 —
        각 헤더 유형 1회씩 → testIdFromW3cBaggage=1, testIdFromFieldHeader=1,
        testIdFromLegacyFieldHeader=1, fallbackActivations=3 단언. ServletAdvice.registry/metrics
        static 주입은 기존 테스트 관례(각주: ServletAdvice 필드가 public volatile) */ }
    @Test void countersAppearInSummary() {                         // REQ-MM-007
        String s = new io.pjacoco.agent.observability.Metrics().summary();
        assertTrue(s.contains("testIdFromW3cBaggage=") && s.contains("testIdFromFieldHeader=")
                && s.contains("testIdFromLegacyFieldHeader="));
    }
    private static java.util.Map<String,String> map(String... kv) {
        java.util.Map<String,String> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i+1]);
        return m;
    }
}
```

- [ ] **Step 2: red 확인** — `./gradlew :agent:test --tests '*BaggageHeadersTest*'` → 컴파일 실패(`fallbackTestId` 부재)가 첫 red.

- [ ] **Step 3: 구현** — Metrics에 카운터 3종 추가(+summary에 `testIdFromW3cBaggage=` 등 3항 추가, 기존 필드 전수-노출 관례). ServletAdvice 폴백 블록 교체:

```java
    /** 폴백 헤더 순서(REQ-MM-004): W3C baggage → Brave/Micrometer 필드 → legacy Sleuth. */
    static String fallbackTestId(Object request) {
        String w3c = BaggageParser.testId(header(request, "baggage"));
        if (w3c != null) { bump(m -> m.testIdFromW3cBaggage); return w3c; }
        String field = trimToNull(header(request, "test.id"));
        if (field != null) { bump(m -> m.testIdFromFieldHeader); return field; }
        String legacy = trimToNull(header(request, "baggage-test.id"));
        if (legacy != null) { bump(m -> m.testIdFromLegacyFieldHeader); return legacy; }
        return null;
    }
    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;                    // REQ-MM-005: 빈 값은 매치 아님
    }
```
Java 8이므로 `bump(...)` 의사표기는 각 분기에서 **서로 다른 필드**를 직접 증가시켜 푼다:
w3c 분기 → `m.testIdFromW3cBaggage`, field 분기 → `m.testIdFromFieldHeader`, legacy 분기 →
`m.testIdFromLegacyFieldHeader` (셋 다 `Metrics m = metrics; if (m != null) ...` 형태).
`activate()`의 폴백 부분(74-81행) 교체 코드:

```java
            // If no tracer context is active, fall back to the baggage headers (REQ-007 + REQ-MM-004).
            if (key == null) {
                String local = fallbackTestId(request);
                if (local != null) {
                    key = local;
                    Metrics m = metrics;
                    if (m != null) m.fallbackActivations.incrementAndGet();  // REQ-019 불변식: 3종 공통
                }
            }
```
store 생성부(83-87행)는 이 task에서 **건드리지 않는다**(기존 `forCoverageKey` 유지) — Task 3에서 분리 적용(작은 diff 유지).

- [ ] **Step 4: green 확인** — `./gradlew :agent:test --tests '*BaggageHeadersTest*'` 전부 PASS + Task 1 E2E 재실행 → 4/4 PASS.
- [ ] **Step 5: 매트릭스 REQ-MM-001~005·007 🟢 갱신 + 커밋** — `feat(agent): recognize Brave/Micrometer baggage field headers (3-header fallback)`

---

### Task 3: 폴백 키 store 생성 규칙 (auto-create 제외)

**REQ-IDs:** REQ-MM-006

**Files:**
- Modify: `agent/src/main/java/io/pjacoco/agent/inbound/servlet/ServletAdvice.java` (activate의 폴백 분기만)
- Test: `agent/src/test/java/io/pjacoco/agent/inbound/servlet/StoreCreationRuleIT.java` (신규)

**Interfaces:**
- Consumes: `TestStoreRegistry.active(String)`(strict/lenient 계약), `forCoverageKey(String)`(tracer 전용 유지), `TraceScopeBridge`(경로 (b) 검증)
- Produces: 없음(내부 규칙)

- [ ] **Step 1: IT 작성 (red)** — `traceKeyAutoCreate=true` 레지스트리로:
  - `baggageKeySkipsAutoCreate`: 스텁 request `test.id: GHOST`(미시작) → `activate()` 후 `registry.active("GHOST")==null` && store 미생성 단언 (현재 구현은 forCoverageKey라 auto-create됨 → red)
  - `servletTracerKeyAutoCreates`: `traceSources`를 고정 키 반환 스텁으로 교체 → activate 후 store 존재 (green 유지 확인)
  - `scopeBridgeKeyAutoCreates`: `TraceScopeBridge.onScopeEnter` 경로로 키 진입 → store 존재 (기존 동작 보존)
- [ ] **Step 2: red 확인** — `baggageKeySkipsAutoCreate`만 FAIL이어야 한다.
- [ ] **Step 3: 구현** — activate()의 폴백 성공 분기에서 `reg.forCoverageKey(key)` 대신 `reg.active(key)` 호출(트레이서 분기는 기존 유지). `forCoverageKey` 시그니처 변경 금지.
- [ ] **Step 4: green + 전 스위트** — `./gradlew :agent:test :agent:integrationTest` green (S5 계열 기존 테스트 무회귀 확인 포함).
- [ ] **Step 5: 매트릭스 REQ-MM-006 🟢 + 커밋** — `feat(agent): baggage-derived keys follow registry contract (no auto-create)`

---

### Task 4: JDK 프록시 무조건 계측 제외

**REQ-IDs:** REQ-MM-010

**Files:**
- Modify: `agent/src/main/java/io/pjacoco/agent/probe/ProbeInstrumentation.java:140-144` (자기-제외 블록)
- Test: `agent/src/test/java/io/pjacoco/agent/probe/ProxyExclusionTest.java` (신규)

**Interfaces:**
- Consumes: `ProbeInstrumentation.JacocoTransformer`(기존 테스트 `JacocoTransformerBootstrapTest`의 생성 패턴 재사용 — instrumenter+options+metrics+log)
- Produces: 없음

- [ ] **Step 1: 단위 테스트 작성 (red)** — transform-층 관측(REQ-MM-010 확정 지점):

```java
/** 발견 방법 인코딩: spike에서 Boot 3가 jdk.proxy3 계측의 $jacocoInit IllegalAccessError로 부팅
 *  실패했다. instrumentFailures로는 안 잡히는 부류(계측 성공, 런타임 폭발)라 transform 반환값을
 *  직접 단언한다. */
@Test void transformReturnsNullForJdkProxy() {
    assertNull(transformer("includes=*").transform(cl, "jdk/proxy3/$Proxy42", null, null, classBytes()));
    assertNull(transformer("includes=*").transform(cl, "com/sun/proxy/$Proxy7", null, null, classBytes()));
}
@Test void explicitIncludesCannotReenable() {            // excludes는 집합을 못 넓히므로 includes로 공격
    assertNull(transformer("includes=jdk.proxy*").transform(cl, "jdk/proxy3/$Proxy42", null, null, classBytes()));
}
```
(`transformer(opts)`/`classBytes()`는 `JacocoTransformerBootstrapTest`의 기존 헬퍼 구성을 복사. `cl`은 앱 클래스로더 — platform-loader 체크를 통과시켜 pre-check 자체를 검증.)
- [ ] **Step 2: red 확인** — 두 테스트 FAIL(현재는 계측 바이트 반환).
- [ ] **Step 3: 구현** — 자기-제외 블록에 프록시 prefix 추가:

```java
            if (dotted.startsWith("io.pjacoco.") || dotted.startsWith("org.jacoco.")
                    || dotted.startsWith("net.bytebuddy.") || dotted.startsWith("org.objectweb.asm.")
                    // JDK dynamic proxies: generated code in dynamic modules; instrumenting them
                    // injects a $jacocoInit that crosses JPMS read edges -> IllegalAccessError at
                    // boot (Boot 3, discovered by the 2026-08-03 spike). Unconditional, like the
                    // self-excludes above — user includes=/excludes= cannot re-enable it.
                    || dotted.startsWith("jdk.proxy") || dotted.startsWith("com.sun.proxy.")) {
                return null;
            }
```
- [ ] **Step 4: green + 커밋** — 매트릭스 REQ-MM-010 🟢. `fix(agent): never instrument JDK dynamic proxies (Boot 3 boot crash)`

---

### Task 5: testkit-core HeaderStyle + 필드 헤더 접근자

**REQ-IDs:** REQ-MM-008 (core 절반)

**Files:**
- Create: `testkit-core/src/main/java/io/pjacoco/testkit/HeaderStyle.java`
- Modify: `testkit-core/src/main/java/io/pjacoco/testkit/Pjacoco.java` (접근자 2개 추가)
- Test: `testkit-core/src/test/java/io/pjacoco/testkit/HeaderStyleCoreTest.java` (신규)

**Interfaces:**
- Produces: `public enum HeaderStyle { W3C_BAGGAGE, FIELD, BOTH }` / `Pjacoco.fieldHeaderName()` → `"test.id"` / `Pjacoco.fieldHeaderValue()` → 현재 testId 그대로(없으면 null). Task 6이 소비.

- [ ] **Step 1: 테스트 작성 (red)**

```java
class HeaderStyleCoreTest {
    @AfterEach void clear() { Pjacoco.clearCurrentTestId(); }
    @Test void fieldHeaderAccessors() {
        Pjacoco.setCurrentTestId("T1#m");
        assertEquals("test.id", Pjacoco.fieldHeaderName());
        assertEquals("T1#m", Pjacoco.fieldHeaderValue());     // raw — 인코딩 없음(헤더 값으로 합법)
    }
    @Test void fieldHeaderValueNullWhenNoActiveTest() {
        assertNull(Pjacoco.fieldHeaderValue());
    }
    @Test void headerStyleHasThreeValues() {
        assertEquals(3, HeaderStyle.values().length);          // W3C_BAGGAGE, FIELD, BOTH
    }
}
```
- [ ] **Step 2: red → 구현 → green** — `HeaderStyle`(javadoc: 각 값의 wire 형식과 사용 시나리오 — design §4.2/§4.3 표 요약), `Pjacoco`에:

```java
    /** Brave/Micrometer 필드-헤더 규약의 헤더명 (Brave BaggagePropagation remote field). */
    public static String fieldHeaderName() { return "test.id"; }
    /** @return 현재 testId 그대로(필드 헤더 값), 활성 테스트가 없으면 null. */
    public static String fieldHeaderValue() { return currentTestId(); }
```
- [ ] **Step 3: 커밋** — `feat(testkit): HeaderStyle enum + field-header accessors`

---

### Task 6: PjacocoRestAssured — enable/baggageFilter HeaderStyle 오버로드

**REQ-IDs:** REQ-MM-008, REQ-MM-009

**Files:**
- Modify: `testkit-restassured/src/main/java/io/pjacoco/testkit/restassured/PjacocoRestAssured.java`
- Test: `testkit-restassured/src/test/java/io/pjacoco/testkit/restassured/HeaderStyleTest.java` (신규 — 기존 테스트 파일 관례 확인 후 배치)

**Interfaces:**
- Consumes: Task 5의 `HeaderStyle`/`fieldHeaderName()`/`fieldHeaderValue()`
- Produces: `enable(HeaderStyle)`, `baggageFilter(HeaderStyle)`; 기존 `enable()`/`baggageFilter()`는 W3C 위임 유지

- [ ] **Step 1: 테스트 작성 (red)** — **기존 `PjacocoRestAssuredTest`의 loopback `HttpServer` 패턴을 재사용**(Mockito 의존 추가 금지 — 이 모듈에 없음): 127.0.0.1 임시 포트 HttpServer가 수신 헤더를 기록하고, RestAssured로 실제 요청을 보내 서버가 받은 헤더를 단언한다:

```java
class HeaderStyleTest {   // 픽스처: PjacocoRestAssuredTest와 동일 — HttpServer 기동, 수신 헤더 Map 캡처
    @AfterEach void clear() { Pjacoco.clearCurrentTestId(); }
    @Test void fieldEmitsFieldHeaderOnly() { /* setCurrentTestId("T1") →
        given().filter(PjacocoRestAssured.baggageFilter(HeaderStyle.FIELD)).get(url) →
        captured.get("Test.id")=="T1" && !captured.containsKey("Baggage") (HttpServer 헤더는 대소문자 정규화 주의 — 기존 테스트의 조회 방식 준용) */ }
    @Test void bothEmitsBoth() { /* baggageFilter(HeaderStyle.BOTH) → 두 헤더 모두 수신 단언 */ }
    @Test void baggageFilterStyleOverload() { /* baggageFilter(HeaderStyle.W3C_BAGGAGE) → baggage만 수신 */ }
    @Test void noArgEnableKeepsW3c() { /* REQ-MM-009: baggageFilter() no-arg → baggage만 수신(기존 동일).
        기존 시그니처 보존은 컴파일 자체가 증명 */ }
}
```
- [ ] **Step 2: red → 구현 → green** —

```java
    public static Filter baggageFilter() { return baggageFilter(HeaderStyle.W3C_BAGGAGE); }
    public static Filter baggageFilter(HeaderStyle style) { return new BaggageFilter(style); }
    public static void enable() { enable(HeaderStyle.W3C_BAGGAGE); }
    public static void enable(HeaderStyle style) { RestAssured.filters(baggageFilter(style)); }

    static final class BaggageFilter implements Filter {
        private final HeaderStyle style;
        BaggageFilter(HeaderStyle style) { this.style = style; }
        @Override public Response filter(FilterableRequestSpecification requestSpec,
                FilterableResponseSpecification responseSpec, FilterContext ctx) {
            if (style != HeaderStyle.FIELD) {
                String baggage = Pjacoco.baggageHeaderValue();
                if (baggage != null) requestSpec.header("baggage", baggage);
            }
            if (style != HeaderStyle.W3C_BAGGAGE) {
                String value = Pjacoco.fieldHeaderValue();
                if (value != null) requestSpec.header(Pjacoco.fieldHeaderName(), value);
            }
            return ctx.next(requestSpec, responseSpec);
        }
    }
```
- [ ] **Step 3: `./gradlew :testkit-core:test :testkit-restassured:test` green → 매트릭스 REQ-MM-008/009 🟢 → 커밋** — `feat(testkit): HeaderStyle overloads for enable()/baggageFilter()`

---

### Task 7: e2e-mm-boot3 독립 빌드 + MmBoot3BootE2E (red→green은 Task 4가 이미 공급)

**REQ-IDs:** REQ-MM-011

**Files:**
- Create: `e2e-mm-boot3/settings.gradle.kts`, `e2e-mm-boot3/build.gradle.kts`, `e2e-mm-boot3/src/test/java/io/pjacoco/e2emm/MmBoot3BootE2E.java`
- 기존: `e2e-mm-boot3/app/` — **spike 앱 소스는 이미 저장소에 커밋돼 있음**(SpikeApplication/SpikeController/SyncWorker/AsyncWorker/DownstreamWorker + application.properties + pom.xml). Maven pom은 참고용 — Gradle 빌드로 이식하며 main sourceSet 경로를 `app/src/main`으로 지정하거나 소스를 이동한다.
- 참고: `spike/build.gradle.kts`(toolchain 17 독립 빌드 선례), spike 결과 문서의 앱 구성

**Interfaces:**
- Consumes: agent shaded jar — 빌드 시 `../agent/build/libs/pjacoco-agent.jar` 경로를 시스템 프로퍼티 `pjacoco.agentJar`로 주입(빌드 스크립트에서 `../gradlew :agent:shadowJar` 산출물 참조; CI 잡이 선행 빌드)
- Produces: Task 8·9가 같은 빌드/앱을 사용. 앱 endpoints: `GET /sync`(직선 코드 서비스 호출), `GET /async`(완료 대기 후 응답, AsyncWorker.work는 **분기 없는 직선 코드로 재작성** — REQ-MM-012의 결정론 단언 전제)

- [ ] **Step 1: 독립 빌드 스캐폴드** — `settings.gradle.kts`(`rootProject.name = "e2e-mm-boot3"`), `build.gradle.kts`: java toolchain 17, Boot 3.3.x BOM + starter-web/actuator/**starter-aop**(spike pom과 동일 — @Async JDK 프록시 재현의 필수 의존) + micrometer-tracing-bridge-brave, `bootJar` + test(JUnit5). 루트 빌드에 포함하지 **않는다**(독립 — JDK 11 CI 레그 보호).
- [ ] **Step 2: 앱 소스 정리** — 커밋된 `e2e-mm-boot3/app` 소스를 Gradle main sourceSet으로 연결. `AsyncWorker.work(int)`를 분기 없는 직선 코드로 단순화(ternary·if 제거 — 커버 단언 결정론). `SpikeController`의 `/call-downstream`이 **자기 자신(local.server.port)이 아니라 설정 가능한 대상**을 호출하도록 `downstream.base-url` 프로퍼티를 추가(기본=self — Task 9의 2-hop이 B 인스턴스 URL 주입).
- [ ] **Step 3: MmBoot3BootE2E 작성** — 발견-방법 인코딩:

```java
/** REQ-MM-011: spike에서 includes=* 기본값이 jdk.proxy3 계측 → $jacocoInit IllegalAccessError로
 *  부팅 실패했다. 기본값 그대로 부팅이 성공해야 한다(Task 4의 프록시 제외가 공급). */
@Test @DisplayName("REQ-MM-011: Boot 3 boots with default includes=*")
void bootsWithDefaultIncludes() throws Exception {
    Process app = new ProcessBuilder(javaBin(), "-javaagent:" + agentJar(),  // 옵션 없음 = includes=* 기본
            "-jar", bootJar(), "--server.port=0").redirectErrorStream(true).redirectOutput(log).start();
    try {
        int port = awaitPortFromLog(log, 60);              // "Tomcat started on port" 파싱, 60s 한도
        assertEquals(200, get("http://127.0.0.1:" + port + "/sync"));
        assertFalse(logContains("IllegalAccessError"), "proxy instrumentation crash must not occur");
    } finally { app.destroyForcibly(); app.waitFor(10, TimeUnit.SECONDS); }   // 모든 경로 teardown
}
```
- [ ] **Step 4: 실행 확인** — **선행: `./gradlew --no-daemon :agent:shadowJar`**(agent jar가 없으면 실패한다) 후 `(cd e2e-mm-boot3 && ../gradlew --no-daemon test)` → Task 4가 이미 머지된 브랜치이므로 PASS 기대. **Task 4 이전 커밋으로 되돌려 red였을 검증이 필요하면 `git stash`로 프록시 수정만 잠시 제거해 FAIL 확인 후 복원**(발견-방법 보존).
- [ ] **Step 5: 매트릭스 REQ-MM-011 🟢 + 커밋** — `test(e2e): standalone Boot 3 build + boot-success E2E`

---

### Task 8: MmTracerPathE2E (동기 + async)

**REQ-IDs:** REQ-MM-012 (CI 기준 제외한 2개 수용기준)

**Files:**
- Create: `e2e-mm-boot3/src/test/java/io/pjacoco/e2emm/MmTracerPathE2E.java`

**Interfaces:**
- Consumes: Task 7의 앱/빌드. agent 옵션 `traceKeyAutoCreate=true,destdir=<tmp>,port=0`
- Produces: 없음

- [ ] **Step 1: E2E 작성** — spike 검증 절차의 테스트화:
  - `b3TraceIdKeyedStore`: 고정 `b3: 80f198ee56343ba864fe8b2a57d3eff7-e457b5a2e4d86bd1-1` 헤더로 `/sync` → SIGTERM flush 후 `80f198ee...exec` 존재 + 서비스 클래스 probe 커버 단언(jacoco ExecutionDataReader).
  - `asyncAttributedToSameStore`: `/async` 요청(응답이 async 완료 후) → 그 요청 traceId의 exec에 **AsyncWorker.work의 실행 라인 probe 전부** 존재(직선 코드라 결정론 — 생성자 probe는 단언 제외, 주석으로 spike 라인 매핑 근거 인용).
- [ ] **Step 2: 실행 green 확인**(spike로 검증된 경로 — 즉시 green 기대; red가 나오면 systematic-debugging으로 회귀 조사) → 매트릭스 REQ-MM-012 부분 🟡(CI 기준 남음) → 커밋 `test(e2e): tracer-path sync+async vectors on Boot 3 + bridge-brave`

---

### Task 9: MmDistributedFieldE2E (2-hop, Docker 게이트)

**REQ-IDs:** REQ-MM-013, REQ-MM-008(E2E 교차)

**Files:**
- Create: `e2e-mm-boot3/src/test/java/io/pjacoco/e2emm/MmDistributedFieldE2E.java`
- Modify: `e2e-mm-boot3/build.gradle.kts` — testkit jar를 **버전 리터럴 없이 glob으로** 참조(+ restassured는 testkit에서 compileOnly라 **직접 의존 선언 필수**):

```kotlin
dependencies {
    // 형제 빌드 산출물 — 버전 무관 glob, sources/javadoc 제외. 경로는 projectDirectory 기준.
    testImplementation(files(layout.projectDirectory.dir("../testkit-core/build/libs").asFileTree.matching {
        include("testkit-core-*.jar"); exclude("*-sources.jar", "*-javadoc.jar") }))
    testImplementation(files(layout.projectDirectory.dir("../testkit-restassured/build/libs").asFileTree.matching {
        include("testkit-restassured-*.jar"); exclude("*-sources.jar", "*-javadoc.jar") }))
    testImplementation("io.rest-assured:rest-assured:5.4.0")   // testkit-restassured의 compileOnly 의존을 직접 공급
}
```
(선행 빌드: `./gradlew :testkit-core:jar :testkit-restassured:jar` — Step 실행 명령과 CI 잡에 포함.)

**Interfaces:**
- Consumes: Task 6의 `HeaderStyle.FIELD`(하니스 측), Task 7 앱(2 인스턴스: A가 B를 호출하는 `/call-downstream` 엔드포인트 — spike의 sink 패턴 승격, `remote-fields=test.id` 설정)
- Produces: 없음

- [ ] **Step 1: E2E 작성** — **자식 JVM 2개**(A·B 인스턴스, Docker 불요 — 요구사항명세도 이 결정으로 이미 정정됨). teardown은 finally에서 PID destroy + 잔존 0 단언:

```java
@Test @DisplayName("REQ-MM-013: 2-hop FIELD 스타일 — 양 서비스 exec가 같은 testId로 수집·병합")
void twoHopSameTestId() throws Exception { /* 본문은 아래 절차 */ }
```
  - 하니스: `POST A:/test/start?testId=D1` → `FIELD` 스타일 헤더(`test.id: D1`)로 `A:/call-downstream` → A가 자체 전파(B3+baggage field)로 B 호출 → 양쪽 stop/flush.
  - 단언: `A-out/D1.exec`와 `B-out/D1.exec` 존재 + `jacococli merge`(또는 ExecutionDataStore 병합) 후 **A에만 있는 클래스와 B에만 있는 클래스가 모두** 병합 결과에 존재.
  - finally: 두 자식 JVM PID destroy + `waitFor` — 잔존 프로세스 0 확인 단언 포함.
- [ ] **Step 2: green → 매트릭스 REQ-MM-013 🟢(및 REQ-MM-008 E2E 교차 표기) → 커밋** — `test(e2e): 2-hop FIELD-style distributed collection`
- 참고: 요구사항명세·design spec의 Docker-게이트 문구는 이 plan 확정 시점에 이미 프로세스-기반으로 역전파 정정 완료(리뷰 반영) — Task 9에서 추가 정정 불필요.

---

### Task 10: CI 배선 (전용 JDK 17 잡)

**REQ-IDs:** REQ-MM-011, REQ-MM-012, REQ-MM-013 (CI 수용기준)

**Files:**
- Modify: `.github/workflows/ci.yml` (신규 job `mm-e2e`)

- [ ] **Step 1: job 추가**

```yaml
  mm-e2e:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "17", cache: gradle }
      - name: Build agent + testkits, then run Boot 3 E2E (standalone JDK-17 build)
        run: |
          ./gradlew --no-daemon :agent:shadowJar :testkit-core:jar :testkit-restassured:jar
          cd e2e-mm-boot3 && ../gradlew --no-daemon test
```
- [ ] **Step 2: actionlint + 커밋** — `ci: dedicated JDK 17 job for the Boot 3 MM e2e build`. 매트릭스의 (공통) CI 배선 행은 PR 리뷰 게이트에서 🟢 처리.

---

### Task 11: 표면 불변 자동 가드

**REQ-IDs:** REQ-MM-015

**Files:**
- Create: `agent/src/test/java/io/pjacoco/agent/KnownKeysSnapshotTest.java`, `agent/src/test/java/io/pjacoco/agent/probe/HotPathGuardTest.java`

- [ ] **Step 1: 테스트 작성** —

```java
/** REQ-MM-015(a): 이번 사이클 계약 — 신규 에이전트 옵션 0. 옵션을 추가하려면 이 스냅숏과
 *  요구사항명세를 함께 갱신해야 한다(무단 표면 확장 방지 가드). */
@Test void knownKeysSetIsFrozenForThisCycle() {
    AgentOptions o = AgentOptions.parse("");   // KNOWN_KEYS 접근자가 없으므로 parseWarnings 우회 검증:
    // 23개 기존 키 전부 warning 없이 파싱됨 + 임의 신규 키는 warning — 집합 동일성의 관측 가능 형태
    assertTrue(AgentOptions.parse("destdir=/x,control=false").parseWarnings().isEmpty());
    assertEquals(1, AgentOptions.parse("someNewOption=1").parseWarnings().size());
}
/** REQ-MM-015(b): recordCoverage 핫패스 무변경 — 메서드 본문만 해시(파일 전체 아님: 무관한
 *  javadoc/다른 메서드 수정에 오탐하지 않도록). CoverageBridge.java의 recordCoverage 메서드를
 *  `// HOT-PATH-BEGIN` / `// HOT-PATH-END` 마커로 감싸고(주석 추가는 핫패스 바이트코드 무영향)
 *  마커 사이 텍스트의 SHA-256을 고정한다. 변경 시 이 테스트와 요구사항명세(성능 계약)를 함께
 *  갱신할 것. */
@Test void recordCoverageSourceUnchanged() throws Exception {
    String src = new String(Files.readAllBytes(
            Paths.get("src/main/java/io/pjacoco/agent/probe/CoverageBridge.java")), StandardCharsets.UTF_8);
    String body = src.substring(src.indexOf("// HOT-PATH-BEGIN"), src.indexOf("// HOT-PATH-END"));
    String sha = /* MessageDigest SHA-256 of body, hex */;
    assertEquals("<구현 시 마커 추가 후 실제 해시로 고정>", sha);
}
```
(주의: 해시 고정은 이 브랜치의 최종 CoverageBridge 기준으로 구현 마지막에 채운다. `AgentOptionsParseWarningsTest#allKnownKeysParseWithoutWarnings`가 이미 존재하므로 (a)는 그 테스트에 "신규 키 경고" 케이스 추가로 갈음해도 된다 — 구현자가 중복 없는 쪽 선택.)
- [ ] **Step 2: green + 매트릭스 REQ-MM-015 🟢 + 커밋** — `test(agent): surface-invariant guards (options set, hot path)`

---

### Task 12: 문서 + 버전 2.1.0 범프

**REQ-IDs:** REQ-MM-014

**Files:**
- Modify: `README.md`, `README.en.md` (헤더 규약 표 §4.2, HeaderStyle 예시, async decorator 전제; 좌표 예시 2.0.0→2.1.0), `build.gradle.kts`(getOrElse "2.1.0"), `maven-plugin/pom.xml`(version+agent.version), `samples/*`(2.1.0), `docs/RELEASING.md` 무변경 확인

- [ ] **Step 1: RELEASING.md 체크리스트 순서대로 버전 범프** (정본 → pom → samples → README 좌표. 역사 서술("v2.0.0 BREAKING" 배너 등)은 건드리지 않는다 — v1.4.1/좌표 전환 때의 과잉 치환 사고 재발 금지, 타깃 패턴 sed만.)
- [ ] **Step 2: README ko/en에** — "트레이서/헤더 규약" 절 추가: design §4.2 표 + HeaderStyle 사용 예(`PjacocoRestAssured.enable(HeaderStyle.FIELD)` + SUT의 `management.tracing.baggage.remote-fields=test.id` 짝) + async 귀속 전제(ContextPropagatingTaskDecorator 필요) + 우선순위·충돌 규칙 1줄. 릴리스 노트 초안(폴백 키 auto-create 제외 + 프록시 기본 제외 행위 변화)을 plan 산출물로 `docs/superpowers/plans/2026-08-03-v210-release-notes-draft.md`에 준비.
- [ ] **Step 3: 매트릭스 REQ-MM-014 🟢(PR 문서 게이트에서 최종) + 커밋** — `docs: MM header conventions + HeaderStyle + v2.1.0 bump`

---

### Task 13: 최종 검증 (매트릭스 100% + 전 스위트 무회귀 + 게이트)

**REQ-IDs:** 전체 (DoD)

- [ ] **Step 1:** 요구사항명세 매트릭스 전 행 🟢(또는 review-게이트 행은 PR에서 체크) 확인 — 각 🟢가 실제 통과 테스트명과 대응하는지 대조.
- [ ] **Step 2:** 전체 회귀: `./gradlew --no-daemon test :agent:integrationTest :agent:e2eTest :agent:e2eJakartaTest :agent:e2eCondyTest :testkit-core:verifyJarEmbedsPom :testkit-junit5:verifyJarEmbedsPom :testkit-junit4:verifyJarEmbedsPom :testkit-restassured:verifyJarEmbedsPom` + `(cd e2e-mm-boot3 && ../gradlew test)` + maven-plugin/samples 로컬 검증(PUBLISHING.md 절차). **e2eTest를 빼먹지 말 것**(v1.4.1 사이클의 실수 재발 방지).
- [ ] **Step 3:** 프로세스 잔존 0 확인(E2E들이 띄운 자식 JVM — 누수 검증 게이트).
- [ ] **Step 4:** PR 게이트 — spec-compliance 리뷰(이 plan+요구사항명세 대비) 먼저, code-quality 리뷰(`pr-review-toolkit:code-reviewer`) 다음, 지적 전건 트리아지 후 PR 생성.

## Self-Review 결과

- Spec coverage: REQ-MM-001~015 전부 task에 매핑(001-005·007→T1/T2, 006→T3, 010→T4, 008→T5/T6/T9, 009→T6, 011→T7, 012→T8+T10, 013→T9+T10, 014→T12, 015→T11). 설계 §4.6(트레이서-활성 test.id)은 문서화-전용 — T12의 README 절에 포함.
- Placeholder: 코드 블록 내 "구현 시 채움"은 해시 고정(T11) 1곳뿐 — 성격상 구현 시점에만 확정 가능함을 명시했음.
- Type consistency: `fallbackTestId(Object)`(T2 정의, T3 사용), `HeaderStyle`(T5 정의, T6/T9 사용), 테스트명 = 요구사항명세 매트릭스와 일치 확인.
