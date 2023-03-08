job("publish-1.0") {
    container("openjdk:11") {
        mountDir = "/root"

        kotlinScript {
            it.gradlew("publish", "-PBUILD_NUMBER=${it.executionNumber()}")
        }

        cache {
            storeKey = "gradle-root-{{ hashFiles('gradle/wrapper/gradle-wrapper.properties', 'build.gradle.kts', 'env/build.gradle.kts', 'settings.gradle.kts') }}"
            localPath = "/root/.gradle"
        }
    }
}