plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

// Multiplatform so an EventBridge Lambda can be written for Kotlin/Native.
//
// The event model and the handler-registration DSL have been common since this module was first
// converted. What is common now in addition is the *dispatch driver* — `processEventBridgePayload`:
// detecting an SQS-wrapped payload, building the partial-batch-failure response, timing the handler,
// deciding whether a failure throws or is reported per record. That was the non-trivial half and it
// previously existed only on the JVM, so a native EventBridge Lambda would have had to reimplement
// it. `java.util.UUID` became `Uuid.random()` and `measureTimeMillis` became `measureTime`; the
// logging went behind `expect` shims so the JVM's slf4j output is unchanged.
//
// What stays in jvmMain is genuine plumbing: `eventBridge()` reading an InputStream and writing the
// response to an OutputStream, `EventBridgeFunction`, and the `processEvent` test helpers.
// `eventBridgeLambda` in nativeMain is the native entry point, a one-liner over `nativeLambda` and
// the common driver.
kotlin {
    explicitApi()

    jvm()
    linuxX64()
    linuxArm64()
    macosArm64()

    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlin.date.time)
                api(projects.events)
                api(projects.logging)
                api(projects.lambda.lambdaCoroutines)
                api(projects.lambda.lambdaSqs)
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
                implementation(projects.lambda.lambdaLogging)
            }
        }
    }

    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Helpers for building EventBridge Lambdas in Kotlin")
            }
        }
    }
}
