plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

// Multiplatform so an AppSync resolver can be written for Kotlin/Native, with the same target set as
// `:lambda:lambda-native`.
//
// This module is shaped differently from the other adapters. There is no `processEvent(x): Response`
// to hoist, because in AppSync the DSL *is* the dispatch: a resolver declares type/field pairs, each
// declaration tests the incoming context, and at most one writes a response. Making that common
// meant making `AppSyncTypeHandler` common, and that interface exposed an `OutputStream`.
//
// So the response sink is now `write(String)` alone, and the stream moved to `JvmAppSyncTypeHandler`
// in `jvmMain`. `appSync()` hands its config block that subtype, so existing blocks that read
// `output` still compile; what breaks is code that names `AppSyncTypeHandler` explicitly and then
// reaches for `.output`. That is the only source-incompatible change in this conversion, and it is
// called out on the interface.
//
// `BufferedAppSyncTypeHandler` is what makes the DSL work off the JVM — the native runtime wants the
// response as a return value, not written to a descriptor. It is also the easiest way to test a
// resolver on any platform.
kotlin {
    explicitApi()

    jvm()
    linuxX64()
    linuxArm64()
    macosArm64()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.serialization.json)
                api(projects.lambda.lambdaCoroutines)
                api(projects.appsync)
            }
        }

        jvmMain {
            dependencies {
                implementation(projects.logging)
                implementation(libs.slf4j.api)
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
                description.set("Help for building AppSync lambdas in Kotlin")
            }
        }
    }
}
