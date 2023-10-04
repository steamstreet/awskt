plugins {
    id("steamstreet-common.jvm-library-conventions")
}

kotlin {
    explicitApi()
}

dependencies {
    implementation(libs.kotlin.serialization.json)
    api(project(":lambda:lambda-coroutines"))

    testImplementation(kotlin("test"))
    testImplementation(libs.kluent)
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Helpers for building SQS handling Lambdas in Kotlin")
            }
        }
    }
}