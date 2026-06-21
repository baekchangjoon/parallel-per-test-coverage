plugins {
    java
    jacoco
    id("com.gradleup.shadow") version "8.3.5"
    `maven-publish`
}

jacoco { toolVersion = "0.8.12" }

// group + version are set for all modules in the root build.gradle.kts (lockstep SemVer).

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

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.shadowJar)
    val shadedJar = tasks.shadowJar.flatMap { it.archiveFile }
    doFirst { systemProperty("pjacoco.shadedJar", shadedJar.get().asFile.absolutePath) }
}

tasks.shadowJar {
    archiveFileName.set("pjacoco-agent.jar")
    archiveClassifier.set("")   // publish as the primary io.pjacoco:pjacoco-agent jar (no "all" classifier)
    // Self-contained agent (spec §3): relocate the embedded jacoco-core + byte-buddy so the agent
    // cannot clash with the same libraries on the target app's classpath. The jacoco-internal hook
    // matchers use suffix matching (nameEndsWith) so they resolve the relocated classes here AND the
    // un-relocated ones used by the in-process integration tests.
    relocate("org.jacoco", "io.pjacoco.shaded.jacoco")
    relocate("net.bytebuddy", "io.pjacoco.shaded.bytebuddy")
    // jacoco-core depends on org.objectweb.asm; relocate it too. Otherwise the embedded jacoco resolves
    // org.objectweb.asm from the TARGET app's classpath — and if that app ships an older ASM (e.g. a
    // transitive org.ow2.asm:asm:9.1), jacoco's Instrumenter throws NoSuchMethodError on the newer ASM
    // API it calls (e.g. Type.getArgumentCount(String), added in ASM 9.6) for classes whose bytecode
    // exercises that path (methods with exception handlers, etc.). ProbeInstrumentation.transform's
    // catch(Throwable) then silently swallows it, dropping those classes' coverage with no signal.
    relocate("org.objectweb.asm", "io.pjacoco.shaded.asm")
    manifest {
        attributes(
            "Premain-Class" to "io.pjacoco.agent.Bootstrap",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
            "Implementation-Version" to project.version.toString()
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
    integrationTestImplementation("io.zipkin.brave:brave:5.16.0")
}

// TargetService/SampleServlet are kept at Java 8 bytecode so jacoco uses ClassFieldProbeArrayStrategy
// here. Java 11+ bytecode (which makes jacoco pick CondyProbeArrayStrategy) is ALSO supported by the
// routing hook — proven by the condyFixture + ProbeRoutingCondyIT below; these javax/jetty fixtures
// simply don't need bumping.
tasks.named<JavaCompile>("compileIntegrationTestJava") {
    options.release.set(8)
}

// ---- condyFixture source set: a SUT class compiled to Java 11+ bytecode (major >= 55) ----
// Any major >= 55 makes jacoco's ProbeArrayStrategyFactory pick CondyProbeArrayStrategy (Java 11 and
// Java 17 take the IDENTICAL branch), so ProbeRoutingCondyIT + e2eCondyTest prove the Condy path.
// The bytecode level is a property: default 11 keeps the fixture buildable on the JDK 11 baseline;
// CI's e2e matrix passes -PcondyRelease=17 / 21 (where that JDK is available) to exercise newer
// bytecode too. The output is a loadable .class resource on integrationTest's classpath.
val condyRelease = providers.gradleProperty("condyRelease").map { it.toInt() }.getOrElse(11)
val condyFixtureSrc = sourceSets.create("condyFixture") {
    java.srcDir("src/condyFixture/java")
}
tasks.named<JavaCompile>("compileCondyFixtureJava") {
    options.release.set(condyRelease)
}
sourceSets.named("integrationTest") {
    compileClasspath += condyFixtureSrc.output   // CondyServlet/CondyE2E reference CondyTarget
    runtimeClasspath += condyFixtureSrc.output
}

// In-process mechanism ITs (manual ByteBuddyAgent self-attach). Excludes the real-agent e2es.
val integrationTest = tasks.register<Test>("integrationTest") {
    description = "In-process probe-mechanism integration tests"
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform { excludeTags("e2e", "e2econdy") }
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
    shouldRunAfter(tasks.test)
}

// End-to-end on MODERN (Java 11+) bytecode: real -javaagent attached, instrumenting CondyTarget
// (major 55 -> jacoco's CondyProbeArrayStrategy). Proves per-test routing works end-to-end for the
// Condy path, not just in-process. Own control port + output dir so it runs beside the other e2es.
val e2eCondyTest = tasks.register<Test>("e2eCondyTest") {
    description = "End-to-end per-test coverage on Java 11+ (Condy) bytecode with the real -javaagent"
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform { includeTags("e2econdy") }
    outputs.upToDateWhen { false }
    dependsOn(tasks.shadowJar)
    val agentJar = layout.buildDirectory.file("libs/pjacoco-agent.jar")
    doFirst {
        jvmArgs(
            "-javaagent:${agentJar.get().asFile.absolutePath}=destfile=build/coverage-condy,port=6312,includes=com.example.app.CondyTarget",
            "-DPJACOCO_E2E_OUTPUT=build/coverage-condy"
        )
    }
    environment("PJACOCO_COMMIT", "e2e-condy-deadbeef")
    extensions.getByType(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class.java).isEnabled = false
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
    val agentJar = layout.buildDirectory.file("libs/pjacoco-agent.jar")
    doFirst {
        jvmArgs(
            "-javaagent:${agentJar.get().asFile.absolutePath}=destfile=build/coverage,port=6310,includes=com.example.app.TargetService",
            "-DPJACOCO_E2E_OUTPUT=build/coverage"
        )
    }
    // commitSha for the manifest header (spec §5.2)
    environment("PJACOCO_COMMIT", "e2e-deadbeef")
}

// ---- e2eJakarta source set: real-agent e2e against the jakarta.servlet stack (Servlet 5+, Jetty 11) ----
// Kept separate from integrationTest because jakarta Jetty 11 and javax Jetty 9.4 share the
// org.eclipse.jetty.* package and cannot coexist on one classpath. Reuses the integrationTest
// TargetService (the instrumented SUT) via its compiled output.
val e2eJakartaSrc = sourceSets.create("e2eJakarta") {
    java.srcDir("src/e2eJakarta/java")
    compileClasspath += sourceSets["main"].output + sourceSets["integrationTest"].output
    runtimeClasspath += sourceSets["main"].output + sourceSets["integrationTest"].output
}
val e2eJakartaImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
@Suppress("UNUSED_VARIABLE")
val e2eJakartaRuntimeOnly by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}
dependencies {
    "e2eJakartaImplementation"("jakarta.servlet:jakarta.servlet-api:5.0.0")
    "e2eJakartaImplementation"("org.eclipse.jetty:jetty-server:11.0.20")
    "e2eJakartaImplementation"("org.eclipse.jetty:jetty-servlet:11.0.20")
}
// SUT class (SampleServletJakarta) compiled to Java 8 bytecode — same probe-strategy reason as integrationTest.
tasks.named<JavaCompile>("compileE2eJakartaJava") {
    options.release.set(8)
}
// End-to-end on the jakarta stack: own control port (6311) and output dir so it runs beside e2eTest.
val e2eJakartaTest = tasks.register<Test>("e2eJakartaTest") {
    description = "End-to-end spec acceptance on the jakarta.servlet stack with the real -javaagent attached"
    group = "verification"
    testClassesDirs = e2eJakartaSrc.output.classesDirs
    classpath = e2eJakartaSrc.runtimeClasspath
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    dependsOn(tasks.shadowJar)
    val agentJar = layout.buildDirectory.file("libs/pjacoco-agent.jar")
    doFirst {
        jvmArgs(
            "-javaagent:${agentJar.get().asFile.absolutePath}=destfile=build/coverage-jakarta,port=6311,includes=com.example.app.TargetService",
            "-DPJACOCO_E2E_OUTPUT=build/coverage-jakarta"
        )
    }
    environment("PJACOCO_COMMIT", "e2e-jakarta-deadbeef")
    extensions.getByType(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class.java).isEnabled = false
}

// ---- otelE2e source set: forked-JVM OTel scope weave E2E (REQ-004, REQ-006) ----
// Kept separate from integrationTest to avoid OTel API bleeding into tests that assert
// "no OTel on classpath" (NoTracerAttachIT). Only added to 'check' when the OTel agent jar is
// present; the Gradle task itself is always registered (so CI can invoke it explicitly).
val otelE2eSrc = sourceSets.create("otelE2e") {
    java.srcDir("src/otelE2e/java")
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}
val otelE2eImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
@Suppress("UNUSED_VARIABLE")
val otelE2eRuntimeOnly by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}
dependencies {
    // OTel API: only in otelE2e (SUT + test harness). NOT in main or integrationTest classpath.
    "otelE2eImplementation"("io.opentelemetry:opentelemetry-api:1.45.0")
    "otelE2eImplementation"("io.opentelemetry:opentelemetry-context:1.45.0")
}
// SUT classes at Java 8 bytecode so jacoco uses the field probe-array strategy.
tasks.named<JavaCompile>("compileOtelE2eJava") {
    options.release.set(8)
}

