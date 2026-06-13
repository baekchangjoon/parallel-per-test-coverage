package io.pjacoco.spike;

import java.lang.reflect.Field;
import net.bytebuddy.asm.Advice;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Woven into jacoco's {@code ProbeInserter.insertProbe(int)} (instrument time). After jacoco emits
 * its own {@code probes[id] = true}, we additionally emit a call to
 * {@code CoverageBridge.recordCoverage(Class, long classId, int probeId)} — additive, the jacoco
 * global array is untouched.
 */
public class InsertProbeAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void exit(
            @Advice.FieldValue("mv") MethodVisitor mv,
            @Advice.FieldValue("arrayStrategy") Object arrayStrategy,
            @Advice.Argument(0) int id)
            throws Exception {
        // arrayStrategy is jacoco's ClassFieldProbeArrayStrategy (package-private): reflect className/classId.
        Field classNameField = arrayStrategy.getClass().getDeclaredField("className");
        classNameField.setAccessible(true);
        String className = (String) classNameField.get(arrayStrategy);

        Field classIdField = arrayStrategy.getClass().getDeclaredField("classId");
        classIdField.setAccessible(true);
        long classId = classIdField.getLong(arrayStrategy);

        // stack after jacoco's probe is empty; push Class, long classId, int id, then invoke.
        mv.visitLdcInsn(Type.getType("L" + className + ";"));
        mv.visitLdcInsn(Long.valueOf(classId));
        mv.visitLdcInsn(Integer.valueOf(id));
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "io/pjacoco/spike/CoverageBridge",
                "recordCoverage",
                "(Ljava/lang/Class;JI)V",
                false);
    }
}
