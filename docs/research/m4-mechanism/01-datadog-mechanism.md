# 01 — Datadog dd-trace-java 커버리지 메커니즘 분석

원본: `DataDog/dd-trace-java` (Apache 2.0). 본 프로젝트가 차용·검증한 패턴의 레퍼런스.

## 1. 클론 방법 (대형 리포 — sparse·blobless)

```bash
git clone --depth 1 --filter=blob:none --sparse https://github.com/DataDog/dd-trace-java.git /tmp/dd-trace-java-ref
cd /tmp/dd-trace-java-ref
git sparse-checkout set \
  dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/coverage \
  dd-java-agent/instrumentation/jacoco-0.8.9
```
`--filter=blob:none --sparse`로 트리만 받고, 필요한 경로만 체크아웃해 블롭을 지연 패치.

## 2. 모듈 구조 (관찰)

- **라인 커버리지(우리 관심)**: `civisibility/coverage/line/` — `LineProbes`, `LineCoverageStore`,
  `ExecutionDataAdapter`(jacoco `ExecutionData` import), `SourceAnalyzer`.
- **파일 커버리지(TIA 기본, 거침)**: `civisibility/coverage/file/instrumentation/` — **자체 ASM**
  트랜스포머(`CoverageClassVisitor`/`CoverageMethodVisitor`)로 `record(clazz)`만 삽입.
- **jacoco 후킹(핵심)**: `dd-java-agent/instrumentation/**jacoco-0.8.9**/.../instrumentation/jacoco/`
  — 5개 파일. 모듈명이 `jacoco-0.8.9`인 점이 **버전 핀**을 드러냄(우리 리뷰의 버전 취약성 지적 확인).

후킹 5개 파일:
| 파일 | 역할 |
|---|---|
| `ProbeInserterInstrumentation.java` | **핵심** — `ProbeInserter.insertProbe`/`visitMaxs`에 advice |
| `MethodVisitorWrapper.java` | relocate된 jacoco의 `MethodVisitor`를 MethodHandle로 호출 |
| `ClassInstrumenterInstrumentation.java` | `ClassInstrumenter.visitTotalProbeCount` 후킹 → 프로브 수 캡처 |
| `InstrumenterInstrumentation.java` | `Instrumenter.instrument` 후킹 → init 트리거 + Java<1.5 스킵 |
| `CoverageDataInjector.java` | 전체 커버리지% 공급자 등록(별개 관심사) |

## 3. 핵심 동작 — 계측 시점에 additive 호출 삽입

### 3.1 `ProbeInserterInstrumentation`

대상 타입 매처(주목 — **relocate된 agent 런타임** 패키지):
```java
// hierarchyMatcher()
nameStartsWith("org.jacoco.agent.rt.internal")
    .and(nameEndsWith(".core.internal.instr.ProbeInserter"))
// 주석: 배포된 jacoco javaagent는 내부 클래스를 난독 패키지로 relocate함
//      ex. org.jacoco.agent.rt.internal_72ddf3b.core.internal.instr.ProbeInserter
```

advice 2개:
```java
methodAdvice:
  visitMaxs(int,int)  → VisitMaxsAdvice      // OnMethodEnter
  insertProbe(int)    → InsertProbeAdvice     // OnMethodExit
```

`VisitMaxsAdvice` — 스택 여유 확보:
```java
@Advice.OnMethodEnter
static void enter(@Advice.Argument(value = 0, readOnly = false) int maxStack) {
    maxStack = maxStack + 2;
}
```

`InsertProbeAdvice` — jacoco 프로브 직후 우리 호출을 삽입:
```java
@Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
static void exit(@Advice.FieldValue("mv") Object mv,
                 @Advice.FieldValue("arrayStrategy") Object arrayStrategy,
                 @Advice.Argument(0) int id) {
    // arrayStrategy(=ClassFieldProbeArrayStrategy)에서 리플렉션으로 className·classId
    String className = (String) field(arrayStrategy, "className");
    // ... include/exclude 패키지 필터 ...
    long classId = longField(arrayStrategy, "classId");

    MethodVisitorWrapper methodVisitor = MethodVisitorWrapper.wrap(mv);
    methodVisitor.pushClass(className);          // LDC Type → 런타임에 Class
    methodVisitor.visitLdcInsn(classId);         // long
    methodVisitor.push(id);                      // int probeId
    methodVisitor.visitMethodInsn(
        INVOKESTATIC,
        "datadog/trace/api/civisibility/coverage/CoveragePerTestBridge",
        "recordCoverage", "(Ljava/lang/Class;JI)V", false);
}
```

