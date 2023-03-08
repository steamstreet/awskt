import java.io.File

job("publish-1.0") {
    container("openjdk:11") {
        kotlinScript {
            val initGradle = File("/mnt/space/system/gradle/init.gradle").readText()
            println(initGradle)
            it.gradlew("publish", "-PBUILD_NUMBER=${it.executionNumber()}")
        }

        cache {
            storeKey = "gradle-{{ hashFiles('gradle/wrapper/gradle-wrapper.properties') }}"
            localPath = "~/.gradle"
        }
    }
}