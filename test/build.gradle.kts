plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

kotlin {
    jvm {
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                api(libs.aws.dynamodb)
                api(libs.aws.dynamodb.local)
                api(libs.aws.eventbridge)
                api(libs.aws.s3)
                api(libs.aws.lambda)

                implementation(libs.aws.lambda.core)
                implementation(libs.aws.lambda.events)
                implementation(libs.jackson)

                api(libs.kotlin.coroutines.core)
                api(libs.kotlin.serialization.json)
                implementation(libs.kotlin.date.time)

                api(project(":standards"))
            }
        }
    }
}


publishing {
    publications {
        withType<MavenPublication> {
            artifactId = "awskt-${artifactId}"
            pom {
                description.set("Some useful tools for writing local unit tests.")
            }
        }
    }
}