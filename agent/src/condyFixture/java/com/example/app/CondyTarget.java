package com.example.app;

/**
 * Application-under-test stand-in compiled to <strong>Java 11+ bytecode</strong> (class-file
 * major version &gt;= 55; default 11, raised to 17/21 in CI via {@code -PcondyRelease}) so that
 * jacoco's {@code ProbeArrayStrategyFactory} selects {@code CondyProbeArrayStrategy} (the
 * {@code version >= 55} branch) instead of the {@code ClassFieldProbeArrayStrategy} used for the
 * Java 8 {@link TargetService}. Java 11 and Java 17 (e.g. Spring Boot 4) take the identical branch.
 *
 * <p>Used by {@code ProbeRoutingCondyIT} (in-process) and {@code CondyE2E} (real agent) to prove
 * per-test probe routing works for modern bytecode — the scenario the README's Spring Boot 4
 * example relies on.
 */
public class CondyTarget {

    public int classify(int n) {
        if (n < 0) {
            return -1;
        }
        if (n == 0) {
            return 0;
        }
        return 1;
    }
}
