plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

// Multiplatform so a Kinesis Lambda can be written for Kotlin/Native, with the same target set as
// `:lambda:lambda-native`.
//
// The record model and the dispatch loop are `commonMain`; nothing in them was JVM-bound once
// `String(Base64.decode(..))` became `Base64.decode(..).decodeToString()`. `Dispatchers.IO` resolves
// from `commonMain` here because the target set is jvm+native only — it is declared in the
// coroutines `concurrent` source set, which is present on both.
//
// `KinesisHandler` stays in `jvmMain` because `InputLambda` is an AWS `RequestStreamHandler`; it now
// delegates to the common loop, which incidentally fixes a bug where it processed every record
// twice. See the class doc.
kotlin {
    explicitApi()

    jvm()
    linuxX64()
    linuxArm64()
    macosArm64()

    sourceSets {
        commonMain {
            dependencies {
                api(projects.lambda.lambdaCoroutines)
                implementation(libs.kotlin.date.time)
            }
        }

        nativeMain {
            dependencies {
                api(projects.lambda.lambdaNative)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlin.coroutines.test)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Help for building Kinesis stream processing lambdas in Kotlin")
            }
        }
    }
}
