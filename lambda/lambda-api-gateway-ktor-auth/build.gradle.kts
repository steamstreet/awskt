plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

// `ApiGatewayPrincipal` and the `ApiGatewayAuth` plugin: the claims API Gateway has already verified,
// as a Ktor principal, on every target. It is the multiplatform counterpart of
// `:lambda:lambda-api-gateway-ktor-jwt`, which is JVM-only because `ktor-server-auth-jwt` wraps
// `com.auth0:java-jwt`. Nothing here verifies a token, so nothing here needs a JWT library:
// `ktor-server-auth` alone publishes klibs for every target below.
//
// ### Why this is a separate artifact
//
// The same reasoning that split `-jwt` out of `:lambda:lambda-api-gateway-ktor` applies. That module
// holds the request/response mapping and nothing else, so that a consumer pays only for what it
// calls. `ktor-server-auth` is a 282 KB jar, larger than the three the `-jwt` split removed put
// together, and it brings `ktor-server-sessions` and `ktor-client-core` with it. Putting this code
// in the adapter's `commonMain` would hand all of that to every consumer of the adapter, including
// the ones behind an IAM authorizer or none.
//
// The package is the adapter's (`com.steamstreet.aws.lambda.apigateway.ktor`), as it is for `-jwt`,
// so a consumer moving from `ApiGatewayJWT` changes two names and no imports.
kotlin {
    explicitApi()

    jvm()
    linuxX64()
    linuxArm64()
    macosArm64()

    sourceSets {
        commonMain {
            dependencies {
                // `api`: `ApiGatewayAuth` is only useful inside the adapter's application, and
                // `ApiGatewayPrincipal` is built from the adapter's request types.
                api(projects.lambda.lambdaApiGatewayKtor)

                // `api`: a consumer calls `authenticate { }` and `call.principal<ApiGatewayPrincipal>()`,
                // which are `ktor-server-auth` declarations.
                api(libs.ktor.server.auth)

                // `api`: `ApiGatewayPrincipal.claims` is a `JsonObject`.
                api(libs.kotlin.serialization.json)
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
                // Only for `ApiGatewayAuthParityJvmTest`, which compares this module's principal with
                // the JVM one for the same request.
                implementation(projects.lambda.lambdaApiGatewayKtorJwt)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("API Gateway authorizer claims as a Ktor principal, for JVM and Kotlin/Native Lambdas.")
            }
        }
    }
}
