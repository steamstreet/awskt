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

    /**
     * The default hierarchy, plus an intermediate `jvmNative` group for everything that can post an
     * event: shared by the JVM and the native targets and **excluding `js`**.
     *
     * The group exists because `EventBridgeSubmitter` needs `aws-eventbridge`, which has no `js`
     * target and never will — signing a request needs primitives the browser does not offer.
     * `commonMain` therefore cannot hold it, but `jvmMain` alone would leave a native Lambda unable
     * to post an event, which is the whole point of the milestone.
     *
     * Declared through the template rather than with hand-written `dependsOn` edges. Through 3.1.5
     * it was built by hand, and the first manual `dependsOn` in a module switches the default
     * template **off**: Gradle warned about it, and `nativeMain`, `linuxMain` and `appleMain` did
     * not exist in this module. Extending the template keeps all of those and adds `jvmNativeMain`
     * beside them, so each native target's main source set depends on both `jvmNativeMain` and its
     * platform family (`linuxMain` or `appleMain`, then `nativeMain`). `withNative()` also means a
     * native target declared above is wired in without naming it here.
     *
     * `js` is outside the group, so `jsMain` still depends on `commonMain` alone and the js
     * compilation never sees `aws-eventbridge`.
     */
    applyDefaultHierarchyTemplate {
        common {
            group("jvmNative") {
                withJvm()
                withNative()
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlin.serialization.json)
            }
        }

        val jvmNativeMain by getting {
            dependencies {
                api(project(":aws:aws-eventbridge"))
                api(project(":standards"))
                api(project(":env"))
                api(project(":logging"))
            }
        }

        jvmMain {
            dependencies {
                api(libs.slf4j.api)
                api(libs.logstash.logback.encoder)
                api(libs.aws.lambda.core)
            }
        }
    }
}
publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Helpers for building EventBridge applications in Kotlin")
            }
        }
    }
}
