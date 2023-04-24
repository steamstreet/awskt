@file:Suppress("UNUSED_VARIABLE")

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
        val jvmMain by getting {
            dependencies {
                compileOnly(libs.aws.secretsmanager)
                compileOnly(libs.kotlin.serialization.json)
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