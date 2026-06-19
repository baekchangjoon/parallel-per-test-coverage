plugins {
    java
}

repositories { mavenCentral() }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

// Compile to Java 8 bytecode so jacoco uses ClassFieldProbeArrayStrategy (className/classId
// fields) rather than the condy strategy used for Java 11+ class files. Also keeps the Brave
// scope-hook agent loadable on the legacy-tram Java 8 services.
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

// ── GA-1 Brave scope-hook spike agent (fat jar) ──────────────────────────────────────────────
// Packages BraveScopeHookAgent + advice + bridge + byte-buddy (unshaded; fine for the spike: the
// legacy-tram services do not ship byte-buddy at runtime). Manifest declares premain/agentmain and
// the retransform capability. No brave on the agent classpath — brave is reached reflectively/woven.
val braveSpikeAgent by tasks.registering(Jar::class) {
    archiveFileName.set("brave-scope-spike-agent.jar")
    from(sourceSets.main.get().output)
    // bundle byte-buddy classes (the target JVM has no byte-buddy on its classpath)
    from(configurations.runtimeClasspath.get()
        .filter { it.name.startsWith("byte-buddy") }
        .map { zipTree(it) }) {
        exclude("META-INF/**")
    }
    manifest {
        attributes(
            "Premain-Class" to "io.pjacoco.spike.BraveScopeHookAgent",
            "Agent-Class" to "io.pjacoco.spike.BraveScopeHookAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true"
        )
    }
}
