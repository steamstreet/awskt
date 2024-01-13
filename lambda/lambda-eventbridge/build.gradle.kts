plugins {
    id("steamstreet-common.jvm-library-conventions")
}

kotlin {
    explicitApi()
}

dependencies {
    api(libs.kotlin.date.time)

    api(project(":events"))
    api(project(":logging"))
    api(project(":lambda:lambda-coroutines"))
    api(project(":lambda:lambda-sqs"))

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.kluent)
    testImplementation(project(":lambda:lambda-logging"))
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