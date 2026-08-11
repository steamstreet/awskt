plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

description = "A hand-written EventBridge client for Kotlin Multiplatform."

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
                // The request differential compares our wire bytes against the real SDK's. As in
                // `aws-dynamodb`, this is jvmTest-only and never reaches commonMain — the point of
                // the module is that production code does not need the SDK.
                implementation(libs.aws.eventbridge)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("A hand-written EventBridge client for Kotlin Multiplatform.")
            }
        }
    }
}
