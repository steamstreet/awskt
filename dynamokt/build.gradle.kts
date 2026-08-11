plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    api(project(":dynamo"))
    // The hand-written client replaces `libs.aws.dynamodb`. That swap is the point of the milestone:
    // nothing in dynamokt's production graph references the AWS SDK any more.
    api(project(":aws:aws-dynamodb"))
    api(libs.kotlin.coroutines.core)
    api(libs.kotlin.serialization.json)
    // `api`, not `implementation`: dates.kt exposes kotlinx.datetime types in its public signatures,
    // so a consumer cannot use them without this on the compile classpath.
    api(libs.kotlin.date.time)
    api(project(":standards"))

    // M5a runs the existing suites against SdkBackedDynamoDb, so behaviour is still the AWS SDK's
    // while the *type* swap is validated. Test-only: the adapter never enters the production graph.
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
                description.set("Helpers for building DynamoDB applications in Kotlin")
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()

    val libsDir = File(projectDir, "dynamo_libs")
    this.systemProperty("java.library.path", libsDir.canonicalPath)

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
