buildscript {
    dependencies {
        classpath("software.amazon.awssdk:sso:2.17.295")
        classpath("ai.clarity:clarity-artifact:0.0.12")
    }
}

allprojects {
    group = "com.steamstreet.common"
    version = "1.0.0-SNAPSHOT"

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


