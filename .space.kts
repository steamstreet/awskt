job("publish-1.0") {
    container("amazoncorretto:17-alpine") {
        kotlinScript {
            it.gradlew("publish", "-PBUILD_NUMBER=${it.executionNumber()}")
        }
    }
}