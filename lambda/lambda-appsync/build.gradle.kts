plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    implementation(libs.kotlin.serialization.json)
    api(projects.lambda.lambdaCoroutines)
    api(projects.appsync)
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