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
                // M5a: the stream-runner tests drive DynamoKt, which now takes a DynamoDb.
                implementation(project(":aws:aws-dynamodb-sdk-adapter"))
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

    // OrbStack on macOS: Testcontainers' auto-detect can't find the daemon and
    // its bundled docker-java defaults to an API version OrbStack rejects
    // ("client version 1.32 is too old; minimum 1.40"). Point at the OrbStack
    // socket and tell Testcontainers to negotiate a newer API.
    val orbstackSocket = File(System.getProperty("user.home"), ".orbstack/run/docker.sock")
    if (orbstackSocket.exists()) {
        environment("DOCKER_HOST", "unix://${orbstackSocket.absolutePath}")
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
        systemProperty("api.version", "1.43")
    }
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