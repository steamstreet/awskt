plugins {
    id("steamstreet-common.multiplatform-library-conventions")
    id("steamstreet-common.container-test-conventions")
}

description = "A signed OpenSearch transport for Kotlin Multiplatform."

/**
 * Depends on **`aws-core` only** — Decision 6, as for every other `aws-*` service module.
 *
 * Deliberately *not* a port of the OpenSearch API. `AwsServiceClient.callRaw` is already
 * service-agnostic, so what this module contributes is the two things `aws-core` cannot know: the
 * `es` signing dialect and OpenSearch's error envelope, which is not an AWS envelope at all. Query
 * construction and response decoding stay with the caller, who is already doing both with
 * `buildJsonObject` and kotlinx.serialization.
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

        jvmTest {
            dependencies {
                // A real OpenSearch to run the transport against. It is the only oracle that can
                // say the error *envelope* this module parses is the envelope OpenSearch actually
                // emits — a canned fixture asserts against whatever it was written from.
                implementation(libs.testcontainers.junit.jupiter)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("A signed OpenSearch transport for Kotlin Multiplatform.")
            }
        }
    }
}
