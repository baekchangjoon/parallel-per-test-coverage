// Auto-provisions JDK toolchains (e.g. JDK 8 for the runtime-compat CI matrix) when not present.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "jacocoagent-parallel"

include("agent")
include("testkit-core")
include("testkit-junit5")
include("testkit-junit4")
include("testkit-restassured")
include("gradle-plugin")
