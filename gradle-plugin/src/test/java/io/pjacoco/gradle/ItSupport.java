package io.pjacoco.gradle;

/** Shared test support for the gradle-plugin functional tests. These tests must run via the Gradle
 *  {@code test} task (which injects {@code pjacoco.it.version}); running one directly from an IDE without
 *  Gradle will fail fast in {@link #itVersion()} by design. */
final class ItSupport {

    private ItSupport() {}

    /**
     * The pjacoco version injected by the build via {@code -Dpjacoco.it.version} (= {@code project.version}).
     * Fails fast if it is missing, so a misconfigured build can NOT silently fall back to a stale literal
     * default (P3-6: stale {@code "1.0.0"}/{@code "1.1.0"} defaults previously masked version mismatches).
     */
    static String itVersion() {
        String version = System.getProperty("pjacoco.it.version");
        if (version == null || version.isEmpty()) {
            throw new IllegalStateException(
                    "pjacoco.it.version not provided — the gradle-plugin 'test' task must inject it "
                            + "(systemProperty(\"pjacoco.it.version\", project.version)).");
        }
        return version;
    }
}
