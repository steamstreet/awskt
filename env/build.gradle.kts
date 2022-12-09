plugins {
    kotlin("multiplatform")
    id("kotlinx-serialization")
}

kotlin {
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

publishing {
    repositories {
        maven("https://steamstreet-141660060409.d.codeartifact.us-west-2.amazonaws.com/maven/steamstreet/")
    }
}