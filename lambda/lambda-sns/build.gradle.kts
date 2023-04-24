plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    implementation(libs.kotlin.serialization.json)
    api(project(":lambda:lambda-coroutines"))
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Helpers for building SNS handling Lambdas in Kotlin")
            }
        }
    }
}