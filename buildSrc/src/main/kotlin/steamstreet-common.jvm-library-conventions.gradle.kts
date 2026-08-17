import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    kotlin("jvm")
    id("kotlinx-serialization")

    id("maven-publish")
    id("org.jetbrains.dokka")
    signing
}

kotlin {
    explicitApiWarning()
    compilerOptions {
        freeCompilerArgs.addAll("-Xcontext-parameters")
        optIn.add("kotlin.time.ExperimentalTime")
    }
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }

    /**
     * ABI dumps — see the fuller note in the multiplatform conventions. `updateLegacyAbi`
     * regenerates, `checkLegacyAbi` verifies and is wired into `check`.
     *
     * This is what makes the 3.0 break reviewable: the `dynamo` / `dynamokt` / `dynamokt-exposed`
     * dumps taken *before* M5a are the baseline the migration diff is read against, and that
     * artifact does not exist today.
     */
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        enabled.set(true)
    }
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.named<Jar>("javadocJar") {
    from(tasks.named("dokkaJavadoc"))
}

publishing {
    publications.create<MavenPublication>("maven") {
        // Group and artifactId are inherited: `com.steamstreet.awskt` from the root project and the
        // module's own name. Through 3.0.0 this prefixed the artifactId with `awskt-` to make up for
        // a group that did not name the project.
        from(components["java"])

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

signing {
    sign(publishing.publications)
}

tasks.withType<Sign> {
    onlyIf { project.hasProperty("signing.keyId") }
}