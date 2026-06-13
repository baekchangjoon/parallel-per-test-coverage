# 02 — 스파이크 검증: self-contained 변형 동작 확인

목표: Datadog 패턴을 **self-contained(jacoco-core 임베드)** 로 차용했을 때
(1) `.exec`가 vanilla와 동일하고 (2) 병렬 테스트가 격리되는지를 *동작하는 코드*로 증명.

## 1. 확정한 jacoco-core 내부 구조 (0.8.12)

후킹이 의존하는 내부 심볼을 소스/javap로 확인:

`org.jacoco.core.internal.instr.ProbeInserter` (`v0.8.12` 소스):
```java
class ProbeInserter extends MethodVisitor implements IProbeInserter {
    private final IProbeArrayStrategy arrayStrategy;     // 필드
    // MethodVisitor 상속: protected MethodVisitor mv;   // 위임 (advice가 읽는 필드)
    public void insertProbe(final int id) {              // ALOAD probes; push id; ICONST_1; BASTORE
        mv.visitVarInsn(ALOAD, variable);
        InstrSupport.push(mv, id);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(BASTORE);                           // ← 직후 스택 비어 있음
    }
    public void visitMaxs(int maxStack, int maxLocals) {
        mv.visitMaxs(Math.max(maxStack + 3, accessorStackSize), maxLocals + 2);  // jacoco가 이미 +3
    }
}
```

`ClassInstrumenter`:
```java
public class ClassInstrumenter extends ClassProbesVisitor {
    private String className;                            // 필드 (advice가 읽음)
    public void visitTotalProbeCount(final int count) { ... }
}
```

`ClassFieldProbeArrayStrategy` (package-private → 리플렉션 필요):
```java
class ClassFieldProbeArrayStrategy implements IProbeArrayStrategy {
    private final String className;
    private final long classId;
}
```

## 2. 결정적 발견 — `org.jacoco.core`는 ASM을 번들하지 않는다

```
$ unzip -l org.jacoco.core-0.8.12.jar | grep -iE "objectweb|/asm/"
(없음)
```
즉 `ProbeInserter.mv`의 타입은 **진짜 `org.objectweb.asm.MethodVisitor`**(relocate 아님). 따라서
우리가 jacoco-core를 임베드하면 advice에서 **`mv`를 직접 캐스팅**해 `visitLdcInsn`/`visitMethodInsn`을
호출할 수 있다 — Datadog의 `MethodVisitorWrapper`(MethodHandle 우회)가 **불필요**.

| | Datadog | 본 스파이크(self-contained) |
|---|---|---|
| 후킹 대상 | 사용자가 붙인 relocate된 jacoco **agent** | 우리가 임베드한 jacoco-**core** |
| 패키지 | `org.jacoco.agent.rt.internal_<hash>.*` (난독) | `org.jacoco.core.internal.instr.*` (고정) |
| `mv` 접근 | MethodHandle (컴파일 참조 불가) | 직접 캐스팅 |
| 버전 결합 | 사용자 jacoco 버전 | 우리가 shade한 고정 버전 |

## 3. 스파이크 설계 (1회 실행, 2-출처 비교)

```
JacocoProbeHook.install()                         // ProbeInserter/ClassInstrumenter에 body-only advice
  ↓
jacoco Instrumenter.instrument(TargetService)     // advised → recordCoverage 삽입, count 캡처
  ↓
LoggerRuntime + RuntimeData (vanilla 출처)         // 계측 클래스의 $jacocoInit이 참조
  ↓
MemoryClassLoader로 로드 → CoverageBridge.start(T1) → classify(5) → clear()
  ↓
vanilla = data.collect(...)  (jacoco 전역 배열)
ours    = TestProbes (우리 브리지)
  ↓
assertArrayEquals(vanillaProbes, ourProbes)        // ← 키스톤
```
한 번의 계측+실행에서 jacoco 전역 배열(vanilla)과 우리 브리지(per-test)를 동시에 얻어 비교 →
additive성(전역 무손상)과 동등성을 같은 실행으로 입증.

> Java 8 바이트코드로 컴파일(`options.release = 8`): 클래스 major 52면 jacoco가
> `ClassFieldProbeArrayStrategy`(className/classId 필드 보유)를 쓴다. Java 11+(major≥55)는 condy
> 전략이라 필드 구조가 달라 리플렉션 가정이 깨질 수 있어 스파이크는 8로 고정.

## 4. 실행 결과 — 통과 ✅

```
SpikeMechanismTest > perTestProbesMatchVanillaJacoco()  PASSED
    [spike] classId=-2198370455144847958 probes=6 coveredLines=[4, 7, 10, 13]
SpikeMechanismTest > parallelContextsAreIsolated()  PASSED
    [spike] isolation negLines=[7, 8] posLines=[7, 10, 13]
```

### 4.1 vanilla 동등성
`assertArrayEquals(vanillaProbes, ourProbes)` 통과 → 우리 per-test `boolean[]`가 jacoco 전역 배열과
**byte 단위로 동일**. 6개 프로브 중 실행 경로의 것만 true, `coveredLines=[4,7,10,13]`.

### 4.2 병렬 격리
2스레드가 `CyclicBarrier`로 동시 출발해 각 1000회 `classify(-5)`/`classify(5)`를 같은 계측 클래스에
실행:
- 음수 경로: `negLines=[7, 8]` (라인 7 `if(n<0)` → 8 `return -1`)
- 양수 경로: `posLines=[7, 10, 13]` (7 → 10 `if(n==0)` → 13 `return 1`)

교차 오염 0 (neg에 10·13 없음, pos에 8 없음). ThreadLocal 컨텍스트가 동시 부하에서 깨끗이 분리.

### 4.3 부수 관찰 — untagged 미기록
첫 테스트의 `coveredLines`엔 라인 4(=생성자 `<init>`)가 있는데, 격리 테스트의 `posLines`엔 없다.
격리 테스트는 `newInstance()`를 컨텍스트 **밖**에서 호출했기 때문 → "활성 컨텍스트 없으면 미기록"이
부수적으로 검증됨.

## 5. 증명된 4가지 속성

1. **vanilla 동일** — 프로브 배열 byte-동일 → 어떤 다운스트림 분석도 동일.
2. **병렬 격리** — 동시 부하에서 per-test 분리(교차 오염 0).
3. **additive** — jacoco 전역 배열 무손상(같은 실행에서 vanilla 수집 성공) → 우리 로직이 죽어도
   일반 jacoco 동작 보존.
4. **self-contained** — 임베드 jacoco-core, 깨끗한 패키지 직접 캐스팅, 별도 jacoco 에이전트 불요.

## 6. 설계 함의 (스펙/플랜에 반영됨)

- 동결 인터페이스: `CoverageBridge.recordCoverage(Class<?>, long classId, int probeId)` +
  `setTotalProbeCount(String className, int count)`.
- `maxStack += 2`로 충분(VerifyError 없음).
- **버전 리스크 완화**: 내부 API 결합면이 *우리가 shade한 고정 버전*에 묶이므로, 취약성은 상시가
  아니라 **임베드 jacoco-core 업그레이드 PR 한정**. 카나리는 그때만 의미.
- 미검증 잔여(스파이크 범위 밖): 정식 에이전트의 `ClassFileTransformer` + `ModifiedSystemClassRuntime`
  배선(표준 jacoco-agent 플러밍) — 플랜 Task 15 e2e에서 확인 예정.
