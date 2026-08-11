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
        /**
         * An intermediate source set for everything that can post an event, shared by the JVM and
         * the native targets and **excluding `js`**.
         *
         * It exists because `EventBridgeSubmitter` needs `aws-eventbridge`, which has no `js`
         * target and never will — signing a request needs primitives the browser does not offer.
         * `commonMain` therefore cannot hold it, but `jvmMain` alone would leave a native Lambda
         * unable to post an event, which is the whole point of the milestone.
         *
         * Built by hand rather than through `applyDefaultHierarchyTemplate`: the default template
         * has no jvm+native group, and adding one there would restructure every other source set
         * in the module as a side effect.
         */
        val jvmNativeMain by creating {
            dependsOn(commonMain.get())

            dependencies {
                api(project(":aws:aws-eventbridge"))
                api(project(":standards"))
                api(project(":env"))
                api(project(":logging"))
            }
        }

        jvmMain {
            dependsOn(jvmNativeMain)

            dependencies {
                api(libs.slf4j.api)
                api(libs.logstash.logback.encoder)
                api(libs.aws.lambda.core)
            }
        }

        nativeMain {
            dependsOn(jvmNativeMain)
        }

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
                description.set("Helpers for building EventBridge applications in Kotlin")
            }
        }
    }
}
