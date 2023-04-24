plugins {
    id("steamstreet-common.jvm-library-conventions")
}

kotlin {
    explicitApi()
}

dependencies {
    api(libs.aws.lambda.core)
    api(libs.kotlin.serialization.json)
    api(project(":lambda:lambda-coroutines"))
    api(project(":events"))
    api(project(":logging"))
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Helpers for building EventBridge Lambdas in Kotlin")
            }
        }
    }
}