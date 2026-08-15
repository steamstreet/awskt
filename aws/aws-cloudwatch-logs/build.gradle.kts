plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

description = "A hand-written CloudWatch Logs Insights client for Kotlin Multiplatform."

/**
 * Depends on **`aws-core` only** — Decision 6, as for every other `aws-*` service module.
 *
 * Notably *not* on `:logging`, and the two are unrelated despite the names: `awskt-logging` writes
 * structured log entries from an application, this reads them back out of CloudWatch afterwards.
 * Nothing in this module emits a log line and nothing in `:logging` queries one.
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
                description.set("A hand-written CloudWatch Logs Insights client for Kotlin Multiplatform.")
            }
        }
    }
}
