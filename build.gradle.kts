plugins {
    id("io.github.gradle-nexus.publish-plugin") version "1.3.0"
    id("nebula.release") version "19.0.10"
    id("com.ncorti.ktfmt.gradle") version "0.20.1" apply false
}

allprojects {
    group = "com.steamstreet"

    // Apply ktfmt for code formatting (manual execution only)
//    apply(plugin = "com.ncorti.ktfmt.gradle")

    // Configure ktfmt to use Kotlin default style
//    configure<com.ncorti.ktfmt.gradle.KtfmtExtension> {
//        kotlinLangStyle()
//    }

    // Remove ktfmt from automatic execution - it should only run when explicitly invoked
    afterEvaluate {
        tasks.configureEach {
            // Prevent check task from depending on ktfmt tasks
            if (name == "check") {
                setDependsOn(dependsOn.filter { dep ->
                    val depString = dep.toString()
                    !depString.contains("ktfmt", ignoreCase = true)
                })
            }
        }

        // Also remove ktfmt from allprojects check
        tasks.matching { it.name.startsWith("ktfmt") }.configureEach {
            enabled = true // Tasks are still available, just not auto-run
        }
    }
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

val closeTask = tasks.named("closeAndReleaseSonatypeStagingRepository")

tasks.named("final") {
    dependsOn(subprojects.flatMap { it.tasks.matching { it.name == "publishToSonatype" } })
    dependsOn(closeTask)
}