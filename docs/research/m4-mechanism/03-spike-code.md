# 03 — 스파이크 코드/테스트 (검증 통과본)

`spike/` 디렉터리의 전체 코드. 10개 파일 ~440 LOC. 아래는 파일별 코드 + 역할 설명 + 정식
에이전트로의 매핑.

## 0. 빌드

`spike/settings.gradle.kts`
```kotlin
rootProject.name = "pjacoco-spike"
```

`spike/build.gradle.kts`
```kotlin
plugins { java }
repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

// Java 8 바이트코드 → jacoco가 ClassFieldProbeArrayStrategy(className/classId 필드) 사용.
tasks.withType<JavaCompile> { options.release.set(8) }

dependencies {
    implementation("org.jacoco:org.jacoco.core:0.8.12")          // ASM은 transitive
    implementation("net.bytebuddy:byte-buddy:1.14.18")
    implementation("net.bytebuddy:byte-buddy-agent:1.14.18")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test {
    useJUnitPlatform()
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")  // self-attach
    testLogging { showStandardStreams = true; events("passed","failed","skipped") }
}
```
- `org.jacoco.core`만 선언하면 ASM(`org.ow2.asm`)이 transitive로 따라와 advice가
  `org.objectweb.asm.*`를 컴파일 참조 가능.
- `byte-buddy-agent`의 self-attach는 JDK9+에서 `-Djdk.attach.allowAttachSelf=true` 필요.
- Gradle 9.x 자체는 JDK17+로 실행해야 함: `JAVA_HOME=<jdk17> gradle test`.

## 1. 런타임 — 브리지와 per-test 스토어

`spike/src/main/java/io/pjacoco/spike/TestProbes.java` — 한 테스트의 누적 커버리지
```java
package io.pjacoco.spike;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TestProbes {
    private final ConcurrentHashMap<Long, boolean[]> probesByClass = new ConcurrentHashMap<Long, boolean[]>();
    private final Map<Long, String> nameByClass = new ConcurrentHashMap<Long, String>();

    public void record(long classId, String className, int probeId, int probeCount) {
        boolean[] arr = probesByClass.get(classId);
        if (arr == null) {
            boolean[] created = new boolean[probeCount];
            boolean[] prev = probesByClass.putIfAbsent(classId, created);
            arr = (prev != null) ? prev : created;
            nameByClass.put(classId, className);
        }
        if (probeId >= 0 && probeId < arr.length) {
            arr[probeId] = true;            // benign race, jacoco의 프로브 쓰기와 동일 성질
        }
    }
    public boolean[] probes(long classId) { return probesByClass.get(classId); }
    public String name(long classId) { return nameByClass.get(classId); }
    public Set<Long> classIds() { return probesByClass.keySet(); }
}
```

`spike/src/main/java/io/pjacoco/spike/CoverageBridge.java` — 계측 코드가 호출하는 표면
```java
package io.pjacoco.spike;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CoverageBridge {
    private static final Map<String, Integer> PROBE_COUNTS = new ConcurrentHashMap<String, Integer>();
    private static final ThreadLocal<TestProbes> ACTIVE = new ThreadLocal<TestProbes>();
    private CoverageBridge() {}

    // 계측 시점: 클래스별 총 프로브 수 (VM/slash 이름)
    public static void setTotalProbeCount(String className, int count) { PROBE_COUNTS.put(className, count); }

    // 런타임 핫패스: 프로브마다 호출. 시그니처는 advice가 박는 디스크립터 (Ljava/lang/Class;JI)V 와 일치.
    public static void recordCoverage(Class<?> clazz, long classId, int probeId) {
        TestProbes t = ACTIVE.get();
        if (t == null) return;               // untagged
        String name = clazz.getName().replace('.', '/');
        Integer count = PROBE_COUNTS.get(name);
        t.record(classId, name, probeId, count != null ? count.intValue() : probeId + 1);
    }
    public static TestProbes start() { TestProbes t = new TestProbes(); ACTIVE.set(t); return t; }
    public static void clear() { ACTIVE.remove(); }
}
```
> 정식 에이전트에선 `ACTIVE` ThreadLocal을 `context/CoverageContext`(TestStore 보유)로 분리하고,
> `recordCoverage`는 `CoverageContext.get()`을 읽어 `store.record(...)` 호출 + try/catch로 예외 삼킴.

## 2. Advice 3종 (정식 에이전트로 verbatim 이식)

