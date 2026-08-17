import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    kotlin("multiplatform")
    id("kotlinx-serialization")

    id("maven-publish")
    id("org.jetbrains.dokka")
    signing
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

//val dokkaHtml by tasks.getting(org.jetbrains.dokka.gradle.DokkaTask::class)
val dokkaOutputDir = project.layout.buildDirectory.dir("dokka")
tasks.getByName<DokkaTask>("dokkaHtml") {
    outputDirectory.set(file(dokkaOutputDir))
}

val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    dependsOn(tasks.dokkaHtml)
    archiveClassifier.set("javadoc")
    from(dokkaOutputDir)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
        optIn.add("kotlin.time.ExperimentalTime")
    }

    /**
     * ABI dumps, so a change to a published artifact's public surface shows up as a reviewable diff
     * rather than as a consumer's compile error after release.
     *
     * This is the Kotlin plugin's **built-in** ABI validation, not the standalone
     * `org.jetbrains.kotlinx:binary-compatibility-validator` the plan names. Two reasons, and the
     * second is the deciding one:
     *  - it ships inside the Kotlin plugin already applied here, so it cannot drift out of step
     *    with the compiler the way a separately-versioned plugin can (a real risk on a repo that
     *    just moved 2.2.21 → 2.3.21 to satisfy the Ktor gate);
     *  - it dumps **klibs**, so `linuxArm64` — the target this whole project exists for — is
     *    covered. The JVM-only dump BCV was written for would not have covered it.
     *
     * `keepUnsupportedTargets` matters more than it looks: without it, running the dump on macOS
     * would silently drop the Linux targets and running it on CI would drop the Apple ones, so the
     * two hosts would fight over the checked-in file forever.
     *
     * Tasks: `updateKotlinAbi` to regenerate, `checkKotlinAbi` to verify (wired into `check`).
     *
     * Note that a klib's ABI dump records the library's unique name, which embeds the group. A
     * change to the group therefore rewrites every dump without any declaration having moved, and
     * `checkKotlinAbi` fails until `updateKotlinAbi` is run.
     */
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        enabled.set(true)
        klib {
            enabled.set(true)
            keepUnsupportedTargets.set(true)
        }
    }
}

publishing {
    publications.withType<MavenPublication> {
        artifact(javadocJar)

        pom {
            name.set("AWSKT: ${project.name}")
            description.set(project.description)
            url.set("https://github.com/steamstreet/awskt")

            licenses {
                license {
                    name.set("MIT")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
            developers {
                developer {
                    organization.set("SteamStreet LLC")
                    organizationUrl.set("https://github.com/steamstreet")
                }
            }
            scm {
                url.set("https://github.com/steamstreet/awskt")
            }
        }
    }
}

// Nothing rewrites the artifactIds here. The Kotlin plugin's own names — `<module>` for the metadata
// publication and `<module>-<target>` for each target — are already the right ones underneath the
// `com.steamstreet.awskt` group. Forcing a prefix on top of them is what produced the split
// namespace that 3.0.0 shipped with, since the Kotlin plugin assigns the target names from a later
// `afterEvaluate` than a publication block runs in.

signing {
    sign(publishing.publications)
}

tasks.withType<Sign> {
    onlyIf { project.hasProperty("signing.keyId") }
}


val signingTasks = tasks.withType<Sign>()
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(signingTasks)
}