buildscript {
    dependencies {
        classpath("software.amazon.awssdk:sso:2.17.295")
        classpath("ai.clarity:clarity-artifact:0.0.12")
    }
}

val MAJOR_VERSION = 1
val MINOR_VERSION = 0

allprojects {
    group = "com.steamstreet.common"
    version = "$MAJOR_VERSION.$MINOR_VERSION${this.findProperty("BUILD_NUMBER")?.let {".$it"} ?: "-SNAPSHOT"}"

    repositories {
        mavenCentral()

        repositories {
            maven("https://steamstreet-141660060409.d.codeartifact.us-west-2.amazonaws.com/maven/steamstreet/")
        }
    }
}

subprojects {
    apply(plugin = "maven-publish")
    apply(plugin = "ai.clarity.codeartifact")
}

plugins {
    kotlin("jvm") version "1.7.22" apply false
    kotlin("plugin.serialization") version "1.7.22" apply false
    kotlin("multiplatform") version "1.7.22" apply false
}


