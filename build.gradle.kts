// Root of the multi-module build. Modules: agent (the -javaagent), and (incoming) testkit-* +
// gradle-plugin + maven-plugin + samples. Shared coordinates/version live here so every module
// releases in lockstep under one SemVer.
allprojects {
    group = "io.pjacoco"   // NOT org.jacoco — that namespace belongs to the JaCoCo project
    // Overridable so the release workflow can stamp the published version: -PreleaseVersion=x.y.z
    version = providers.gradleProperty("releaseVersion").getOrElse("1.0.0")
}
