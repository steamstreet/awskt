plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    implementation(libs.kotlin.serialization.json)
    api(projects.cognito)
    api(projects.lambda.lambdaApiGateway)
    api(projects.lambda.lambdaCoroutines)

    api(libs.ktor.server.host.common)
    api(libs.ktor.server.auth.jwt)
    api(libs.ktor.server.core)
    api(libs.ktor.server.auth.jwt)

    testImplementation(kotlin("test"))
    testImplementation(libs.kluent)
    testImplementation(libs.ktor.server.status.pages)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.ktor.server.status.pages)
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