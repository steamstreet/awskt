plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

description = "A hand-written S3 client for Kotlin Multiplatform."

/**
 * Depends on **`aws-core` only** — deliberately not `:dynamo`, `:standards`, `:env` or `:logging`.
 * S3 shares the transport and the signer with the rest of the library and nothing else; a
 * dependency on the foundation modules would put a logging framework into the graph of anyone who
 * only wants a presigned URL.
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

        jvmTest {
            dependencies {
                // The presign differential compares our URL against the real SDK presigner's,
                // signature included. jvmTest-only; the SDK never reaches commonMain.
                implementation(libs.aws.s3)
                // The live smoke resolves a *named profile* through the SDK and bridges it into our
                // provider — ours reads environment variables only. jvmTest-only, like the SDK itself.
                implementation(libs.aws.sdk.config)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("A hand-written S3 client for Kotlin Multiplatform.")
            }
        }
    }
}
