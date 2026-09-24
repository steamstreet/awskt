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

        // `Secret_` resolution on native goes through awskt's own Secrets Manager client, since the
        // AWS SDK the JVM uses has no native build. The plan's Decision 6 permits this direction:
        // `aws-secretsmanager` must not depend on `env`, but `env` may depend on it. `api` because
        // SecretsManagerSecretsProvider's constructor takes that module's client type.
        nativeMain {
            dependencies {
                api(project(":aws:aws-secretsmanager"))
                implementation(project(":logging"))
                implementation(libs.kotlin.coroutines.core)
                implementation(libs.kotlin.serialization.json)
            }
        }

        nativeTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.ktor.client.mock)
                implementation(libs.kotlin.coroutines.core)
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