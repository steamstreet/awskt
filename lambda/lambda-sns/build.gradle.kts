plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

// Multiplatform so an SNS Lambda can be written for Kotlin/Native. The target set matches
// `:lambda:lambda-native`: `linuxArm64` deploys, `linuxX64` and `macosArm64` exist so the code is
// compiled and, on the hosts that support it, tested.
//
// The split follows the rule used across these modules: the *semantics* go in `commonMain` and the
// *runtime plumbing* stays platform-specific. `SnsPayload.processMessages` — decode each record's
// message body, dispatch with the record in scope — is the whole of what an SNS handler does and
// has nothing JVM-bound in it. What is JVM-bound is `InputLambda`, which is an AWS
// `RequestStreamHandler`; the native equivalent is `main`, which `snsLambda` in `nativeMain`
// serves.
//
// `SNSHandler` keeps its signature and now delegates to the common function, so the JVM and native
// paths cannot drift in how they parse a message.
kotlin {
    explicitApi()

    jvm()
    linuxX64()
    linuxArm64()
    macosArm64()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.serialization.json)
                api(projects.lambda.lambdaCoroutines)
            }
        }

        // `api(lambdaNative)` because `snsLambda` is a wrapper over `nativeLambdaInput` and a
        // consumer that calls it is already writing against the native runtime. It costs a
        // dependency edge, not binary size — Kotlin/Native dead-strips at link time, so a consumer
        // that takes this module only for `SnsPayload` links none of the runtime.
        nativeMain {
            dependencies {
                api(projects.lambda.lambdaNative)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlin.coroutines.test)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Helpers for building SNS handling Lambdas in Kotlin")
            }
        }
    }
}
