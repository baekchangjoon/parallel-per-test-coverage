package io.pjacoco.agent.store;

/** className (VM/slash name) + probe boolean[] for one class within a TestStore. */
public final class ClassProbes {
    private final String className;
    private final boolean[] probes;

    public ClassProbes(String className, boolean[] probes) {
        this.className = className;
        this.probes = probes;
    }

    public String className() { return className; }
    public boolean[] probes() { return probes; }
}
