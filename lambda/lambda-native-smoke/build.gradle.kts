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
