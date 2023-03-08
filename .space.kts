job("publish-1.0") {
    container("openjdk:11") {
        mountDir = "/root"

        kotlinScript {
            it.gradlew("publish", "-PBUILD_NUMBER=${it.executionNumber()}", "--stacktrace")
        }

        cache {
            storeKey = "gradle-wrapper-{{ hashFiles('gradle/wrapper/gradle-wrapper.properties', 'build.gradle.kts', 'env/build.gradle.kts', 'settings.gradle.kts') }}"
            localPath = "/root/.gradle/wrapper"
        }
        cache {
            storeKey = "gradle-daemon-{{ hashFiles('gradle/wrapper/gradle-wrapper.properties', 'build.gradle.kts', 'env/build.gradle.kts', 'settings.gradle.kts') }}"
            localPath = "/root/.gradle/caches"
        }
        cache {
            storeKey = "gradle-caches-{{ hashFiles('gradle/wrapper/gradle-wrapper.properties', 'build.gradle.kts', 'env/build.gradle.kts', 'settings.gradle.kts') }}"
            localPath = "/root/.gradle/daemon"
        }
    }
}