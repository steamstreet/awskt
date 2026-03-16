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
                api(libs.kotlin.date.time)
                api(projects.events)
                api(projects.logging)
                api(projects.lambda.lambdaCoroutines)
                api(projects.lambda.lambdaSqs)
            }
        }
        jvmMain {
            dependencies {
                implementation(libs.slf4j.api)
                implementation(libs.logstash.logback.encoder)
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlin.coroutines.test)
                implementation(libs.kluent)
                implementation(projects.lambda.lambdaLogging)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Helpers for building EventBridge Lambdas in Kotlin")
            }
        }
    }
}
