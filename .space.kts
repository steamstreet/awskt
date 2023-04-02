job("publish-1.0") {
    container("openjdk:11") {
//        mountDir = "/root"

        kotlinScript {
            it.gradlew("publish", "-PBUILD_NUMBER=${it.executionNumber()}", "--stacktrace")
        }
//
//        val buildFiles = "{{ hashFiles('gradle/wrapper/gradle-wrapper.properties', 'build.gradle.kts', 'env/build.gradle.kts', 'settings.gradle.kts') }}"
//        cache {
//            storeKey = "gradle-config-${buildFiles}"
//            localPath = "/root/.gradle"
//        }
    }
}