pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "e2e-mm-boot3"

// Standalone build (own settings.gradle.kts) — intentionally NOT included in the root
// multi-module build's settings.gradle.kts. This module requires JDK 17 (Spring Boot 3.3.x);
// the root build's JDK-8/11 compat CI legs must never see it.
