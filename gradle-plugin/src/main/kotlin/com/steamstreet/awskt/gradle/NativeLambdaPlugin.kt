package com.steamstreet.awskt.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import java.util.Locale

/**
 * Packages a Kotlin/Native executable as an AWS Lambda deployment artifact.
 *
 * Two tasks:
 *  - `packageLambda` — zips the `linuxArm64` release executable as `bootstrap`, which is the entry
 *    point name the `provided.al2023` runtime looks for.
 *  - `packageNativeLayer` — builds a Lambda Layer zip containing `lib/libcrypt.so.1`.
 *
 * The layer exists because of [KT-55643](https://youtrack.jetbrains.com/issue/KT-55643): Kotlin/Native
 * links against `libcrypt.so.1`, which Amazon Linux 2023 does not ship. The issue is Open, unassigned
 * and has no fix version, and has been present since Kotlin 1.8, so this is not a stopgap — it is
 * expected to outlive several toolchain bumps. Deploy the layer alongside the function and set
 * `LD_LIBRARY_PATH=/opt/lib:/lib64:/usr/lib64`, which puts the layer's `lib/` ahead of the system
 * paths without hiding them.
 */
public class NativeLambdaPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create<NativeLambdaExtension>("nativeLambda").apply {
            target.convention("linuxArm64")
            buildType.convention("release")
            layerImage.convention("amazonlinux:2")
            layerPlatform.convention("linux/arm64")
            layerLibraries.convention(listOf("/usr/lib64/libcrypt.so.1"))
        }

        val lambdaOutputDir = project.layout.buildDirectory.dir("lambda")

        project.tasks.register<Zip>("packageLambda") {
            group = LAMBDA_GROUP
            description = "Packages the Kotlin/Native executable as a Lambda bootstrap zip."

            val target = extension.target.get()
            val buildType = extension.buildType.get()

            dependsOn("link${buildType.capitalized()}Executable${target.capitalized()}")

            archiveFileName.set("${project.name}.zip")
            destinationDirectory.set(lambdaOutputDir)

            from(project.layout.buildDirectory.dir("bin/$target/${buildType}Executable")) {
                // Restrict to the executable itself; the same directory can also hold debug
                // companions that have no business inside a deployment package.
                include("*.kexe")
                rename(".*\\.kexe", "bootstrap")
            }

            filePermissions {
                unix("rwxr-xr-x")
            }
        }

        val extractLayer = project.tasks.register<ExtractLayerLibraries>("extractLayerLibraries") {
            group = LAMBDA_GROUP
            description = "Extracts the shared libraries the native Lambda layer provides."
            image.set(extension.layerImage)
            platform.set(extension.layerPlatform)
            libraries.set(extension.layerLibraries)
            outputDirectory.set(project.layout.buildDirectory.dir("native-layer/lib"))
        }

        project.tasks.register<Zip>("packageNativeLayer") {
            group = LAMBDA_GROUP
            description = "Builds the Lambda Layer zip carrying libcrypt.so.1 (KT-55643)."

            archiveFileName.set("${project.name}-native-layer.zip")
            destinationDirectory.set(lambdaOutputDir)

            // `into("lib")` is load-bearing: LD_LIBRARY_PATH points at /opt/lib, and a layer's
            // contents are unpacked under /opt.
            from(extractLayer) {
                into("lib")
            }

            filePermissions {
                unix("rwxr-xr-x")
            }
        }
    }

    private fun String.capitalized(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

    private companion object {
        const val LAMBDA_GROUP = "lambda"
    }
}
