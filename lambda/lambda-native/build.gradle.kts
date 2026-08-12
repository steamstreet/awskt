plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

// Native-only, deliberately: there is no JVM custom runtime to implement. `linuxArm64` is the
// deployment target, `linuxX64` and `macosArm64` exist so the code is compiled — and, on the two
// hosts that support it, testable — rather than being verified only by a deploy.
kotlin {
    explicitApi()

    linuxX64()
    linuxArm64()
    macosArm64()

    sourceSets {
        nativeMain {
            dependencies {
                api(projects.lambda.lambdaCoroutines)
                api(libs.ktor.client.core)
                api(libs.ktor.client.curl)
                implementation(libs.kotlin.serialization.json)
            }
        }
        nativeTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlin.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Kotlin/Native AWS Lambda custom runtime support")
            }
        }
    }
}
