job("publish-1.0") {
    container("openjdk:11") {
        kotlinScript {
            it.gradlew("publish", "-PBUILD_NUMBER=${it.executionNumber()}")
        }

        cache {
            storeKey = "gradle-{{ hashFiles('gradle/wrapper/gradle-wrapper.properties') }}"
            localPath = System.getenv("GRADLE_USER_HOME")
        }
    }
}