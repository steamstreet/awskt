plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

// Multiplatform so a Kotlin/Native Lambda can both decode the SQS envelope and process it.
//
// The DTOs and the processing loops — `processMessages`, `processRawMessages`, `runMessages`,
// `processBatch` — are `commonMain`. The loops are where the batch semantics live (which record
// failed, what goes in `batchItemFailures`, whether the batch runs concurrently), and having one
// copy of them means the JVM base classes and the native `sqsLambda` wrappers cannot drift apart.
// `SQSBatchHandler.handleEvents` is now a call to `runMessages`.
//
// The handler base classes themselves stay on the JVM: they are built on InputStream/OutputStream
// and the AWS `Context`. Native's equivalent is `main`, which the `sqsLambda`/`sqsBatchLambda`
// wrappers in `nativeMain` serve — each a one-liner over a public common function, so an application
// that needs its own dispatch can skip them and call the functions directly.
//
// Per-record logging context is `expect`/`actual`: the JVM keeps slf4j's MDC, so existing log output
// is byte-for-byte what it was, while native uses the coroutine-context `Log` API.
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
                // The common processing loop logs per-record failures through the `expect` shims in
                // SQSProcessing.kt, so the logging module is needed on every target now, not just
                // the JVM.
                implementation(projects.logging)
            }
        }

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

        jvmTest {
            dependencies {
                implementation(libs.kluent)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Helpers for building SQS handling Lambdas in Kotlin")
            }
        }
    }
}
