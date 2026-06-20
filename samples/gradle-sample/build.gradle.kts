plugins {
    java
    id("io.pjacoco.gradle") version "1.2.0"
}

repositories {
    mavenLocal()
    mavenCentral()
}

val pjacocoVersion = "1.2.0"
dependencies {
    testImplementation("io.pjacoco:pjacoco-testkit-junit5:$pjacocoVersion")
    testImplementation("io.pjacoco:pjacoco-testkit-junit4:$pjacocoVersion")
    testImplementation("io.pjacoco:pjacoco-testkit-restassured:$pjacocoVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("io.rest-assured:rest-assured:5.4.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.eclipse.jetty:jetty-server:9.4.55.v20240627")
    testImplementation("org.eclipse.jetty:jetty-servlet:9.4.55.v20240627")
    testImplementation("javax.servlet:javax.servlet-api:3.1.0")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.3")  // run JUnit 4 on the platform (AC4)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// AC1 + AC4: the plugin injects the agent + -Dpjacoco.control-url into the `test` JVM (in-JVM
// black-box). The REST Assured suite runs in parallel; per-test routing keeps each test's coverage
// separate.
pjacoco {
    port.set(6330)
    includes.set(listOf("com.sample.Alpha", "com.sample.Beta"))
    attachTo.set(listOf("test", "unitTest"))
}

// Pure-unit in-process per-test coverage demo (no servlet/HTTP): the SUT is called directly on the
// test thread; the in-process extension (auto-registered) brackets each test. Distinct output dir.
sourceSets {
    create("unitTest") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}
val unitTestImplementation by configurations.getting { extendsFrom(configurations.testImplementation.get()) }
val unitTestRuntimeOnly by configurations.getting { extendsFrom(configurations.testRuntimeOnly.get()) }

val unitTest = tasks.register<Test>("unitTest") {
    description = "Pure-unit in-process per-test coverage demo"
    group = "verification"
    testClassesDirs = sourceSets["unitTest"].output.classesDirs
    classpath = sourceSets["unitTest"].runtimeClasspath
    useJUnitPlatform()
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
}
