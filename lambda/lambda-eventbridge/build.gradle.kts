plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

// Multiplatform so the event model and the handler-registration DSL compile for Kotlin/Native.
// The Lambda plumbing (InputStream/OutputStream, the AWS `Context`, the SQS-wrapped variant) stays
// in jvmMain.
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
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlin.coroutines.test)
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
