plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    implementation(libs.kotlin.serialization.json)
    api(project(":lambda:lambda-coroutines"))
    api(project(":lambda:lambda-api-gateway"))

    testImplementation(kotlin("test"))
    testImplementation(libs.kluent)

    api(libs.ktor.server.test.host)
    api(libs.ktor.server.host.common)
    api(libs.ktor.server.core)

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