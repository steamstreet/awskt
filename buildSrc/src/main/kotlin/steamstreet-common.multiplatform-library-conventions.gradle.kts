import org.gradle.kotlin.dsl.kotlin

plugins {
    kotlin("multiplatform")
    id("kotlinx-serialization")

    id("maven-publish")
    id("org.jetbrains.dokka")
    signing
}

kotlin {
    explicitApi()

    jvm {
    }

    js(IR) {
        useCommonJs()
        browser()
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

val dokkaHtml by tasks.getting(org.jetbrains.dokka.gradle.DokkaTask::class)
val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    dependsOn(dokkaHtml)
    archiveClassifier.set("javadoc")
    from(dokkaHtml.outputDirectory)
}

publishing {
    repositories {
        maven {
            name = "sonatype"
            setUrl("https://oss.sonatype.org/service/local/staging/deploy/maven2")
            credentials {
                username = findProperty("sonatypeUsername").toString()
                password = findProperty("sonatypePassword").toString()
            }
        }
    }

    publications.withType<MavenPublication> {
        artifact(tasks.findByName("javadocJar"))
        groupId = "com.steamstreet"
        artifactId = "awskt-${artifactId}"

        println("Description: ${project.description}")

        pom {
            name.set(project.name)
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