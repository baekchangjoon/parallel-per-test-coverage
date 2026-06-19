package io.pjacoco.agent.trace;

import java.util.function.Supplier;

public final class LocalTestIdSource implements TestIdSource {
    private final Supplier<String> supplier;   // baggage/ThreadLocal-derived testId

    public LocalTestIdSource(Supplier<String> supplier) {
        this.supplier = supplier;
    }

    @Override
    public String currentKey() {
        try {
            return supplier.get();
        } catch (Throwable t) {
            return null;
        }
    }
}
