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
        groupId = "com.steamstreet"
        artifactId = "awskt-${artifactId}"

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