val MAJOR_VERSION = 2
val MINOR_VERSION = 0

plugins {
    id("io.github.gradle-nexus.publish-plugin") version "1.3.0"
    id("nebula.release") version "19.0.10"
}

allprojects {
    group = "com.steamstreet"
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

tasks.named("snapshot") {
    dependsOn(subprojects.flatMap { it.tasks.matching { it.name == "publishToMavenLocal" } })
}

tasks.named("final") {
    dependsOn(subprojects.flatMap { it.tasks.matching { it.name == "publishToMavenLocal" } })
//    dependsOn(subprojects.flatMap { it.tasks.matching { it.name == "publishToSonatype" } })
//    dependsOn(subprojects.flatMap { it.tasks.matching { it.name == "closeAndReleaseSonatypeStagingRepository" } })
}