plugins {
    `kotlin-dsl` // brings `java-gradle-plugin` with it
    `maven-publish`
    signing
    id("io.github.gradle-nexus.publish-plugin") version "1.3.0"
}

group = "com.steamstreet.awskt"
description =
    "Gradle plugin that packages a Kotlin/Native linuxArm64 executable as an AWS Lambda " +
        "`provided.al2023` bootstrap zip."

// The included build does not participate in the root project's nebula release, so it carries its
// own version. Consumers who resolve the plugin from a repository (rather than via `includeBuild`)
// get this coordinate; `-Pawskt.pluginVersion=` lets the release job stamp the real number without
// editing the file.
version = providers.gradleProperty("awskt.pluginVersion").getOrElse("3.0.0-SNAPSHOT")

repositories {
    mavenCentral()
    gradlePluginPortal()
}

gradlePlugin {
    plugins {
        create("nativeLambda") {
            id = "com.steamstreet.awskt.native-lambda"
            implementationClass = "com.steamstreet.awskt.gradle.NativeLambdaPlugin"
            displayName = "AWSKT native Lambda packaging"
            description =
                "Packages a Kotlin/Native linuxArm64 executable as an AWS Lambda `provided.al2023` " +
                    "bootstrap zip, and builds the libcrypt layer that Kotlin/Native binaries need " +
                    "on Amazon Linux 2023 (KT-55643)."
        }
    }
}

// `java-gradle-plugin` already creates both publications this build needs: `pluginMaven` (the jar,
// at com.steamstreet:awskt-gradle-plugin) and `nativeLambdaPluginMarkerMaven` (the marker that lets
// consumers write `plugins { id("com.steamstreet.awskt.native-lambda") version "..." }` against a
// plain `mavenCentral()`). What the build lacked was the three things Central validates on: the
// sources/javadoc artifacts, the POM metadata, and signatures. The root project gets these from
// buildSrc conventions, which an `includeBuild` cannot see, so they are restated here.
java {
    withSourcesJar()
    withJavadocJar()
}

// `rootProject.name` here is `awskt-gradle-plugin`, which read correctly under the old
// `com.steamstreet` group but stutters under `com.steamstreet.awskt`. Only the jar publication is
// renamed: the marker's coordinates are derived from the plugin id, not from this project's name, and
// `plugins { id(...) }` resolution depends on them staying exactly as the id spells them.
afterEvaluate {
    publishing.publications.withType<MavenPublication>()
        .matching { it.name == "pluginMaven" }
        .forEach { it.artifactId = "gradle-plugin" }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("AWSKT: native Lambda Gradle plugin")
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

tasks.withType<Sign>().configureEach {
    onlyIf { project.hasProperty("signing.keyId") }
}

tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(tasks.withType<Sign>())
}

// This build is separate from the root's, so it opens its own staging repository and lands as its
// own deployment in the Portal — it cannot share the one `final` creates. The endpoints and the
// credential handling mirror the root build; see the note there about closing only, never releasing.
nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))

            username = findProperty("mavenCentralUsername") as String?
            password = findProperty("mavenCentralPassword") as String?
        }
    }
}
