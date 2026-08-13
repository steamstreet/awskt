import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

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
         *
         * Note what the hand-built edges below cost us: the first manual `dependsOn` in a module
         * switches the default hierarchy template **off**. `jvmMain` survives that because the
         * `jvm()` target creates it, but every intermediate set the template used to supply —
         * `nativeMain`, `linuxMain`, `appleMain` — stops existing. Wiring this source set through
         * `nativeMain` therefore silently compiled nothing: the set was real, and attached to no
         * compilation. Attach to the targets' own default source sets instead; those are created
         * by the targets, so they are there whether the template is on or off.
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

        /**
         * Every native target's main compilation, and deliberately not `js`: `aws-eventbridge` has
         * no `js` target, so leaking this set into the js compilation breaks `compileKotlinJs`.
         *
         * Driven off the target list rather than naming `linuxX64Main`/`linuxArm64Main`/
         * `macosArm64Main` by hand so that declaring a new native target above wires it up here
         * too. Hand-written names would leave a new target quietly missing these classes, which is
         * the exact failure this replaced.
         */
        targets.withType<KotlinNativeTarget>().configureEach {
            compilations.getByName("main").defaultSourceSet.dependsOn(jvmNativeMain)
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
