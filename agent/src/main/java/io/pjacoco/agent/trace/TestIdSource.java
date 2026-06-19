package io.pjacoco.agent.trace;

public interface TestIdSource {
    /** @return current coverage key, or null if this source cannot resolve one (fall through). */
    String currentKey();
}
