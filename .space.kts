job("publish-1.0") {
    container("openjdk:11") {
        mountDir = "/root"

        args("chmod", "+x", "/root/.gradle/nodejs/node-v16.13.0-linux-x64/bin/node")

        kotlinScript {
            it.gradlew("publish", "-PBUILD_NUMBER=${it.executionNumber()}", "--stacktrace")
        }

        val buildFiles = "{{ hashFiles('gradle/wrapper/gradle-wrapper.properties', 'build.gradle.kts', 'env/build.gradle.kts', 'settings.gradle.kts') }}"
        cache {
            storeKey = "gradle-wrapper-${buildFiles}"
            localPath = "/root/.gradle/wrapper"
        }
        cache {
            storeKey = "gradle-cache-${buildFiles}"
            localPath = "/root/.gradle/caches"
        }
        cache {
            storeKey = "gradle-daemons-${buildFiles}"
            localPath = "/root/.gradle/daemon"
        }
    }
}