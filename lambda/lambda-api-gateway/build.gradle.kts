plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

// Multiplatform so a Kotlin/Native Lambda can share the API Gateway proxy model with the JVM
// handlers — the same target set as `:lambda:lambda-native`, for the same reason: `linuxArm64` is
// what deploys, `linuxX64` and `macosArm64` exist so the code is compiled and, on the hosts that
// support it, tested.
//
// The split is not arbitrary. Only `ApiGatewayProxyHandler` is JVM-bound, because it extends
// `IOLambda` (an AWS `RequestStreamHandler`); the request/response model it carries has no JVM
// content at all once `java.util.Base64` is replaced with `kotlin.io.encoding.Base64`. The JVM
// surface is unchanged — same package, same types, same transitive `lambda-coroutines`.
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
                description.set("Helpers for building Api Gateway handling Lambdas in Kotlin")
            }
        }
    }
}
