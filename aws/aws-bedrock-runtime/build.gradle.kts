plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

description = "A hand-written Bedrock Runtime client for Kotlin Multiplatform."

/**
 * Depends on **`aws-core` only** — Decision 6, as for every other `aws-*` service module.
 *
 * This is `bedrock-runtime`, the **data plane**: invoking models. The `bedrock` control plane —
 * listing foundation models, managing provisioned throughput, guardrails, model customization — is
 * a separate service with a separate endpoint and is not covered here.
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
                // `api` rather than `implementation`: converseStream returns a Flow, so the type is
                // on this module's public surface and a consumer needs it to compile.
                api(libs.kotlin.coroutines.core)
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
                description.set("A hand-written Bedrock Runtime client for Kotlin Multiplatform.")
            }
        }
    }
}
