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
}