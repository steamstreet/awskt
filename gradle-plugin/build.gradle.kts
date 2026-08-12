plugins {
    `kotlin-dsl` // brings `java-gradle-plugin` with it
    `maven-publish`
}

group = "com.steamstreet"

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
