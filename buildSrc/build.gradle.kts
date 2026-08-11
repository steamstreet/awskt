plugins {
    `kotlin-dsl` // this will create our Gradle convention plugins

    // don't add the Kotlin JVM plugin
    // kotlin("jvm") version embeddedKotlinVersion
    // Why? It's a long story, but Gradle uses an embedded version of Kotlin,
    // (which is provided by the `kotlin-dsl` plugin)
    // which means importing an external version _might_ cause issues
    // It's annoying but not important. The Kotlin plugin version below,
    // in dependencies { }, will be used for building our 'main' project.
    // https://github.com/gradle/gradle/issues/16345
}

// Coupled to Ktor, and not by preference. From Ktor 3.4.0 onward the published Kotlin/Native klibs
// carry abi_version=2.3.0, which a 2.2.x compiler cannot read — it reports the incompatibility as a
// misleading "KLIB resolver: Could not find <path>" for a file that is sitting right there. So the
// plan's M0 hard gate (Ktor >= 3.5.0, for the KTOR-9527 Curl freeze) *requires* Kotlin 2.3.x.
// 2.3.21 is what Ktor 3.5.2 itself was compiled with. Do not lower one without lowering the other.
val kotlinVersion = "2.3.21"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:2.0.0")
}