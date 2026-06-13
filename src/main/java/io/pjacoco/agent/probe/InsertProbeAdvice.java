package io.pjacoco.agent.probe;

import java.lang.reflect.Field;
import net.bytebuddy.asm.Advice;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Woven into jacoco's {@code ProbeInserter.insertProbe(int)} (instrument time). After jacoco emits
 * its own {@code probes[id] = true}, additionally emits {@code CoverageBridge.recordCoverage(Class,
 * long classId, int probeId)} — additive; the jacoco global array is untouched. Verbatim from spike/.
 */
public class InsertProbeAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void exit(
            @Advice.FieldValue("mv") MethodVisitor mv,
            @Advice.FieldValue("arrayStrategy") Object arrayStrategy,
            @Advice.Argument(0) int id)
            throws Exception {
        // arrayStrategy is jacoco's ClassFieldProbeArrayStrategy (package-private): reflect fields.
        // If a different strategy (e.g. Condy for Java 11+ classes) is used, the fields are absent and
        // the surrounding suppress(Throwable) makes this a no-op (graceful degradation).
        Field classNameField = arrayStrategy.getClass().getDeclaredField("className");
        classNameField.setAccessible(true);
        String className = (String) classNameField.get(arrayStrategy);

        Field classIdField = arrayStrategy.getClass().getDeclaredField("classId");
        classIdField.setAccessible(true);
        long classId = classIdField.getLong(arrayStrategy);

        mv.visitLdcInsn(Type.getType("L" + className + ";"));
        mv.visitLdcInsn(Long.valueOf(classId));
        mv.visitLdcInsn(Integer.valueOf(id));
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "io/pjacoco/agent/probe/CoverageBridge",
                "recordCoverage",
                "(Ljava/lang/Class;JI)V",
                false);
    }
}
