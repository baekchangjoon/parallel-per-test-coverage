plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "io.pjacoco"   // NOT org.jacoco — that namespace belongs to the JaCoCo project
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories { mavenCentral() }

// jacoco-core version is overridable so the version canary can sweep a matrix (Task 18).
val jacocoVersion = providers.gradleProperty("jacocoVersion").getOrElse("0.8.12")

dependencies {
    implementation("org.jacoco:org.jacoco.core:$jacocoVersion")
    implementation("net.bytebuddy:byte-buddy:1.14.18")
    compileOnly("javax.servlet:javax.servlet-api:3.1.0")          // provided by the target app

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.jacoco:org.jacoco.core:$jacocoVersion")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("javax.servlet:javax.servlet-api:3.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

tasks.shadowJar {
    archiveFileName.set("jacocoagent-parallel.jar")
    // v1: fat jar without relocation. Single-agent deployment means no clash with a separately
    // attached jacoco; keeping packages unrelocated lets the ByteBuddy hook target the plain
    // org.jacoco.core.internal.instr.* names (matching the validated spike).
    manifest {
        attributes(
            "Premain-Class" to "io.pjacoco.agent.Bootstrap",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true"
        )
    }
}

// ---- integrationTest source set: in-process mechanism ITs + the e2e (split by tag at run time) ----
sourceSets {
    create("integrationTest") {
        java.srcDir("src/integrationTest/java")
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    }
}
val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
@Suppress("UNUSED_VARIABLE")
val integrationTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}
dependencies {
    integrationTestImplementation("net.bytebuddy:byte-buddy-agent:1.14.18")
    integrationTestImplementation("org.eclipse.jetty:jetty-server:9.4.55.v20240627")
    integrationTestImplementation("org.eclipse.jetty:jetty-servlet:9.4.55.v20240627")
}

// App-under-test classes (TargetService, SampleServlet) must be Java 8 bytecode so jacoco uses the
// ClassFieldProbeArrayStrategy (className/classId fields the advice reflects). Java 11+ regular
// classes use CondyProbeArrayStrategy instead — out of v1 scope.
tasks.named<JavaCompile>("compileIntegrationTestJava") {
    options.release.set(8)
}

// In-process mechanism ITs (manual ByteBuddyAgent self-attach). Excludes the real-agent e2e.
val integrationTest = tasks.register<Test>("integrationTest") {
    description = "In-process probe-mechanism integration tests"
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform { excludeTags("e2e") }
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
    shouldRunAfter(tasks.test)
}

// End-to-end: real -javaagent attached, asserts via .exec files only. Separate JVM from the
// in-process ITs so TargetService is never instrumented twice.
val e2eTest = tasks.register<Test>("e2eTest") {
    description = "End-to-end spec acceptance with the real -javaagent attached"
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform { includeTags("e2e") }
    outputs.upToDateWhen { false }      // always re-run: exercises the freshly built agent jar (side-effecting)
    dependsOn(tasks.shadowJar)
    val agentJar = layout.buildDirectory.file("libs/jacocoagent-parallel.jar")
    doFirst {
        jvmArgs(
            "-javaagent:${agentJar.get().asFile.absolutePath}=destfile=build/coverage,port=6310,includes=com.example.app.TargetService",
            "-DPJACOCO_E2E_OUTPUT=build/coverage"
        )
    }
    // commitSha for the manifest header (spec §5.2)
    environment("PJACOCO_COMMIT", "e2e-deadbeef")
}

tasks.named("check") { dependsOn(integrationTest) }
