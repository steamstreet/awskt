plugins {
    id("io.github.gradle-nexus.publish-plugin") version "1.3.0"
    id("nebula.release") version "19.0.10"
    id("com.ncorti.ktfmt.gradle") version "0.20.1" apply false
}

allprojects {
    // The project owns the `com.steamstreet.awskt` namespace outright, so the group carries the
    // project name and the artifacts do not have to. Through 3.0.0 these published as
    // `com.steamstreet:awskt-<module>`, which needed a prefix forced onto every artifactId; a
    // namespaced group makes that unnecessary. Central coordinates are immutable, so the 3.0.0
    // coordinates remain published — see the release notes for the relocation story.
    group = "com.steamstreet.awskt"

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
    // The default client timeout is five minutes, which is not enough. Closing a staging repository
    // holding every module's artifacts took 272 seconds when measured directly against the API, so
    // the default sits close enough to the real duration that it timed out and failed the release
    // *after* everything had already been uploaded — leaving the release untagged, which is the same
    // end state the note below the `final` task describes.
    clientTimeout.set(java.time.Duration.ofMinutes(30))
    connectTimeout.set(java.time.Duration.ofMinutes(5))

    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))

            // Left null when the properties are absent so that the publishing tasks report missing
            // credentials. Reading them with toString() would send the literal string "null" and
            // surface as an authentication failure instead.
            username = findProperty("mavenCentralUsername") as String?
            password = findProperty("mavenCentralPassword") as String?
        }
    }
}


subprojects {
    this.task<DependencyReportTask>("allDeps")
}

tasks.named("snapshot") {
    dependsOn(subprojects.flatMap { it.tasks.matching { it.name == "publishToMavenLocal" } })
}

// Only close the staging repository, because `closeAndReleaseSonatypeStagingRepository` waits for a
// 'released' state that the Central Portal's OSSRH compatibility API never reports. Releasing that
// way failed *after* the artifacts had already been uploaded, which left the release untagged and
// forced the next build to reuse the version.
//
// IMPORTANT: closing is not publishing, and `final` therefore does not finish a release. The
// deployment stops at VALIDATED and stays there until something publishes it explicitly; this
// namespace is not on auto-publish. `final` exits 0 either way, so a release driven by it alone
// looks like it succeeded and ships nothing. Both 3.0.0 and 3.1.0 stalled here.
//
// Use `scripts/release.sh`, which runs `final` and then publishes the deployment and verifies the
// artifacts actually answer on repo1. Publishing by hand instead means POSTing to
// https://central.sonatype.com/api/v1/publisher/deployment/<id>, or clicking Publish at
// https://central.sonatype.com/publishing/deployments.
val closeTask = tasks.named("closeSonatypeStagingRepository")

tasks.named("final") {
    dependsOn(subprojects.flatMap { it.tasks.matching { it.name == "publishToSonatype" } })
    dependsOn(closeTask)
}