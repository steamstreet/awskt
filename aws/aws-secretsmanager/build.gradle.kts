plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

description = "A hand-written Secrets Manager client for Kotlin Multiplatform."

/**
 * Depends on **`aws-core` only**. Notably *not* on `:env`, even though `env`'s `SecretsProvider` is
 * the most obvious consumer: the dependency has to run that way round — `env` may depend on this —
 * or every caller who wants to read one secret acquires a logging framework and an AppConfig client.
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
                description.set("A hand-written Secrets Manager client for Kotlin Multiplatform.")
            }
        }
    }
}
