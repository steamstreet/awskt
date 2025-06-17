plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    api(projects.logging)
    implementation(libs.slf4j.logback.classic)
    implementation(libs.aws.lambda.core)
    implementation(libs.log4j.api)
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