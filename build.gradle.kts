val MAJOR_VERSION = 2
val MINOR_VERSION = 0

plugins {
    id("io.github.gradle-nexus.publish-plugin") version "1.3.0"
}

allprojects {
    group = "com.steamstreet"

    val releaseName = findProperty("RELEASE_NAME") as? String
    version = releaseName?.removePrefix("v")
        ?: "$MAJOR_VERSION.$MINOR_VERSION${this.findProperty("BUILD_NUMBER")?.let { ".$it" } ?: ".0-SNAPSHOT"}"
}

nexusPublishing {
    repositories {
        sonatype {
            username = findProperty("sonatypeUsername").toString()
            password = findProperty("sonatypePassword").toString()
        }
    }
}

subprojects {
    this.task<DependencyReportTask>("allDeps")
}