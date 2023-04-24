plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    api(project(":logging"))
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Helpers for logging in Lambdas in Kotlin")
            }
        }
    }
}