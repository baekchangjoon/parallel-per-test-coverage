package io.pjacoco.e2emm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MmBoot3BootE2E {

    private static final Pattern TOMCAT_PORT_LOG = Pattern.compile("Tomcat started on port\\D*(\\d+)");

    /**
     * REQ-MM-011: spike에서 includes=* 기본값이 jdk.proxy3 계측 -> $jacocoInit IllegalAccessError로
     * 부팅 실패했다. 기본값 그대로 부팅이 성공해야 한다(Task 4의 프록시 제외가 공급).
     */
    @Test
    @DisplayName("REQ-MM-011: Boot 3 boots with default includes=*")
    void bootsWithDefaultIncludes() throws Exception {
        File log = Files.createTempFile("mm-boot3-boot", ".log").toFile();
        Process app = new ProcessBuilder(
                javaBin(),
                "-javaagent:" + agentJar(),   // no agent options -> includes=* default
                "-jar", bootJar(),
                "--server.port=0")
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start();
        try {
            int port = awaitPortFromLog(log, 60);
            int status = get("http://127.0.0.1:" + port + "/sync");
            assertEquals(200, status, "app must respond 200 on /sync once booted");
            assertFalse(logContains(log, "IllegalAccessError"),
                    "proxy instrumentation crash must not occur (REQ-MM-011 regression)");
        } finally {
            // Teardown on every exit path (success, assertion failure, or exception above).
            app.destroyForcibly();
            app.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static String javaBin() {
        String javaHome = System.getProperty("java.home");
        return javaHome + File.separator + "bin" + File.separator + "java";
    }

    private static String agentJar() {
        String path = System.getProperty("pjacoco.agentJar");
        if (path == null || path.isBlank()) {
            fail("pjacoco.agentJar system property not set (expected from build.gradle.kts test task)");
        }
        File jar = new File(path);
        if (!jar.isFile()) {
            fail("agent jar not found at " + jar.getAbsolutePath()
                    + " -- run `./gradlew --no-daemon :agent:shadowJar` from the repo root first");
        }
        return jar.getAbsolutePath();
    }

    private static String bootJar() {
        String path = System.getProperty("e2emm.bootJar");
        if (path == null || path.isBlank()) {
            fail("e2emm.bootJar system property not set (expected from build.gradle.kts test task)");
        }
        File jar = new File(path);
        if (!jar.isFile()) {
            fail("boot jar not found at " + jar.getAbsolutePath() + " -- the bootJar task should have built it");
        }
        return jar.getAbsolutePath();
    }

    private static int awaitPortFromLog(File log, int timeoutSeconds) throws IOException, InterruptedException {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            String content = Files.readString(log.toPath());
            Matcher matcher = TOMCAT_PORT_LOG.matcher(content);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
            if (content.contains("APPLICATION FAILED TO START") || content.contains("Error starting ApplicationContext")) {
                fail("app failed to start within " + timeoutSeconds + "s:\n" + content);
            }
            Thread.sleep(200);
        }
        fail("app did not log a Tomcat startup line within " + timeoutSeconds + "s:\n" + Files.readString(log.toPath()));
        throw new AssertionError("unreachable");
    }

    private static boolean logContains(File log, String needle) throws IOException {
        return Files.readString(log.toPath()).contains(needle);
    }

    private static int get(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }
}
