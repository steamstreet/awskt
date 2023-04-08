@file:Suppress("UNUSED_VARIABLE")
plugins {
    kotlin("multiplatform")
    id("kotlinx-serialization")
}

kotlin {
    explicitApi()

    jvm {}

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