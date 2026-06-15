plugins {
    `java-library`
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
    compileOnly("io.rest-assured:rest-assured:5.4.0")

    testImplementation("io.rest-assured:rest-assured:5.4.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