→ 즉 **런타임 프로브 발화를 가로채는 게 아니라**, 계측 결과물에 `recordCoverage(Class, classId, probeId)`
한 줄을 jacoco 프로브 옆에 박는다. jacoco 전역 배열은 그대로(additive).

### 3.2 `MethodVisitorWrapper` — 왜 MethodHandle인가 (우리와의 결정적 차이)

Datadog은 사용자가 붙인 **relocate된 jacoco 에이전트**를 후킹하므로, `mv`의 실제 타입은
`org.jacoco.agent.rt.internal_<hash>.asm.MethodVisitor` — 컴파일 시점에 참조 불가. 그래서
런타임에 `RT.getAgent()`로 에이전트 패키지를 알아내 클래스를 로드하고 메서드를 `MethodHandle`로
unreflect해 호출한다:
```java
IAgent agent = RT.getAgent();
String pkg = agent.getClass().getPackage().getName();           // 난독 패키지
Class<?> shadedMV = agent.getClass().getClassLoader().loadClass(pkg + ".asm.MethodVisitor");
visitMethodInsnHandle = lookup.unreflect(shadedMV.getMethod("visitMethodInsn", ...));
```

### 3.3 `ClassInstrumenterInstrumentation` — 프로브 수 캡처

발화 시점엔 클래스 총 프로브 수를 알 수 없다. 그래서 계측 시점에 따로 잡는다:
```java
hierarchyMatcher: ...".core.internal.instr.ClassInstrumenter"
methodAdvice: visitTotalProbeCount(int) → VisitTotalProbeCountAdvice

@Advice.OnMethodEnter
static void enter(@Advice.FieldValue("className") String className, @Advice.Argument(0) int count) {
    CoveragePerTestBridge.setTotalProbeCount(className, count);
}
```

### 3.4 `InstrumenterInstrumentation` — init 순서 + Java<1.5 스킵

```java
hierarchyMatcher: ...".core.instr.Instrumenter"
methodAdvice: instrument(byte[]) → InstrumentAdvice

@Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
static byte[] enter(@Advice.Argument(0) byte[] bytes) {
    CoverageDataInjector.init();                         // preMain 순서가 불확정이라 반복 호출로 트리거
    int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
    if (major < 49) return bytes;                        // Java<1.5: 타입 상수 푸시 불가 → 계측 스킵
    return null;                                         // 진행
}
```

## 4. 이 분석이 확정한 것 (우리 리뷰 대비)

1. **C1 확정**: 메커니즘은 "발화 후킹"이 아니라 **계측 시점 `insertProbe` 후킹**. 우리 플랜의
   4-인자 `record(classId, className, probeId, probeCount)`는 틀림 — 발화 시점엔 probeCount가 없고,
   올바른 시그니처는 `recordCoverage(Class, long classId, int probeId)` + 별도 `setTotalProbeCount`.
2. **버전 취약성 확정**: 모듈명 `jacoco-0.8.9` + 난독 패키지 매칭 = Datadog은 사용자 jacoco 버전에
   결합. 우리는 임베드로 이 결합을 **우리 통제**로 가져옴(→ 02 문서).
3. **self-contained가 더 단순함을 발견**: Datadog의 MethodHandle 우회는 *relocate된 agent*를 후킹하기
   때문. jacoco-core를 직접 임베드하면 깨끗한 패키지를 직접 캐스팅 → MethodHandle 불요.

## 5. "그대로 가져다 쓰기"가 안 되는 이유 (재확인)

- in-process 전제(JUnit 리스너가 컨텍스트 세팅) — 우리는 out-of-process(Baggage)로 이식 필요.
- 산출물이 `.exec`가 아니라 Datadog 인테이크로 직행(SaaS 결합).
- 트레이서 코어·shaded 의존에 얽혀 모듈 단독 적출 불가.
→ 그래서 **코드 이식이 아니라 패턴 차용**. 검증은 다음 문서.
