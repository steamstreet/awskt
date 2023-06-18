plugins {
    id("steamstreet-common.jvm-library-conventions")
}

kotlin {
    explicitApi()
}

dependencies {
    api(libs.aws.dynamodb)
    api(libs.kotlin.coroutines.core)
    api(libs.kotlin.serialization.json)
    implementation(libs.kotlin.date.time)
    api(project(":standards"))

    testImplementation(kotlin("test"))
    testImplementation(libs.kluent)
    testImplementation(libs.aws.dynamodb.local)
    testImplementation(libs.kotlin.coroutines.test)
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