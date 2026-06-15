package io.pjacoco.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Framework-neutral entry point for routing per-test coverage to the
 * <a href="https://github.com/baekchangjoon/parallel-per-test-coverage">parallel-per-test-coverage</a>
 * agent attached to the server under test. Zero third-party dependencies; Java 8 compatible.
 *
 * <p><strong>Opt-in:</strong> point it at the agent's control endpoint with the system property
 * {@code -Dpjacoco.control-url=http://127.0.0.1:6310}. With no such property {@link #enabled()} is
 * {@code false} and every method is a no-op, so a suite with this on the classpath behaves normally
 * when routing is off. Build plugins (pjacoco Gradle/Maven) set the property automatically.
 *
 * <p>The active {@code testId} is held in an {@link InheritableThreadLocal}, so worker threads a test
 * spawns inherit it and their requests stay attributed to the launching test. Every control-plane
 * call is best-effort: any failure is swallowed so coverage routing can never fail a test.
 *
 * <p>Typically you don't call this directly — use {@code PjacocoExtension} (JUnit 5),
 * {@code PjacocoRule} (JUnit 4), and a client adapter (e.g. {@code PjacocoRestAssured}). The static
 * API here is the seam those adapters and manual integrations build on.
 */
public final class Pjacoco {

    /** System property naming the agent control endpoint base, e.g. {@code http://127.0.0.1:6310}. */
    public static final String CONTROL_URL_PROPERTY = "pjacoco.control-url";

    private static final InheritableThreadLocal<String> CURRENT_TEST_ID = new InheritableThreadLocal<String>();

    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 3000;

    private Pjacoco() {}

    /** @return the configured control endpoint base (e.g. {@code http://127.0.0.1:6310}), or null. */
    public static String controlUrl() {
        String v = System.getProperty(CONTROL_URL_PROPERTY);
        if (v == null) {
            return null;
        }
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    /** @return true when a non-blank {@value #CONTROL_URL_PROPERTY} is set (routing is active). */
    public static boolean enabled() {
        return controlUrl() != null;
    }

    /** @return the test id active on the current thread (inherited by spawned threads), or null. */
    public static String currentTestId() {
        return CURRENT_TEST_ID.get();
    }

    /** Set the active test id on this thread; inherited by threads spawned afterward. */
    public static void setCurrentTestId(String testId) {
        if (testId == null) {
            CURRENT_TEST_ID.remove();
        } else {
            CURRENT_TEST_ID.set(testId);
        }
    }

    /** Clear the active test id on this thread. */
    public static void clearCurrentTestId() {
        CURRENT_TEST_ID.remove();
    }

    /**
     * @return the OpenTelemetry baggage value {@code "test.id=<currentTestId>"} for use as the
     *     {@code baggage} request header value, or null when no test is active. The raw id is kept
     *     (a {@code #} is legal in a header value); only control-URL query params are URL-encoded.
     */
    public static String baggageHeaderValue() {
        String id = currentTestId();
        return id == null ? null : "test.id=" + id;
    }

    /**
     * Open a per-test coverage boundary: {@code POST /__coverage__/test/start?testId=..&shardId=..}.
     * Best-effort; no-op when {@link #enabled()} is false.
     *
     * @param testId the test id (e.g. {@code ClassName#method}); URL-encoded before sending
     * @param shardId optional shard id, or null
     */
    public static void start(String testId, String shardId) {
        if (testId == null) {
            return;
        }
        String query = "testId=" + enc(testId);
        if (shardId != null) {
            query += "&shardId=" + enc(shardId);
        }
        post("start", query);
    }

    /**
     * Close a per-test coverage boundary: {@code POST /__coverage__/test/stop?testId=..&result=..},
     * which flushes one vanilla-JaCoCo {@code <testId>.exec} per test case. Best-effort; no-op when
     * {@link #enabled()} is false.
     *
     * @param testId the test id; URL-encoded before sending
     * @param result e.g. {@code passed} or {@code failed}; URL-encoded before sending
     */
    public static void stop(String testId, String result) {
        if (testId == null) {
            return;
        }
        String query = "testId=" + enc(testId);
        if (result != null) {
            query += "&result=" + enc(result);
        }
        post("stop", query);
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value; // unreachable for UTF-8; keep best-effort
        }
    }

    /** Fire a control-plane request; never throws (coverage routing must not break the suite). */
    private static void post(String op, String query) {
        String base = controlUrl();
        if (base == null) {
            return;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(base + "/__coverage__/test/" + op + "?" + query);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(new byte[0]);
            os.close();
            conn.getResponseCode(); // force the request to be sent
            drain(conn);
        } catch (Exception ignored) {
            // best-effort: swallow so coverage routing can never fail a test
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void drain(HttpURLConnection conn) {
        InputStream in = null;
        try {
            in = conn.getInputStream();
            byte[] buf = new byte[1024];
            while (in.read(buf) > 0) {
                // discard
            }
        } catch (IOException ignored) {
            // ignore
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }
}
