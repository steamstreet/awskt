plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

description = "JWT verification (RS256, ES256) with JWKS, and ES256 signing, for Kotlin Multiplatform."

/**
 * Verifies and signs JSON Web Tokens on the JVM and on Kotlin/Native, which `com.auth0:java-jwt`
 * cannot do. The cryptography is `cryptography-kotlin`: the JDK's providers on the JVM, and a
 * statically linked OpenSSL 3 on native, so a native Lambda needs nothing from the host image.
 *
 * `aws-core` is an `implementation` dependency, used only for its HTTP client factory, so that JWKS
 * fetches get the same timeouts, engine choice and CA-bundle handling as every AWS call. No AWS type
 * appears in this module's API.
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
                api(libs.kotlin.serialization.json)
                api(libs.ktor.client.core)
                implementation(project(":aws:aws-core"))
                implementation(libs.kotlin.coroutines.core)
                implementation(libs.cryptography.core)
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
                implementation(libs.cryptography.provider.jdk)
            }
        }

        nativeMain {
            dependencies {
                implementation(libs.cryptography.provider.openssl3.prebuilt)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("JWT verification and signing for Kotlin Multiplatform.")
            }
        }
    }
}
