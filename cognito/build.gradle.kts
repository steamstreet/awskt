plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    api(libs.kotlin.serialization.json)
    api(libs.ktor.server.auth.jwt)
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Helpers for building AppSync applications in Kotlin")
            }
        }
    }
}