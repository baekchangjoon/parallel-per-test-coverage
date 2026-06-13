package com.example.app;

/**
 * Application-under-test class (a stand-in for third-party production code — note it is NOT under
 * the agent's own {@code io.pjacoco} namespace, which the agent self-excludes). Distinct branches let
 * tests assert that different test cases produce different per-test coverage. Compiled to Java 8
 * bytecode (see build.gradle.kts) so jacoco uses the field probe-array strategy.
 */
public class TargetService {

    public int classify(int n) {
        if (n < 0) {
            return -1;
        }
        if (n == 0) {
            return 0;
        }
        return 1;
    }

    public String greet(boolean formal) {
        if (formal) {
            return "Good day";
        }
        return "hi";
    }
}
