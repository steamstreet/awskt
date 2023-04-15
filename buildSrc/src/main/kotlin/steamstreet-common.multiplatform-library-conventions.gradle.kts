import org.gradle.kotlin.dsl.kotlin

plugins {
    kotlin("multiplatform")
    id("kotlinx-serialization")
}

kotlin {
    explicitApi()

    jvm {
    }

    js(IR) {
        useCommonJs()
        browser()
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}