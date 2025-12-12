plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    api(libs.aws.dynamodb)
    api(libs.kotlin.coroutines.core)
    api(project(":standards"))

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
}
