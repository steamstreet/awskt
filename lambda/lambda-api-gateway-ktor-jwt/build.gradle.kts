plugins {
    id("steamstreet-common.jvm-library-conventions")
}

// `JWTPrincipal(request)` and the `ApiGatewayJWT` plugin, split out of `:lambda:lambda-api-gateway-ktor`
// so that adapter can be taken without `com.auth0:java-jwt` behind it.
//
// ### Why this is a separate artifact
//
// The multiplatform change (561c3b7) moved `JWT.kt` into `jvmMain` and argued that a `-jwt` artifact
// "would have done the same job while breaking every existing consumer's build file". The first half
// of that turned out to be wrong. `jvmMain` stops a JVM-only dependency gating the *native* build,
// but it does nothing about the JVM classpath, and that is where the cost actually lands: every
// consumer of the adapter — including the ones that never mention JWT — was resolving
// `ktor-server-auth-jwt`, and through it `com.auth0:java-jwt` and `com.auth0:jwks-rsa`, straight
// into its Lambda deployment package. For an application using Ktor's own `bearer` auth, that is
// pure cold-start weight for an API it does not call.
//
// Jackson is a common false positive here: it shows up next to those jars in a dependency report,
// but it reaches the classpath on its own through `logstash-logback-encoder` behind `:logging` and
// is unaffected by this split. auth-jwt was only pinning it higher (2.18.3 -> 2.22.0).
//
// It also left the JVM and native variants of one artifact with materially different public surfaces:
// `JWTPrincipal` existed on JVM and silently did not exist on native, so "the adapter is
// multiplatform" was true of the mapping and not of the module.
//
// ### Why `compileOnly` was not enough
//
// The obvious cheaper fix — leave `JWT.kt` where it is and demote `api(libs.ktor.server.auth.jwt)` to
// `compileOnly` — does not actually remove the jars. `:cognito` declares `api(libs.ktor.server.auth.jwt)`
// of its own (its `JsonElementClaim` implements `com.auth0.jwt.interfaces.Claim`, so it genuinely
// needs java-jwt), and `JWT.kt` needs `:cognito`. Demoting only the direct dependency leaves the
// second path intact and the consumer's classpath unchanged. Moving the code is what moves both.
//
// ### What consumers see
//
// The package is deliberately unchanged (`com.steamstreet.aws.lambda.apigateway.ktor`), so no import
// moves and no source changes. A consumer that uses `JWTPrincipal` or `ApiGatewayJWT` adds this one
// artifact to its build file; a consumer that does not gets a smaller classpath for free. See the
// note in `:lambda:lambda-api-gateway-ktor` for the other half of this.
//
// JVM-only, and not a multiplatform module with a single `jvm()` target, because there is no native
// story to tell: `ktor-server-auth-jwt` wraps java-jwt and publishes no klibs. A native Lambda that
// needs claims should read them off `ApiGatewayProxyRequest.requestContext.authorizer` directly.
kotlin {
    // Matches the module this code came from, which is stricter than the `explicitApiWarning()` the
    // JVM conventions default to. Splitting an artifact out should not quietly relax its API rules.
    explicitApi()
}

dependencies {
    // `api`, not `implementation`: `JWTPrincipal` is the return type of the function this module
    // exists to provide, so a consumer cannot use it without `ktor-server-auth-jwt` on its own
    // compile classpath. `:cognito` would supply it transitively, but this module imports
    // `io.ktor.server.auth.jwt` directly and so declares it directly.
    api(libs.ktor.server.auth.jwt)
    api(projects.cognito)

    // `api` so that adding this artifact is enough on its own — a consumer that wants the JWT plugin
    // always wants the adapter it plugs into, and `ApiGatewayJWT` reads `call.apiGatewayRequest`
    // from it.
    api(projects.lambda.lambdaApiGatewayKtor)

    implementation(libs.kotlin.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kluent)
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("JWT principal and authentication plugin for API Gateway Ktor Lambdas.")
            }
        }
    }
}
