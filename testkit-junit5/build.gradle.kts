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
    compileOnly("org.junit.jupiter:junit-jupiter-api:5.10.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("library") {
            artifactId = "pjacoco-testkit-junit5"
            from(components["java"])
            pom {
                name.set("pjacoco-testkit-junit5")
                description.set("pjacoco JUnit 5 extension (per-test coverage boundary).")
            }
        }
    }
}
