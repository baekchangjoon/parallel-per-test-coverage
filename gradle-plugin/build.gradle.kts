plugins {
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "1.2.1"
    `maven-publish`   // also lets the sample resolve the plugin from mavenLocal in CI
}

// The plugin runs in Gradle's JVM (Java 11+); the agent it resolves stays Java 8 compatible.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

repositories { mavenCentral() }

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation(gradleTestKit())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    website.set("https://github.com/baekchangjoon/parallel-per-test-coverage")
    vcsUrl.set("https://github.com/baekchangjoon/parallel-per-test-coverage.git")
    plugins {
        create("pjacoco") {
            id = "io.pjacoco.gradle"
            implementationClass = "io.pjacoco.gradle.PjacocoPlugin"
            displayName = "pjacoco per-test coverage"
            description = "Resolves the pjacoco agent and wires -javaagent for per-test coverage of parallel black-box suites."
            tags.set(listOf("coverage", "jacoco", "testing", "per-test", "javaagent"))
        }
    }
}

// The functional test (TestKit) needs the freshly built agent (shaded) + testkit-core jars, served to
// the generated consumer build via a flatDir repo — no publishing required. evaluationDependsOn makes
// those projects' tasks resolvable here regardless of configuration order.
evaluationDependsOn(":agent")
evaluationDependsOn(":testkit-core")
val agentShadowJar = project(":agent").tasks.named("shadowJar")
val testkitJar = project(":testkit-core").tasks.named("jar")

tasks.test {
    useJUnitPlatform()
    dependsOn(agentShadowJar, testkitJar)
    systemProperty("pjacoco.it.agentJar", agentShadowJar.get().outputs.files.singleFile.absolutePath)
    systemProperty("pjacoco.it.testkitJar", testkitJar.get().outputs.files.singleFile.absolutePath)
    systemProperty("pjacoco.it.version", project.version.toString())
}
