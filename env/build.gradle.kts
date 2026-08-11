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

    linuxX64()
    linuxArm64()
    macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
            }
        }

        val jvmMain by getting {
            dependencies {
                compileOnly(libs.aws.secretsmanager)
                compileOnly(libs.kotlin.serialization.json)
                compileOnly(libs.kotlin.coroutines.core)
                compileOnly(libs.aws.appconfigdata)

                implementation(project(":standards"))
                implementation(project(":logging"))
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Tools for retrieving environment variables from different sources.")
            }
        }
    }
}