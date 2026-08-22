plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

description = "A hand-written Lambda invoke client for Kotlin Multiplatform."

/**
 * Depends on **`aws-core` only** — Decision 6, as for every other `aws-*` service module.
 *
 * This is the **invoke plane** of the Lambda service and nothing else: `Invoke` and
 * `InvokeWithResponseStream`. Everything else the Lambda API offers — creating functions, updating
 * code and configuration, aliases, versions, event source mappings, concurrency, layers — is the
 * resource plane, is provisioned by CloudFormation or the CLI rather than by a running function,
 * and is out of scope. See the KDoc on `Lambda`.
 *
 * Unrelated to the `:lambda:*` modules despite the shared name, and complementary to them: those
 * handle events *arriving* at a function and contain no client, while this one calls a function
 * from the outside. Their package is `com.steamstreet.aws.lambda`; this module's is
 * `com.steamstreet.awskt.lambda`.
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
                // `api` rather than `implementation`: invokeWithResponseStream returns a Flow, so
                // the type is on this module's public surface and a consumer needs it to compile.
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
                description.set("A hand-written Lambda invoke client for Kotlin Multiplatform.")
            }
        }
    }
}
