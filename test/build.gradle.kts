plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

kotlin {
    explicitApi()

    jvm {}

    sourceSets {
        jvmMain {
            dependencies {
                api(libs.aws.dynamodb)
                api(libs.aws.dynamodb.local)
                api(libs.aws.dynamodbstreams)
                api(libs.aws.eventbridge)
                api(libs.aws.s3)
                api(libs.aws.lambda)
                compileOnly(libs.aws.sqs)

                implementation(libs.aws.lambda.core)
                implementation(libs.aws.lambda.events)
                implementation(libs.jackson)

                api(libs.mockk)
                api(libs.kotlin.coroutines.core)
                api(libs.kotlin.serialization.json)
                implementation(libs.event.ruler)
                implementation(libs.kotlin.date.time)

                api(project(":standards"))
                api(project(":dynamokt"))
                api(project(":lambda:lambda-eventbridge"))
                api(project(":lambda:lambda-dynamo-streams"))
            }
        }
        jvmTest {
            dependencies {
                implementation(libs.testcontainers.junit.jupiter)
                implementation(libs.testcontainers.localstack)
                implementation(kotlin("test"))
                implementation(libs.kotlin.coroutines.test)
                implementation(libs.slf4j.logback.classic)
                implementation(libs.kotest.assertions.core)
                implementation(libs.kotest.runner.junit5)
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Some useful tools for writing local unit tests.")
            }
        }
    }
}