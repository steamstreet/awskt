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