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
                api(libs.kotlin.serialization.json)
                api(libs.kotlin.coroutines.core)
                api(projects.env)
            }
        }
        jvmMain {
            dependencies {
                api(projects.lambda.lambdaCore)
                implementation(projects.logging)
                implementation(libs.slf4j.api)
                implementation(libs.logstash.logback.encoder)
                implementation(libs.kotlin.coroutines.slf4j)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Helpers for integrating coroutines into AWS Lambdas in Kotlin")
            }
        }
    }
}
