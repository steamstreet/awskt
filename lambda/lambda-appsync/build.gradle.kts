plugins {
    id("steamstreet-common.jvm-library-conventions")
}

kotlin {
    explicitApi()
}

dependencies {
    implementation(libs.kotlin.serialization.json)
    api(project(":lambda:lambda-coroutines"))
    api(project(":appsync"))
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