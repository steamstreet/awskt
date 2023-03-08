@file:Suppress("UNUSED_VARIABLE")

plugins {
    kotlin("multiplatform")
    id("kotlinx-serialization")
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
            }
        }

        val jvmMain by getting {
            dependencies {
                compileOnly(libs.aws.secrets)
                compileOnly(libs.kotlin.serialization.json)
            }
        }
    }
}

val hello = tasks.register("hello") {
    doLast {
        println(gradle.gradleHomeDir!!.absolutePath)
    }
}
tasks.named("publish") {
    dependsOn(hello)
}

