plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    implementation(libs.kotlin.serialization.json)
    api(projects.lambda.lambdaCoroutines)
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