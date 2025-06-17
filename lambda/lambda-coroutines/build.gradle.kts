plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    api(libs.kotlin.serialization.json)
    api(libs.kotlin.coroutines.core)

    api(projects.lambda.lambdaCore)
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