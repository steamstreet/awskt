job("publish-1.0") {
    container("openjdk:11") {
        kotlinScript {
            it.gradlew("publish", "-PBUILD_NUMBER=${it.executionNumber()}")
        }

        cache {
            storeKey = "gradle-{{ hashFiles('gradle-wrapper.properties') }}"
            localPath = "~/.gradle"
        }
    }
}