package io.pjacoco.agent.probe;

/**
 * Throwaway class instrumented once during premain to force jacoco's {@code ProbeInserter} to load
 * (and be advised by ByteBuddy) in a clean context — before the {@code ClassFileTransformer} starts
 * instrumenting app classes nested inside its own transform (where ByteBuddy would skip it). The
 * branch guarantees at least one probe, exercising {@code insertProbe}.
 */
final class WarmupTarget {
    int branch(int n) {
        if (n < 0) {
            return -1;
        }
        return 1;
    }
}
