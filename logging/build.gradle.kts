plugins {
    kotlin("jvm")
    id("kotlinx-serialization")
}

kotlin {
    explicitApi()
}

dependencies {
    api(libs.slf4j.api)
    api(libs.logstash.logback.encoder)
    implementation(libs.kotlin.serialization.json)
}