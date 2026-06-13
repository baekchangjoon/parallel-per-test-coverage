plugins {
    java
}

repositories { mavenCentral() }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

// Compile to Java 8 bytecode so jacoco uses ClassFieldProbeArrayStrategy (className/classId
// fields) rather than the condy strategy used for Java 11+ class files.
tasks.withType<JavaCompile> { options.release.set(8) }

dependencies {
    implementation("org.jacoco:org.jacoco.core:0.8.12")
    implementation("net.bytebuddy:byte-buddy:1.14.18")
    implementation("net.bytebuddy:byte-buddy-agent:1.14.18")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // byte-buddy-agent self-attach on JDK 9+
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
