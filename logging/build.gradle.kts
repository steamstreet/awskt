@file:Suppress("UNUSED_VARIABLE")

plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

kotlin {
    sourceSets {
        val jvmMain by getting {
            dependencies {
                api(libs.slf4j.api)
                api(libs.logstash.logback.encoder)
                implementation(libs.kotlin.serialization.json)
            }
        }
    }
}