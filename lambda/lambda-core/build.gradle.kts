plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    api(libs.aws.lambda.core)
    api(project(":logging"))
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Help for building AWS Lambdas in Kotlin")
            }
        }
    }
}