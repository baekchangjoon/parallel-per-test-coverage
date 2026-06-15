// Standalone consumer build: resolves the pjacoco plugin + artifacts from the local Maven repo.
// CI publishes them first: ./gradlew :agent:publishToMavenLocal :testkit-*:publishToMavenLocal
// :gradle-plugin:publishToMavenLocal && ./gradlew -p samples/gradle-sample test
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
rootProject.name = "gradle-sample"
