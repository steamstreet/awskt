plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    api(project(":dynamo"))
    api(libs.aws.dynamodb)
    api(libs.kotlin.coroutines.core)
    api(libs.kotlin.serialization.json)
    implementation(libs.kotlin.date.time)
    api(project(":standards"))
    implementation(project(":env"))

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