// Root of the multi-module build. Modules: agent (the -javaagent), and (incoming) testkit-* +
// gradle-plugin + maven-plugin + samples. Shared coordinates/version live here so every module
// releases in lockstep under one SemVer.
allprojects {
    group = "io.pjacoco"   // NOT org.jacoco — that namespace belongs to the JaCoCo project
    // Overridable so the release workflow can stamp the published version: -PreleaseVersion=x.y.z
    version = providers.gradleProperty("releaseVersion").getOrElse("1.1.0")

    // Library javadoc contains code examples with annotations (e.g. @ExtendWith) that doclint
    // misreads as tags; don't fail the publishable javadoc jars on lint.
    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }

    // CI runtime-JDK matrix: fork the test JVM on a specific JDK via a toolchain when -PtestJavaVersion
    // is set, so the Java 8-compatible modules can be exercised on JDK 8/11 while Gradle itself runs on
    // a newer JDK. (Condy fixtures are major>=55 and cannot load on JDK 8 — those tasks aren't run there.)
    val testJavaVersion = providers.gradleProperty("testJavaVersion")
    pluginManager.withPlugin("java") {
        if (testJavaVersion.isPresent) {
            val toolchains = extensions.getByType<JavaToolchainService>()
            tasks.withType<Test>().configureEach {
                javaLauncher.set(toolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(testJavaVersion.get().toInt()))
                })
            }
        }
    }

    // Shared publication metadata + (credentials-gated) signing for every module that publishes.
    // Maven Central requires the POM metadata below + signatures. NOTE: the POM/signing here is
    // wired, but the actual Central Portal upload step is NOT yet in release.yml — public publish is
    // a deferred, credentials-gated follow-up (REQ-D03; see docs/PUBLISHING.md "Public release").
    // Today release.yml publishes only the agent shaded jar to a GitHub Release; consume the other
    // modules via publishToMavenLocal until then (README "빠른 시작").
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
