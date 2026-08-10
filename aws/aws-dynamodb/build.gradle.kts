plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

description = "A hand-written DynamoDB client for Kotlin Multiplatform."

kotlin {
    explicitApi()

    jvm()
    linuxX64()
    linuxArm64()
    macosArm64()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":aws:aws-core"))
                api(libs.kotlin.serialization.json)
                implementation(libs.kotlin.coroutines.core)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.ktor.client.mock)
                implementation(libs.kotlin.coroutines.test)
            }
        }

        jvmTest {
            dependencies {
                // The differential harness compares our wire bytes against the real SDK's. This
                // dependency is jvmTest-only and deliberately never reaches commonMain — the whole
                // point of the module is that production code does not need it.
                implementation(libs.aws.dynamodb)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("A hand-written DynamoDB client for Kotlin Multiplatform.")
            }
        }
    }
}
