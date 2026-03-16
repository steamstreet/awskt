plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

kotlin {
    explicitApi()

    linuxArm64()
    macosArm64()

    sourceSets {
        nativeMain {
            dependencies {
                api(projects.lambda.lambdaCoroutines)
                api(libs.ktor.client.core)
                api(libs.ktor.client.curl)
                implementation(libs.kotlin.serialization.json)
                implementation(libs.kotlin.date.time)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Kotlin/Native AWS Lambda custom runtime support")
            }
        }
    }
}
