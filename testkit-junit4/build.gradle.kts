plugins {
    `java-library`
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withJavadocJar()
    withSourcesJar()
}

repositories { mavenCentral() }

dependencies {
    api(project(":testkit-core"))
    compileOnly("junit:junit:4.13.2")

    // Tests run on the JUnit 5 platform but exercise the JUnit 4 Rule via its TestWatcher API.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("library") {
            artifactId = "pjacoco-testkit-junit4"
            from(components["java"])
            pom {
                name.set("pjacoco-testkit-junit4")
                description.set("pjacoco JUnit 4 rule (per-test coverage boundary).")
            }
        }
    }
}
