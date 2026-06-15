plugins {
    java
    `maven-publish`
}

// Java 8 compatible: test suites may run on JDK 8, and the control-plane HTTP uses
// java.net.HttpURLConnection (NOT java.net.http.HttpClient, which is Java 11+). Zero runtime deps.
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withJavadocJar()
    withSourcesJar()
}

repositories { mavenCentral() }

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("library") {
            artifactId = "pjacoco-testkit"
            from(components["java"])
            pom {
                name.set("pjacoco-testkit")
                description.set("pjacoco test-side control API (framework-neutral).")
            }
        }
    }
}
