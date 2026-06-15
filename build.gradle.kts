// Root of the multi-module build. Modules: agent (the -javaagent), and (incoming) testkit-* +
// gradle-plugin + maven-plugin + samples. Shared coordinates/version live here so every module
// releases in lockstep under one SemVer.
allprojects {
    group = "io.pjacoco"   // NOT org.jacoco — that namespace belongs to the JaCoCo project
    // Overridable so the release workflow can stamp the published version: -PreleaseVersion=x.y.z
    version = providers.gradleProperty("releaseVersion").getOrElse("1.0.0")

    // Library javadoc contains code examples with annotations (e.g. @ExtendWith) that doclint
    // misreads as tags; don't fail the publishable javadoc jars on lint.
    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }

    // Shared publication metadata + (credentials-gated) signing for every module that publishes.
    // Maven Central requires the POM metadata below + signatures; the actual Central Portal upload is
    // wired in the release workflow and only runs when the publishing secrets are present.
    pluginManager.withPlugin("maven-publish") {
        apply(plugin = "signing")
        val repoUrl = "https://github.com/baekchangjoon/parallel-per-test-coverage"
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    url.set(repoUrl)
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            id.set("baekchangjoon")
                            name.set("Changjoon Baek")
                        }
                    }
                    scm {
                        url.set(repoUrl)
                        connection.set("scm:git:$repoUrl.git")
                        developerConnection.set("scm:git:$repoUrl.git")
                    }
                }
            }
        }
        extensions.configure<org.gradle.plugins.signing.SigningExtension> {
            val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
            val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull
            isRequired = signingKey != null && signingKey.isNotBlank()
            if (isRequired) {
                useInMemoryPgpKeys(signingKey, signingPassword)
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }
    }
}
