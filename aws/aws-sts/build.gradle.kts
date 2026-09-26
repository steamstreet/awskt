plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

description = "A hand-written STS client and an assume-role credentials provider for Kotlin Multiplatform."

/**
 * Depends on **`aws-core` only** — Decision 6, as for every other `aws-*` service module.
 *
 * STS is not in `aws-core` even though what it produces is credentials. `aws-core` owns the
 * [com.steamstreet.awskt.core.AwsCredentialsProvider] seam and nothing that fills it beyond the
 * environment; `AssumeRoleCredentialsProvider` fills it from here, so the dependency runs one way and
 * `aws-core` never learns that STS exists.
 *
 * Like `aws-sns`, this module speaks the query protocol and so carries no JSON dependency: requests
 * are form-encoded and responses are read out of XML by `Wire.kt`.
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
                description.set("A hand-written STS client for Kotlin Multiplatform.")
            }
        }
    }
}
