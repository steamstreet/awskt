plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

description = "A hand-written SNS client for Kotlin Multiplatform."

/**
 * Depends on **`aws-core` only** — Decision 6, as for every other `aws-*` service module.
 *
 * ### On the JSON dependency, which this module originally did without
 *
 * The first version of this file said there was deliberately no `kotlinx-serialization-json` here,
 * because SNS speaks the query protocol — form-encoded requests, XML responses, no JSON in either
 * direction. **That reasoning is still correct about the protocol and was wrong as a rule for the
 * module**, and mobile push is what exposed the difference.
 *
 * A mobile push message carries per-platform payloads as a JSON envelope whose values are
 * themselves JSON documents *as strings* (see `MobilePush.kt`). That envelope is **application data
 * SNS carries opaquely**, not wire framing — the protocol underneath it is still form-encoded — and
 * building it by string concatenation breaks the first time a notification body contains a quote or
 * a newline, which for user-generated content is immediately.
 *
 * So the rule is narrowed rather than dropped: **no `@Serializable` and no JSON on the
 * request/response path** — a `@SerialName` in this module still means somebody has misread the
 * protocol, and `Wire.kt` remains the only encoder and reader for it. JSON is used solely to build
 * payloads that pass through SNS untouched.
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
                // `api`, not `implementation`: MobilePushMessageBuilder.platform takes a JsonObject,
                // so the type is on this module's public surface and a consumer needs it to compile.
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
                description.set("A hand-written SNS client for Kotlin Multiplatform.")
            }
        }
    }
}
