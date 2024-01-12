plugins {
    id("steamstreet-common.jvm-library-conventions")
}

kotlin {
    explicitApi()
}

dependencies {
    api(libs.aws.lambda.core)
    api(libs.kotlin.serialization.json)
    api(libs.kotlin.date.time)
    api(libs.slf4j.api)
    api(project(":lambda:lambda-coroutines"))
    api(project(":events"))
    api(project(":logging"))
    api(project(":lambda:lambda-sqs"))

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.kluent)
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