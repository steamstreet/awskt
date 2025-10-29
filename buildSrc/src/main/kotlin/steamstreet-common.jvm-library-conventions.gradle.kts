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