plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    api(projects.lambda.lambdaCoroutines)
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Helpers for building SNS handling Lambdas in Kotlin")
            }
        }
    }
}