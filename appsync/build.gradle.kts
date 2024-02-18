plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    api(libs.kotlin.serialization.json)
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Helpers for building AppSync applications in Kotlin")
            }
        }
    }
}