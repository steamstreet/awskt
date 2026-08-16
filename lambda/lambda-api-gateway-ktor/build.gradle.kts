plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

// Multiplatform so the same Ktor application — routing, plugins, handlers, untouched — can be
// driven by either the JVM Lambda or a Kotlin/Native one. The target set matches
// `:lambda:lambda-native`: `linuxArm64` deploys, `linuxX64` and `macosArm64` exist so the code is
// compiled and, on the hosts that support it, tested.
//
// This works because the adapter never runs a server. `APIGatewayKtorServer` maps a proxy event
// onto `Application.execute` in-process — no socket, no engine, no selector — so the only Ktor
// artifact it needs is `ktor-server-core`, which publishes klibs for every target above.
//
// ### What had to move, and what did not
//
// The request/response mapping (`APIGatewayKtorServer`, `ApiGatewayKtorCall`) is now `commonMain`.
// Nothing in it was JVM-bound by nature — it used `java.util.Base64` and `java.util.concurrent`
// atomics where `kotlin.io.encoding.Base64` and plain properties do the same job on every target.
//
// Two things stay in `jvmMain`, and only one of them is interesting:
//
//  - `APIGatewayLambdaServer` extends `ApiGatewayProxyHandler`, i.e. an AWS `RequestStreamHandler`.
//    That is how the *JVM* runtime discovers a handler and has no native counterpart; native
//    Lambdas are found by `main`, which is what `apiGatewayKtorLambda` in `nativeMain` provides.
//
//  - `JWT.kt`, with `:cognito` and `ktor-server-auth-jwt` behind it. `ktor-server-auth-jwt` wraps
//    `com.auth0:java-jwt` and is JVM-only, so it cannot go native — but it was never part of the
//    request-mapping path either, and keeping it in `jvmMain` means it no longer gates anything. A
//    separate `-jwt` artifact would have done the same job while breaking every existing consumer's
//    build file; this way the JVM surface is unchanged.
//
// `ktor-server-host-common` is retained rather than dropped: it is not needed to drive the pipeline
// (core carries `embeddedServer` and `ApplicationEngine`), but it is an `api` dependency today and
// some consumer may be resolving it transitively. It publishes klibs for all four targets, so
// keeping it costs native nothing.
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
                api(projects.lambda.lambdaApiGateway)
                api(projects.lambda.lambdaCoroutines)

                api(libs.ktor.server.host.common)
                api(libs.ktor.server.core)
            }
        }

        jvmMain {
            dependencies {
                api(projects.cognito)
                api(libs.ktor.server.auth.jwt)
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
                implementation(libs.ktor.server.status.pages)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(projects.standards)
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
                description.set("Build an API Gateway Lambda using Ktor server.")
            }
        }
    }
}
