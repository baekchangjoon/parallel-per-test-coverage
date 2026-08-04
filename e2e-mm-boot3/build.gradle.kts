// Standalone JDK-17 Gradle build for the Boot 3 spike app + its boot-success/tracer-path/distributed
// E2Es (REQ-MM-011/012/013). Kept out of the root multi-module build so the JDK-8/11 compat CI legs
// never resolve Spring Boot 3.3.x (which requires JDK 17). Run explicitly:
//   ../gradlew --no-daemon :agent:shadowJar                        // from the repo root — agent jar
//   ../gradlew --no-daemon :testkit-core:jar :testkit-restassured:jar   // sibling testkit jars (MmDistributedFieldE2E)
//   (cd e2e-mm-boot3 && ../gradlew --no-daemon test)
plugins {
    java
    id("org.springframework.boot") version "3.3.5"
}

group = "io.pjacoco.e2emm"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

repositories { mavenCentral() }

// The spike app's sources are committed under app/src/main (Maven layout, pom.xml kept for
// reference) — wired as this build's main sourceSet rather than moved, so the pom.xml stays a
// faithful description of the same sources.
sourceSets {
    main {
        java.setSrcDirs(listOf("app/src/main/java"))
        resources.setSrcDirs(listOf("app/src/main/resources"))
    }
}

dependencies {
    // Spring Boot BOM via platform import (no io.spring.dependency-management plugin needed for
    // a single-module build) — pins web/actuator/aop/tracing-bridge-brave + transitives (brave).
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.3.5"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Required for @Async: Spring's @Async proxying goes through a JDK dynamic proxy
    // (io.pjacoco.spike... reproduces the jdk.proxy* instrumentation crash this E2E guards against).
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Parses .exec files via ExecutionDataReader/Analyzer (MmTracerPathE2E) — same version the agent
    // module embeds (agent/build.gradle.kts jacocoVersion default), unshaded here since this test
    // reads plain jacoco-format output rather than linking against the agent's relocated copy.
    testImplementation("org.jacoco:org.jacoco.core:0.8.12")

    // Sibling build outputs (testkit-core/testkit-restassured) for MmDistributedFieldE2E's
    // HeaderStyle.FIELD harness — version-free glob, sources/javadoc excluded. Path resolved relative
    // to THIS project's directory. Requires a prior sibling build:
    // `../gradlew :testkit-core:jar :testkit-restassured:jar` (see the run instructions above).
    testImplementation(files(layout.projectDirectory.dir("../testkit-core/build/libs").asFileTree.matching {
        include("testkit-core-*.jar"); exclude("*-sources.jar", "*-javadoc.jar") }))
    testImplementation(files(layout.projectDirectory.dir("../testkit-restassured/build/libs").asFileTree.matching {
        include("testkit-restassured-*.jar"); exclude("*-sources.jar", "*-javadoc.jar") }))
    testImplementation("io.rest-assured:rest-assured:5.4.0")   // supplies testkit-restassured's compileOnly dependency
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.bootJar)

    // Resolve both jars robustly relative to THIS project's directory (project.rootDir), not the
    // test JVM's working directory — computed here at configuration time so it is stable
    // regardless of how/where `gradlew test` is invoked from.
    val agentJar = project.rootDir.resolve("../agent/build/libs/pjacoco-agent.jar")
    val bootJarFile = tasks.bootJar.flatMap { it.archiveFile }
    doFirst {
        systemProperty("pjacoco.agentJar", agentJar.absolutePath)
        systemProperty("e2emm.bootJar", bootJarFile.get().asFile.absolutePath)
    }

    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
