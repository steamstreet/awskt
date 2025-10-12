plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

kotlin {
    explicitApi()

    jvm {
    }

    sourceSets {
        jvmMain {
            dependencies {
                api(libs.slf4j.api)
                api(libs.logstash.logback.encoder)
                implementation(libs.kotlin.serialization.json)
                implementation(libs.kotlin.coroutines.slf4j)
                implementation(libs.kotlin.date.time)
                implementation(projects.standards)
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