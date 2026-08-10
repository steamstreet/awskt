plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

description = "Signed AWS transport: credentials, endpoints, retries, error mapping."

kotlin {
    explicitApi()

    jvm()
    linuxX64()
    linuxArm64()
    macosArm64()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":aws:aws-signing"))
                api(libs.ktor.client.core)
                implementation(libs.kotlin.serialization.json)
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

        jvmMain {
            dependencies {
                implementation(libs.ktor.client.cio)
            }
        }

        // Curl rather than CIO on native: CIO's native TLS support is the weaker of the two, and
        // the Lambda runtime already links libcurl. The CA bundle path is injected by the caller —
        // see awsHttpClient's KDoc for why hardcoding the AL2023 path breaks macOS at runtime.
        nativeMain {
            dependencies {
                implementation(libs.ktor.client.curl)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Signed AWS transport for Kotlin Multiplatform.")
            }
        }
    }
}
