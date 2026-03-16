plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

kotlin {
    explicitApi()

    jvm()
    linuxArm64()
    macosArm64()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.serialization.json)
                api(projects.lambda.lambdaCoroutines)
            }
        }
        jvmMain {
            dependencies {
                implementation(projects.logging)
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kluent)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Helpers for building SQS handling Lambdas in Kotlin")
            }
        }
    }
}
