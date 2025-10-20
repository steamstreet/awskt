plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    api(project(":lambda:lambda-coroutines"))
    api(project(":lambda:lambda-kinesis"))
    api(project(":dynamokt"))
    api(libs.aws.kinesis)

    testImplementation(kotlin("test"))
    testImplementation(libs.kluent)
    testImplementation(libs.mockk)
    testImplementation(project(":test"))
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Help for building Dynamo stream handler lambdas in Kotlin")
            }
        }
    }
}