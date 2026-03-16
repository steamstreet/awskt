plugins {
    kotlin("multiplatform")
    id("kotlinx-serialization")
}

kotlin {
    linuxArm64()

    sourceSets {
        nativeMain {
            dependencies {
                implementation(project(":lambda:lambda-native"))
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
}

tasks.register<Zip>("packageLambda") {
    dependsOn("linkReleaseExecutableLinuxArm64")
    archiveFileName.set("${project.name}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("lambda"))

    from(layout.buildDirectory.dir("bin/linuxArm64/releaseExecutable")) {
        rename(".*\\.kexe", "bootstrap")
    }

    filePermissions {
        unix("rwxr-xr-x")
    }
}
