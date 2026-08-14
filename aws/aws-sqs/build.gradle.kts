plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

description = "A hand-written SQS client for Kotlin Multiplatform."

/**
 * Depends on **`aws-core` only** — Decision 6, as for every other `aws-*` service module.
 *
 * Note this is a different artifact from `:lambda:lambda-sqs`, which handles SQS events *arriving*
 * at a Lambda and has no client in it at all. The two are complementary and neither depends on the
 * other: `awskt-lambda-sqs` consumes an invocation payload, `awskt-aws-sqs` calls the service.
 */
kotlin {
    explicitApi()

    jvm()
    linuxX64()
    linuxArm64()
    macosArm64()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":aws:aws-core"))
                api(libs.kotlin.serialization.json)
                implementation(libs.kotlin.coroutines.core)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.ktor.client.mock)
                implementation(libs.kotlin.coroutines.test)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("A hand-written SQS client for Kotlin Multiplatform.")
            }
        }
    }
}
