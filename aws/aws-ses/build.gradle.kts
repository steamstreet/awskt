plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

description = "A hand-written Amazon SES v2 client for Kotlin Multiplatform."

/**
 * Depends on **`aws-core` only** — Decision 6, as for every other `aws-*` service module.
 *
 * SES v2 is `restJson1`, so this module is shaped like `:aws:aws-scheduler` rather than like
 * `:aws:aws-sns`. The brand similarity to SNS is misleading in exactly the way the Scheduler /
 * EventBridge pair is: SNS speaks the AWS query protocol and needs a hand-written form encoder and
 * XML reader, while SES v2 is a JSON API addressed by method and path and needs neither.
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
                description.set("A hand-written Amazon SES v2 client for Kotlin Multiplatform.")
            }
        }
    }
}
