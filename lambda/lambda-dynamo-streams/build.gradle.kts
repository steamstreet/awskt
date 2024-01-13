plugins {
    id("steamstreet-common.jvm-library-conventions")
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":lambda:lambda-coroutines"))
    api(project(":dynamokt"))
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Help for building AppSync lambdas in Kotlin")
            }
        }
    }
}