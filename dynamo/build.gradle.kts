plugins {
    id("steamstreet-common.jvm-library-conventions")
}

dependencies {
    api(libs.aws.dynamodb)
    api(libs.kotlin.serialization.json)
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Core DynamoDB types for streaming and serialization")
            }
        }
    }
}
