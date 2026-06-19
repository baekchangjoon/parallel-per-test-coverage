// GA-1 (OTel) spike extension — a ContextStorageProvider SPI packaged as an OTel javaagent
// extension. Compiled against the public OTel API as compileOnly (those classes are supplied at
// runtime by the application's own opentelemetry-api dependency / the agent), and built to Java 8
// bytecode so it loads on the Java 8 targets.
plugins {
    java
}

repositories { mavenCentral() }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

tasks.withType<JavaCompile> { options.release.set(8) }

dependencies {
    // compileOnly: the OTel API classes (io.opentelemetry.context.*, io.opentelemetry.api.trace.*)
    // are present at runtime via the application classpath; the extension must NOT bundle them.
    compileOnly("io.opentelemetry:opentelemetry-api:1.45.0")
    compileOnly("io.opentelemetry:opentelemetry-context:1.45.0")
}

// The extension jar: just our classes + the META-INF/services SPI descriptor. No OTel API bundled.
tasks.jar {
    archiveFileName.set("otel-context-spike-ext.jar")
}
