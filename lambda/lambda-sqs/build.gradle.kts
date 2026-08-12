plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

// Multiplatform so a Kotlin/Native Lambda can decode the SQS envelope. Only the DTOs are common —
// the handler base classes are built on InputStream/OutputStream and the AWS `Context`, so they
// stay on the JVM.
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
        jvmMain {
            dependencies {
                implementation(projects.logging)
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
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
