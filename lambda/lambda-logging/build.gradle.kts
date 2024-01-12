plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    api(project(":logging"))
    implementation(libs.slf4j.logback.classic)
    implementation(libs.aws.lambda.core)
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