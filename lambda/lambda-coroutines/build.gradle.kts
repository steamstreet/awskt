plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

// Multiplatform so that a Kotlin/Native Lambda can share `LambdaContext`, `lambdaJson` and
// `logIncoming` with the JVM handlers. The JVM surface is unchanged apart from `lambdaContext`,
// which is now the common `LambdaContext` rather than the AWS `Context` — reach the AWS type
// through `awsLambdaContext` or `JvmLambdaContext.awsContext`.
kotlin {
    explicitApi()

    jvm()
    linuxX64()
    linuxArm64()
    macosArm64()

    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlin.serialization.json)
                api(libs.kotlin.coroutines.core)
                api(projects.env)
            }
        }
        jvmMain {
            dependencies {
                api(projects.lambda.lambdaCore)
                implementation(projects.logging)
                implementation(libs.slf4j.api)
                implementation(libs.logstash.logback.encoder)
                implementation(libs.kotlin.coroutines.slf4j)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Helpers for integrating coroutines into AWS Lambdas in Kotlin")
            }
        }
    }
}
