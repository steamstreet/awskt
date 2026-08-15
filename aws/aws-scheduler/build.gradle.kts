plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

description = "A hand-written EventBridge Scheduler client for Kotlin Multiplatform."

/**
 * Depends on **`aws-core` only** — Decision 6, as for every other `aws-*` service module.
 *
 * Unrelated to `:aws:aws-eventbridge` despite the shared brand: EventBridge Scheduler is a separate
 * service with its own endpoint (`scheduler`), its own protocol (restJson1, where EventBridge is
 * AWS-JSON 1.1) and no overlapping operations. The two share nothing but a name.
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
                description.set("A hand-written EventBridge Scheduler client for Kotlin Multiplatform.")
            }
        }
    }
}