`InsertProbeAdvice.java` — `ProbeInserter.insertProbe(id)` 직후 additive 호출 삽입
```java
package io.pjacoco.spike;

import java.lang.reflect.Field;
import net.bytebuddy.asm.Advice;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public class InsertProbeAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void exit(@Advice.FieldValue("mv") MethodVisitor mv,           // 직접 캐스팅 (relocate 아님)
                     @Advice.FieldValue("arrayStrategy") Object arrayStrategy,
                     @Advice.Argument(0) int id) throws Exception {
        Field cnf = arrayStrategy.getClass().getDeclaredField("className");
        cnf.setAccessible(true); String className = (String) cnf.get(arrayStrategy);
        Field cif = arrayStrategy.getClass().getDeclaredField("classId");
        cif.setAccessible(true); long classId = cif.getLong(arrayStrategy);

        mv.visitLdcInsn(Type.getType("L" + className + ";"));            // Class
        mv.visitLdcInsn(Long.valueOf(classId));                          // long
        mv.visitLdcInsn(Integer.valueOf(id));                           // int
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "io/pjacoco/spike/CoverageBridge",
                "recordCoverage", "(Ljava/lang/Class;JI)V", false);
    }
}
```
> `insertProbe`는 *계측 시점*에 클래스당 프로브 수만큼 호출됨(런타임 핫패스 아님). 리플렉션 비용은
> 계측 1회성이라 무관. 런타임 비용은 *삽입된* `recordCoverage` 호출뿐.

`VisitMaxsAdvice.java` — 스택 여유 +2
```java
package io.pjacoco.spike;
import net.bytebuddy.asm.Advice;

public class VisitMaxsAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter(@Advice.Argument(value = 0, readOnly = false) int maxStack) {
        maxStack = maxStack + 2;   // jacoco가 이미 +3, 우리 Class+long+int 호출에 +2면 충분
    }
}
```

`VisitTotalProbeCountAdvice.java` — 프로브 수 캡처
```java
package io.pjacoco.spike;
import net.bytebuddy.asm.Advice;

public class VisitTotalProbeCountAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter(@Advice.FieldValue("className") String className, @Advice.Argument(0) int count) {
        CoverageBridge.setTotalProbeCount(className, count);
    }
}
```

## 3. 후킹 설치

`JacocoProbeHook.java` — body-only advice를 jacoco 내부에 설치
```java
package io.pjacoco.spike;

import static net.bytebuddy.matcher.ElementMatchers.named;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

public final class JacocoProbeHook {
    private static volatile boolean installed = false;
    private JacocoProbeHook() {}

    public static synchronized void install() {
        if (installed) return;
        new AgentBuilder.Default()
                .disableClassFormatChanges()                                   // body-only → 멤버 추가 없음
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(named("org.jacoco.core.internal.instr.ProbeInserter"))
                .transform((b, t, cl, m, pd) -> b
                        .visit(Advice.to(VisitMaxsAdvice.class).on(named("visitMaxs")))
                        .visit(Advice.to(InsertProbeAdvice.class).on(named("insertProbe"))))
                .type(named("org.jacoco.core.internal.instr.ClassInstrumenter"))
                .transform((b, t, cl, m, pd) -> b
                        .visit(Advice.to(VisitTotalProbeCountAdvice.class).on(named("visitTotalProbeCount"))))
                .installOn(ByteBuddyAgent.install());
        installed = true;
    }
}
```
> `disableClassFormatChanges()`는 여기서 **옳다** — advice는 메서드 본문만 바꾸고 멤버를 추가하지
> 않으므로 retransformation 제약(필드/메서드 추가 금지)과 충돌하지 않는다. (멤버를 추가하는 프로브
> 계측은 ByteBuddy가 아니라 jacoco `Instrumenter`가 *로드 시점*에 한다.)
> 정식 에이전트의 `ProbeInstrumentation.installHookOnly(inst)`가 이 메서드에 대응.

## 4. 테스트

`TargetService.java` — 커버리지 대상
```java
package io.pjacoco.spike;

public class TargetService {
    public int classify(int n) {
        if (n < 0) { return -1; }
        if (n == 0) { return 0; }
        return 1;
    }
    public String greet(boolean formal) {
        if (formal) { return "Good day"; }
        return "hi";
    }
}
```

