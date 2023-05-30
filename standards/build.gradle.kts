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

    ios {

    }

    @Suppress("UNUSED_VARIABLE")
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlin.coroutines.core)
            }
        }

        val jvmMain by getting {
            dependencies {
            }
        }

        val iosMain by getting {
            dependencies {
                implementation(libs.kotlin.date.time)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            artifactId = "awskt-${artifactId}"
            pom {
                description.set("Standard constructs and functions for working with AWS in Kotlin.")
            }
        }
    }
}