plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    api(project(":dynamo"))
    // The hand-written client replaces the AWS SDK here too — `dynamokt-exposed` builds requests
    // directly rather than through `dynamokt`, so it has its own client dependency to swap.
    api(project(":aws:aws-dynamodb"))
    api(libs.kotlin.coroutines.core)
    api(project(":standards"))

    // M5a executes against SdkBackedDynamoDb so the type swap is validated against known-good
    // AWS SDK behaviour. Test-only; the adapter never enters the production graph.
    testImplementation(project(":aws:aws-dynamodb-sdk-adapter"))
    testImplementation(kotlin("test"))
    testImplementation(libs.kluent)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.localstack)
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Exposed-style type-safe ORM for DynamoDB")
            }
        }
    }
}

tasks.test {
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

tasks.withType<Test> {
    // Forwards the implementation switch into the test JVM. Without this, `-Dawskt.dynamodb.impl`
    // only ever reaches the Gradle daemon and the flip silently does nothing.
    systemProperty("awskt.dynamodb.impl", System.getProperty("awskt.dynamodb.impl") ?: "sdk")
}