`SpikeMechanismTest.java` — 키스톤(동등성) + 격리. 핵심만 발췌(전체는 `spike/`):
```java
@Test
void perTestProbesMatchVanillaJacoco() throws Exception {
    JacocoProbeHook.install();
    byte[] original = readBytes(NAME);

    IRuntime runtime = new LoggerRuntime();
    Instrumenter instrumenter = new Instrumenter(runtime);
    byte[] instrumented = instrumenter.instrument(original, NAME);   // advised → recordCoverage 삽입
    RuntimeData data = new RuntimeData();
    runtime.startup(data);

    MemoryClassLoader loader = new MemoryClassLoader();              // 부모=테스트 로더(브리지/jacoco 해소)
    loader.add(NAME, instrumented);
    Class<?> target = loader.loadClass(NAME);

    TestProbes testProbes = CoverageBridge.start();
    target.getMethod("classify", int.class).invoke(target.getDeclaredConstructor().newInstance(), 5);
    CoverageBridge.clear();

    ExecutionDataStore vanillaStore = new ExecutionDataStore();
    data.collect(vanillaStore, new SessionInfoStore(), false);      // vanilla = jacoco 전역 배열
    runtime.shutdown();

    ExecutionData vanillaEd = vanillaStore.getContents().iterator().next();
    long classId = vanillaEd.getId();
    boolean[] ourProbes = testProbes.probes(classId);
    assertArrayEquals(vanillaEd.getProbes(), ourProbes);            // ← 키스톤: byte-동일
    // + Analyzer로 커버 라인 집합 동일 확인
}

@Test
void parallelContextsAreIsolated() throws Exception {
    // ... 동일하게 1회 계측 후 ...
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CyclicBarrier barrier = new CyclicBarrier(2);
    Callable<TestProbes> neg = () -> { TestProbes t = CoverageBridge.start(); barrier.await();
        for (int i=0;i<1000;i++) classify.invoke(instance, -5); CoverageBridge.clear(); return t; };
    Callable<TestProbes> pos = () -> { TestProbes t = CoverageBridge.start(); barrier.await();
        for (int i=0;i<1000;i++) classify.invoke(instance, 5);  CoverageBridge.clear(); return t; };
    TestProbes tNeg = pool.submit(neg).get(), tPos = pool.submit(pos).get();
    // assertFalse(Arrays.equals(neg, pos)) + 커버 라인 집합 다름 단언
}
```
`MemoryClassLoader`(부모 = 테스트 클래스로더)는 계측 클래스만 로컬 정의, 나머지(`CoverageBridge`·
jacoco 런타임·ASM)는 부모로 위임 — retransformation의 "필드 추가 불가" 문제를 회피하는 **로드타임**
계측 패턴.

## 5. 스파이크 → 정식 에이전트 매핑

| 스파이크 파일 | 정식 에이전트 (플랜 Task) |
|---|---|
| `CoverageBridge` (ThreadLocal 내장) | `probe/CoverageBridge` + `context/CoverageContext`(TestStore 보유) — T7/T1 |
| `TestProbes` | `store/TestStore` (+`ClassProbes`) — T2 |
| `InsertProbeAdvice`·`VisitMaxsAdvice`·`VisitTotalProbeCountAdvice` | **verbatim** `probe/*` (패키지·브리지 경로만 변경) — T11 |
| `JacocoProbeHook.install` | `probe/ProbeInstrumentation.installHookOnly` + `install(inst,options)`(+ClassFileTransformer+runtime) — T11 |
| `SpikeMechanismTest#perTestProbesMatchVanillaJacoco` | `it/GoldenEquivalenceIT` — T12 |
| `SpikeMechanismTest#parallelContextsAreIsolated` | `it/ParallelIsolationIT`(정식은 부착 에이전트+Jetty, 파일 단언) — T15 |
| `TargetService` | `it/TargetService` — T11 |

## 6. 정식 이식 시 달라지는 점 (스파이크 범위 밖)

1. **ThreadLocal 분리**: 스파이크는 브리지가 ThreadLocal을 내장. 정식은 `CoverageContext`(TestStore
   보유)로 분리하고, 인입 서블릿 advice가 요청 시 store를 해소해 set.
2. **계측 경로**: 스파이크는 테스트가 직접 `Instrumenter.instrument` 호출. 정식은
   `ClassFileTransformer`가 로드되는 앱 클래스를 자동 계측(+`WildcardMatcher` includes/excludes).
3. **런타임**: 스파이크는 `LoggerRuntime`(in-process 테스트용). 정식 에이전트는
   `ModifiedSystemClassRuntime.createFor(inst, ...)`(jacoco 에이전트와 동일한 프로덕션 선택).
4. **예외 격리**: 정식 `recordCoverage`는 try/catch로 모든 예외를 삼켜 앱을 절대 죽이지 않음.
5. **shade**: 정식은 jacoco-core/byte-buddy를 `io.pjacoco.shaded.*`로 relocate(에이전트 자가당착 회피).
   그러면 **후킹 대상 패키지가 바뀐다**: `named("org.jacoco.core.internal.instr.ProbeInserter")`
   → `named("io.pjacoco.shaded.jacoco.core.internal.instr.ProbeInserter")`. 반면 advice가 박는
   `recordCoverage` 디스크립터(`io/pjacoco/agent/probe/CoverageBridge`)는 **우리 클래스라 shade
   대상이 아니므로 그대로**. (스파이크는 unshaded라 `org.jacoco.*`를 그대로 후킹해 검증.)
