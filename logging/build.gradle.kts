@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

kotlin {
    explicitApi()

    jvm()
    iosArm64()
    iosSimulatorArm64()
    js {
        browser()
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.serialization.json)
                implementation(libs.kotlin.date.time)
                implementation(projects.standards)
                compileOnly(libs.ktor.client.core)
            }
        }
        jvmMain {
            dependencies {
                api(libs.slf4j.api)
                api(libs.logstash.logback.encoder)
                implementation(libs.kotlin.coroutines.slf4j)
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlin.coroutines.test)
                implementation(libs.kluent)
                implementation(libs.slf4j.logback.classic)
                implementation(libs.logstash.logback.encoder)
            }
        }
        jsMain {
            dependencies {
                api(libs.ktor.client.core)
            }
        }
        nativeMain {
            dependencies {
                api(libs.ktor.client.core)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Useful logging tools for Kotlin on AWS")
            }
        }
    }
}