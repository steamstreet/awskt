plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    implementation(libs.kotlin.serialization.json)
    api(project(":lambda:lambda-coroutines"))

    testImplementation(kotlin("test"))
    testImplementation(libs.kluent)
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Helpers for building Api Gateway handling Lambdas in Kotlin")
            }
        }
    }
}