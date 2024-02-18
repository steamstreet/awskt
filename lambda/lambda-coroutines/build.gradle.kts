plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    api(libs.kotlin.serialization.json)
    api(project(":lambda:lambda-core"))
    api(libs.kotlin.coroutines.core)
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Helpers for integrating coroutines into AWS Lambdas in Kotlin")
            }
        }
    }
}