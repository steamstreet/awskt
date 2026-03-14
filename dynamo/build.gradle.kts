plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

kotlin {
    explicitApi()

    jvm()
    linuxArm64()

    sourceSets {
        commonMain {
            dependencies {
                api(libs.aws.dynamodb)
                api(libs.kotlin.serialization.json)
            }
        }
    }
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
