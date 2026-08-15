plugins {
    kotlin("multiplatform")
    id("kotlinx-serialization")
    id("com.steamstreet.awskt.native-lambda")
}

// Not published — this is the deployed-Lambda smoke fixture. `linuxArm64` is what actually ships to
// AWS; `macosArm64` exists so the same code is compiled on a developer machine, since linuxArm64 is
// Tier 2 and cannot run tests at all.
kotlin {
    linuxArm64 {
        binaries {
            executable {
                entryPoint = "com.steamstreet.awskt.smoke.main"
            }
        }
    }
    macosArm64 {
        binaries {
            executable {
                entryPoint = "com.steamstreet.awskt.smoke.main"
            }
        }
    }

    sourceSets {
        nativeMain {
            dependencies {
                implementation(projects.lambda.lambdaNative)
                implementation(projects.aws.awsDynamodb)
                implementation(projects.aws.awsS3)
                implementation(projects.aws.awsEventbridge)
                // M8–M10. Every one of these is `linuxArm64` code that nothing else in this
                // repository can execute: Kotlin/Native Tier 2 does not run tests, so a client that
                // compiles for Graviton and faults on it would otherwise ship unnoticed.
                implementation(projects.aws.awsSecretsmanager)
                implementation(projects.aws.awsKms)
                implementation(projects.aws.awsSqs)
                implementation(projects.aws.awsSns)
                implementation(projects.aws.awsScheduler)
                implementation(projects.aws.awsBedrockRuntime)
                implementation(projects.aws.awsCloudwatchLogs)
                implementation(projects.dynamo)
                implementation(projects.env)
                implementation(libs.kotlin.serialization.json)
            }
        }
    }

    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
}