// Forked-JVM OTel smoke E2E: proves the scope weave fires with the REAL OTel javaagent and
// attributes async coverage to the same trace (REQ-004, REQ-006).
val otelWeaveE2e = tasks.register<Test>("otelWeaveE2e") {
    description = "Forked-JVM OTel scope weave E2E: proves REQ-004 (request thread) and REQ-006 (async attribution)"
    group = "verification"
    testClassesDirs = otelE2eSrc.output.classesDirs
    classpath = otelE2eSrc.runtimeClasspath
    useJUnitPlatform { includeTags("otelE2e") }
    outputs.upToDateWhen { false }
    dependsOn(tasks.shadowJar)
    val agentJar = layout.buildDirectory.file("libs/pjacoco-agent.jar")
    val otelAgentJar = layout.buildDirectory.file("otel-agent/opentelemetry-javaagent.jar")
    doFirst {
        systemProperty("pjacoco.shadedJar", agentJar.get().asFile.absolutePath)
        systemProperty("pjacoco.otelAgent", otelAgentJar.get().asFile.absolutePath)
    }
    extensions.getByType(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class.java).isEnabled = false
}

tasks.named("check") { dependsOn(integrationTest) }

// ---- self-coverage of the agent (io.pjacoco.agent.*) by the in-process suites ----
// The e2e runs the SHADED agent in a forked JVM: self-coverage attribution is messy and it would
// double-instrument the SUT, so jacoco is disabled there (the e2e still runs in CI). Coverage is
// measured from `test` (unit) + `integrationTest` (in-process mechanism).
e2eTest.configure {
    extensions.getByType(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class.java).isEnabled = false
}

tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"), integrationTest)
    executionData(tasks.named<Test>("test").get(), integrationTest.get())
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

// ---- publishing: the agent is published as its SHADED jar (relocated jacoco-core + byte-buddy) so
// consumers/plugins resolving io.pjacoco:pjacoco-agent get the self-contained -javaagent, not the
// unshaded jar. (Maven Central metadata/signing is layered on in the publishing phase.)
publishing {
    publications {
        create<MavenPublication>("agent") {
            artifactId = "pjacoco-agent"
            artifact(tasks.shadowJar)
            pom {
                name.set("pjacoco-agent")
                description.set("Per-test coverage Java agent for parallel out-of-process black-box suites.")
            }
        }
    }
}
