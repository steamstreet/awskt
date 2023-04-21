plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

kotlin {
    @Suppress("UNUSED_VARIABLE")
    sourceSets {
        val commonMain by getting {
            dependencies {
            }
        }

        val jvmMain by getting {
            dependencies {
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Standard constructs and functions for working with AWS in Kotlin.")
            }
        }
    }
}