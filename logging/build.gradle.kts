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
                api(libs.slf4j.api)
                api(libs.logstash.logback.encoder)
                implementation(libs.kotlin.serialization.json)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            artifactId = "awskt-${artifactId}"
            pom {
                description.set("Useful logging tools for Kotlin on AWS")
            }
        }
    }
}