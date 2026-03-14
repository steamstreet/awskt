plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

kotlin {
    explicitApi()

    jvm()
    linuxArm64()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":dynamo"))
                api(libs.aws.dynamodb)
                api(libs.kotlin.coroutines.core)
                api(libs.kotlin.serialization.json)
                implementation(libs.kotlin.date.time)
                api(project(":standards"))
                implementation(project(":env"))
            }
        }

        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kluent)
                implementation(libs.kotlin.coroutines.test)
                implementation(libs.testcontainers.junit.jupiter)
                implementation(libs.testcontainers.localstack)
            }
        }
    }
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

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()

    val libsDir = File(projectDir, "dynamo_libs")
    this.systemProperty("java.library.path", libsDir.canonicalPath)
}
