// Standalone JDK-17 Gradle build for the Boot 3 spike app + its boot-success/tracer-path E2Es
// (REQ-MM-011/012/013). Kept out of the root multi-module build so the JDK-8/11 compat CI legs
// never resolve Spring Boot 3.3.x (which requires JDK 17). Run explicitly:
//   ../gradlew --no-daemon :agent:shadowJar   // from the repo root — builds the agent jar first
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
