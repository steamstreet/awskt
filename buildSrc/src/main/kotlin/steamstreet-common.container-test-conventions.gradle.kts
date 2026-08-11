/**
 * Docker wiring for Testcontainers-backed suites.
 *
 * This block was verbatim-triplicated across `dynamokt`, `dynamokt-exposed` and `test`; a fourth
 * copy went in with `aws-dynamodb`'s LocalStack suite, which is where it stopped being tolerable.
 * Hoisted here so the next module that needs a container applies a plugin instead of copying a
 * comment it has to understand first.
 *
 * `tasks.withType<Test>().configureEach` rather than `tasks.test`: a Kotlin Multiplatform module has
 * `jvmTest`, not `test`, and the two forms are not interchangeable.
 *
 * The three existing copies are deliberately left alone for now — replacing them touches three
 * working integration suites, which is M0's job, not a drive-by.
 */
tasks.withType<Test>().configureEach {
    // OrbStack on macOS: Testcontainers' auto-detect can't find the daemon and its bundled
    // docker-java defaults to an API version OrbStack rejects ("client version 1.32 is too old;
    // minimum 1.40"). Point at the OrbStack socket and tell Testcontainers to negotiate a newer API.
    val orbstackSocket = File(System.getProperty("user.home"), ".orbstack/run/docker.sock")
    if (orbstackSocket.exists()) {
        environment("DOCKER_HOST", "unix://${orbstackSocket.absolutePath}")
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
        systemProperty("api.version", "1.43")
    }
}
