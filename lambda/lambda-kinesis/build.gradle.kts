plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    api(project(":lambda:lambda-coroutines"))
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Help for building Kinesis stream processing lambdas in Kotlin")
            }
        }
    }
}