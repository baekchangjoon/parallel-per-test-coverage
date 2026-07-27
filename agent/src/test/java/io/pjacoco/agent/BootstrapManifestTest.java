package io.pjacoco.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Regression test for the 2026-07-27 evaluation finding (§4.3): the manifest hardcoded
 * {@code "jacocoVersion": "0.8.12"} — a build embedding another jacoco (e.g.
 * {@code -PjacocoVersion=0.8.13}, the canary matrix) still CLAIMED 0.8.12. The manifest must
 * report the embedded jacoco-core's own version constant.
 */
class BootstrapManifestTest {

    @Test
    void manifestReportsTheEmbeddedJacocoVersion() {
        String header = Bootstrap.manifestHeader("abc123");
        String expected = Bootstrap.shortJacocoVersion(org.jacoco.core.JaCoCo.VERSION);
        assertTrue(header.contains("\"jacocoVersion\":\"" + expected + "\""),
                "manifest must report the embedded jacoco-core version, was: " + header);
        assertTrue(header.contains("\"commitSha\":\"abc123\""));
    }

    /** The manifest documents a 3-segment version; JaCoCo.VERSION is the fully-qualified build id
     *  (e.g. 0.8.12.202403310830) — the short form keeps the documented output schema stable. */
    @Test
    void jacocoVersionIsShortenedToThreeSegments() {
        assertTrue(Bootstrap.shortJacocoVersion("0.8.12.202403310830").equals("0.8.12"));
        assertTrue(Bootstrap.shortJacocoVersion("0.8.12").equals("0.8.12"));
    }

    @Test
    void manifestOmitsNullCommitSha() {
        assertFalse(Bootstrap.manifestHeader(null).contains("commitSha"));
    }
}
