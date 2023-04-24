plugins {
    id("steamstreet-common.jvm-library-conventions")
}

kotlin {
    explicitApi()
}

dependencies {
    api(libs.slf4j.api)
    api(libs.logstash.logback.encoder)
    api(libs.aws.lambda.core)
    api(libs.aws.eventbridge)
    api(libs.kotlin.serialization.json)

    api(project(":standards"))
    api(project(":env"))
    api(project(":logging"))
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Helpers for building EventBridge applications in Kotlin")
            }
        }
    }
}