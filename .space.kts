job("publish-1.0") {
    container("openjdk:11") {
        kotlinScript {
            it.gradlew("hello", "-PBUILD_NUMBER=${it.executionNumber()}")
        }

        cache {
            storeKey = "gradle-{{ hashFiles('gradle/wrapper/gradle-wrapper.properties') }}"
            localPath = "~/.gradle"
        }
    }
}