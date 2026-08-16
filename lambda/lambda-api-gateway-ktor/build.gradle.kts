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
// One thing stays in `jvmMain`: `APIGatewayLambdaServer` extends `ApiGatewayProxyHandler`, i.e. an
// AWS `RequestStreamHandler`. That is how the *JVM* runtime discovers a handler and has no native
// counterpart; native Lambdas are found by `main`, which is what `apiGatewayKtorLambda` in
// `nativeMain` provides.
//
// ### What left the module entirely
//
// `JWT.kt` used to sit in `jvmMain` with `:cognito` and `ktor-server-auth-jwt` behind it, on the
// reasoning that `jvmMain` was enough to stop a JVM-only dependency gating the native build. It is —
// but that was the wrong problem. `jvmMain` says nothing about the JVM *classpath*, and both of
// those were `api` dependencies, so every consumer of this adapter resolved `ktor-server-auth-jwt`,
// `com.auth0:java-jwt` and `com.auth0:jwks-rsa` whether or not it had ever referenced a
// `JWTPrincipal` — Lambda package size and cold start for an API most consumers do not call. It
// also meant the JVM and native variants of one artifact published different public surfaces.
//
// (Jackson is *not* in that list, despite appearing alongside them in the report that prompted this.
// It arrives independently via `logstash-logback-encoder` behind `:logging`, and stays on the
// classpath after this change. All auth-jwt did was force it up from 2.18.3 to 2.22.0, so removing
// auth-jwt drops the version back rather than dropping the jars.)
//
// So it now lives in `:lambda:lambda-api-gateway-ktor-jwt`, in the same package, and what remains
// here is the request/response mapping and nothing else. Consumers that want `JWTPrincipal` or
// `ApiGatewayJWT` add that artifact; their imports do not change. That module's build file carries
// the rest of the reasoning, including why demoting the dependency to `compileOnly` would not have
// removed the jars.
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
