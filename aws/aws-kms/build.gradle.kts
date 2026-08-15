plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

description = "A hand-written KMS client for Kotlin Multiplatform."

/**
 * Depends on **`aws-core` only**, for the same reason `aws-s3` does: KMS shares the transport and
 * the signer with the rest of the library and nothing else. A caller who wants to decrypt a data
 * key should not acquire a logging framework or a DynamoDB mapper along the way.
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
                description.set("A hand-written KMS client for Kotlin Multiplatform.")
            }
        }
    }
}
