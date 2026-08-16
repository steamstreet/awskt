plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

// Multiplatform so an AppSync resolver can be written for Kotlin/Native, with the same target set as
// `:lambda:lambda-native`.
//
// There was nothing to untangle here: the resolver context is `@Serializable` data and the only
// JVM-bound member was `InputStream.appSyncContext()`, which moved to `jvmMain` unchanged. The JVM
// surface is identical — same package, same types.
//
// `explicitApi()` matches what the JVM conventions plugin applied before (it defaults to
// `explicitApiWarning()`); this module's declarations were already explicit, so nothing had to
// change to satisfy it.
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
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Helpers for building AppSync applications in Kotlin")
            }
        }
    }
}
