plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

kotlin {
    explicitApi()

    jvm {
    }

    js(IR) {
        useCommonJs()
        browser()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                compileOnly(libs.kotlin.coroutines.core)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(libs.slf4j.api)
                implementation(libs.logstash.logback.encoder)
                compileOnly(libs.aws.secretsmanager)
                compileOnly(libs.kotlin.serialization.json)
                compileOnly(libs.kotlin.coroutines.core)
                compileOnly(libs.aws.appconfigdata)

                implementation(project(":standards"))
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            artifactId = "awskt-${artifactId}"
            pom {
                description.set("Tools for retrieving environment variables from different sources.")
            }
        }
    }
}