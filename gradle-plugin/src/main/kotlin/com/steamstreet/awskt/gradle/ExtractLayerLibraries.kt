package com.steamstreet.awskt.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Copies a fixed set of shared libraries out of a container image into a directory that
 * `packageNativeLayer` zips.
 *
 * Extraction runs `cat` inside the image rather than `docker cp`, because the interesting paths are
 * symlinks (`libcrypt.so.1` → `libcrypt-2.26.so`) and `docker cp` preserves a symlink as a symlink.
 * A dangling symlink in a Lambda layer resolves to nothing at runtime and the function fails to
 * start with a loader error that names the library but not the reason. `cat` follows the link and
 * yields the real bytes.
 */
public abstract class ExtractLayerLibraries : DefaultTask() {
    @get:Input
    public abstract val image: Property<String>

    @get:Input
    public abstract val platform: Property<String>

    @get:Input
    public abstract val libraries: ListProperty<String>

    @get:OutputDirectory
    public abstract val outputDirectory: DirectoryProperty

    @get:Inject
    protected abstract val execOperations: ExecOperations

    @TaskAction
    public fun extract() {
        val image = image.get()
        val platform = platform.get()
        val destination = outputDirectory.get().asFile
        destination.deleteRecursively()
        destination.mkdirs()

        // Pull separately so that pull progress cannot end up interleaved with the library bytes
        // that the extraction step reads from stdout.
        execOperations.exec {
            commandLine("docker", "pull", "--platform", platform, image)
        }

        libraries.get().forEach { path ->
            val target = File(destination, File(path).name)
            val stderr = ByteArrayOutputStream()
            target.outputStream().use { out ->
                val result = execOperations.exec {
                    commandLine("docker", "run", "--rm", "--platform", platform, image, "cat", path)
                    standardOutput = out
                    errorOutput = stderr
                    isIgnoreExitValue = true
                }
                if (result.exitValue != 0) {
                    throw GradleException(
                        "Could not read $path from $image ($platform): ${stderr.toString().trim()}"
                    )
                }
            }
            // An empty or absurdly small file means `cat` succeeded against the wrong path, which
            // would otherwise ship a broken layer that only fails at Lambda cold start.
            if (target.length() < MINIMUM_PLAUSIBLE_LIBRARY_BYTES) {
                throw GradleException(
                    "Extracted $path from $image but got ${target.length()} bytes, which cannot be a " +
                        "shared library. The path is probably wrong for this image."
                )
            }
            logger.lifecycle("Extracted $path (${target.length()} bytes) from $image")
        }
    }

    private companion object {
        const val MINIMUM_PLAUSIBLE_LIBRARY_BYTES = 1024L
    }
}
