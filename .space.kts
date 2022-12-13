job("publish-1.0") {
    gradlew("openjdk:11", "build")
//    container("thowimmer/kotlin-native-multiplatform") {
//        kotlinScript {
//            it.gradlew("publish", "-PBUILD_NUMBER=${it.executionNumber()}")
//        }
//    }
}