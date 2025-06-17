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
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))

            username = findProperty("mavenCentralUsername").toString()
            password = findProperty("mavenCentralPassword").toString()
        }
    }
}


subprojects {
    this.task<DependencyReportTask>("allDeps")
}

tasks.named("snapshot") {
    dependsOn(subprojects.flatMap { it.tasks.matching { it.name == "publishToMavenLocal" } })
}

val closeTask = tasks.named("closeSonatypeStagingRepository")
//val closeTask = tasks.named("closeAndReleaseSonatypeStagingRepository")

tasks.named("final") {
    dependsOn(subprojects.flatMap { it.tasks.matching { it.name == "publishToSonatype" } })
    dependsOn(closeTask)
}