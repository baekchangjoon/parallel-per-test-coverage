plugins {
    `java-gradle-plugin`
}

// The plugin runs in Gradle's JVM (Java 11+); the agent it resolves stays Java 8 compatible.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

repositories { mavenCentral() }

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("pjacoco") {
            id = "io.pjacoco.gradle"
            implementationClass = "io.pjacoco.gradle.PjacocoPlugin"
            displayName = "pjacoco per-test coverage"
            description = "Resolves the pjacoco agent and wires -javaagent for per-test coverage of parallel black-box suites."
        }
    }
}

tasks.test { useJUnitPlatform() }
