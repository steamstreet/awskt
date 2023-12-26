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
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation("org.testcontainers:junit-jupiter:1.19.3")
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