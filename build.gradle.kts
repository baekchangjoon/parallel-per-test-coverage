// Root of the multi-module build. Modules: agent (the -javaagent), and (incoming) testkit-* +
// gradle-plugin + maven-plugin + samples. Shared coordinates/version live here so every module
// releases in lockstep under one SemVer.
allprojects {
    group = "io.github.beltian.pjacoco"   // NOT org.jacoco — that namespace belongs to the JaCoCo project
    // Overridable so the release workflow can stamp the published version: -PreleaseVersion=x.y.z
    version = providers.gradleProperty("releaseVersion").getOrElse("2.0.0")

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
    // Maven Central requires the POM metadata below + signatures. The Central Portal upload is
    // wired in release.yml (secrets-gated: staging publish -> centralBundle -> Portal API upload);
    // see docs/PUBLISHING.md "Public release".
    pluginManager.withPlugin("maven-publish") {
        apply(plugin = "signing")
        val repoUrl = "https://github.com/beltian/parallel-per-test-coverage"
        extensions.configure<PublishingExtension> {
            // Central Portal upload path: every module publishes into ONE root-level staging dir,
            // which the root `centralBundle` task zips into the Portal Publisher API bundle format
            // (repository-layout tree incl. .asc signatures + Gradle-generated checksums).
            // Run from a clean checkout (release.yml always is) — a dirty staging dir would leak
            // stale files into the bundle.
            repositories {
                maven {
                    name = "centralStaging"
                    url = uri(rootProject.layout.buildDirectory.dir("staging-deploy"))
                }
            }
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
                            id.set("beltian")
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
        // Embed the publication POM + pom.properties into the testkit jars (META-INF/maven/...):
        // the release-jar onboarding path is `mvn install:install-file -Dfile=<jar>`, and without
        // an embedded POM that generates a stub with NO dependency info — consumers adding only
        // pjacoco-testkit-junit5 then hit NoClassDefFoundError for testkit-core classes
        // (2026-07-27 dogfooding finding U3). verifyJarEmbedsPom (wired into check) asserts the
        // entries exist, encoding the way the gap was found (inspecting the jar for META-INF/maven).
        if (project.name.startsWith("testkit")) {
            afterEvaluate {
                val publication = extensions.getByType<PublishingExtension>()
                        .publications.named("library").get() as MavenPublication
                val gid = group.toString()
                val aid = publication.artifactId
                val ver = version.toString()
                val mavenDir = "META-INF/maven/$gid/$aid"
                val propsFile = layout.buildDirectory.file("embedPom/pom.properties")
                val genProps = tasks.register("generatePomProperties") {
                    // The coordinates MUST be an input: without it, re-building with a different
                    // -PreleaseVersion left the task UP-TO-DATE and shipped a jar whose pom.xml and
                    // pom.properties disagreed on the version — exactly the install-file GAV
                    // corruption this block exists to prevent.
                    inputs.property("coordinates", "$gid:$aid:$ver")
                    outputs.file(propsFile)
                    doLast {
                        propsFile.get().asFile.writeText(
                                "groupId=$gid\nartifactId=$aid\nversion=$ver\n")
                    }
                }
                tasks.named<Jar>("jar") {
                    into(mavenDir) {
                        from(tasks.named("generatePomFileForLibraryPublication")) { rename { "pom.xml" } }
                        from(genProps)
                    }
                }
                // Configuration-time provider (not tasks...get() inside doLast, which captures the
                // Project and breaks the configuration cache).
                val jarArchive = tasks.named<Jar>("jar").flatMap { it.archiveFile }
                val verifyJarEmbedsPom = tasks.register("verifyJarEmbedsPom") {
                    inputs.file(jarArchive)
                    doLast {
                        java.util.zip.ZipFile(jarArchive.get().asFile).use { zip ->
                            val pomEntry = zip.getEntry("$mavenDir/pom.xml")
                            require(pomEntry != null) {
                                "$aid jar must embed $mavenDir/pom.xml (install-file GAV + dependencies)"
                            }
                            require(zip.getEntry("$mavenDir/pom.properties") != null) {
                                "$aid jar must embed $mavenDir/pom.properties"
                            }
                            // The two embedded files must agree with the built version — catches a
                            // stale pom.properties surviving a version change.
                            val pomXml = zip.getInputStream(pomEntry).bufferedReader().readText()
                            require(pomXml.contains("<version>$ver</version>")) {
                                "$aid embedded pom.xml version must be $ver"
                            }
                            val props = zip.getInputStream(zip.getEntry("$mavenDir/pom.properties"))
                                    .bufferedReader().readText()
                            require(props.contains("version=$ver")) {
                                "$aid embedded pom.properties version must be $ver"
                            }
                        }
                    }
                }
                tasks.named("check") { dependsOn(verifyJarEmbedsPom) }
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

// Central Portal bundle: zip of the aggregated staging repository tree. Upload it (secrets-gated,
// wired in release.yml) with:
//   curl -f -X POST -H "Authorization: Bearer $(printf '%s:%s' "$USER" "$TOKEN" | base64)" \
//     -F bundle=@build/central-bundle.zip \
//     "https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED"
// USER_MANAGED = artifacts wait for a manual "Publish" click in the Portal UI (deliberate for the
// first releases; switch to AUTOMATIC once the pipeline is trusted).
tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Zips the staging-deploy tree into a Central Portal Publisher API bundle."
    from(layout.buildDirectory.dir("staging-deploy"))
    destinationDirectory.set(layout.buildDirectory)
    archiveFileName.set("central-bundle.zip")
}
