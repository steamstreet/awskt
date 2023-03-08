job("publish-1.0") {
    container(displayName = "Run gradle build", image = "amazoncorretto:17-alpine") {
        kotlinScript { api ->
            // here can be your complex logic
            api.gradlew("publish", "-PBUILD_NUMBER=${api.executionNumber()}")
        }

        cache {
            storeKey = "gradle-{{ hashFiles('gradle/wrapper/gradle-wrapper.properties') }}"
            localPath = "/root/.gradle/wrapper"
        }
        cache {
            storeKey = "m2-{{ hashFiles('build.gradle.kts', 'env/build.gradle.kts', 'settings.gradle.kts') }}"
            localPath = "/root/.m2/repository"
        }
    }
}