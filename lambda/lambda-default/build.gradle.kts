plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    api(project(":env"))
    api(project(":standards"))
    api(project(":logging"))
    api(project(":lambda:lambda-core"))
    api(project(":lambda:lambda-logging"))
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Provides typical defaults for Lambdas in Kotlin.")
            }
        }
    }
}