plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

description = "A hand-written SNS client for Kotlin Multiplatform."

/**
 * Depends on **`aws-core` only** — Decision 6, as for every other `aws-*` service module.
 *
 * Note there is **no `kotlinx-serialization-json` dependency here**, unlike every sibling module.
 * SNS speaks the AWS query protocol: form-encoded requests and XML responses, with no JSON in
 * either direction. `Wire.kt` carries the encoder and the reader instead. A `@Serializable`
 * annotation appearing in this module means somebody has misread the protocol.
 *
 * Different artifact from `:lambda:lambda-sns`, which handles SNS notifications *arriving* at a
 * Lambda and contains no client.
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
                description.set("A hand-written SNS client for Kotlin Multiplatform.")
            }
        }
    }
}
