plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

kotlin {
    explicitApi()

    jvm {
    }

    js(IR) {
        useCommonJs()
        browser()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlin.serialization.json)
            }
        }
        val jvmMain by getting {
            dependencies {
                api(libs.slf4j.api)
                api(libs.logstash.logback.encoder)
                api(libs.aws.lambda.core)
                api(libs.aws.eventbridge)

                api(project(":standards"))
                api(project(":env"))
                api(project(":logging"))
            }
        }
    }
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