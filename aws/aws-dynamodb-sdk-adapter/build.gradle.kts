plugins {
    id("steamstreet-common.jvm-library-conventions")
    id("steamstreet-common.container-test-conventions")
}

description = "A DynamoDb implementation backed by the AWS SDK for Kotlin, for JVM migrations."

dependencies {
    // Both are `api`: a consumer of this module holds our DTOs on one side and hands it an SDK
    // client on the other, so both type sets are part of its surface.
    api(project(":aws:aws-dynamodb"))
    api(project(":dynamo"))
    api(libs.aws.dynamodb)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlin.coroutines.test)

    // The parity suite runs the same operations through both implementations against one
    // LocalStack. That equivalence is the entire premise of the M5a/M5b split, so it is worth
    // asserting rather than assuming.
    testImplementation(libs.testcontainers.localstack)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("A DynamoDb implementation backed by the AWS SDK for Kotlin.")
            }
        }
    }
}
