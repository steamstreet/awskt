@file:Suppress("UNUSED_VARIABLE")

plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

kotlin {
    sourceSets {
        val jvmMain by getting {
            dependencies {
                compileOnly(libs.aws.secrets)
                compileOnly(libs.kotlin.serialization.json)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Tools for retrieving environment variables from different sources.")
            }
        }
    }
}