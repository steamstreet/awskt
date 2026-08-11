plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

description = "Core DynamoDB types for streaming and serialization"

/**
 * `dynamo` owns [com.steamstreet.dynamokt.AttributeValue] — see the plan's Decision 2.
 *
 * The dependency list is the point of this module, and it is deliberately tiny: kotlinx-serialization
 * and nothing else. **No `aws-core`, no Ktor, no AWS SDK.** DynamoDB *stream* records are parsed by
 * Lambdas that never make an API call, so putting the attribute type in the transport module would
 * drag an HTTP client into every one of them. That is also why `AttributeValue` lives here rather
 * than in `aws-dynamodb`, which depends on this module and re-exports the type.
 *
 * The package is `com.steamstreet.dynamokt`, shared with `dynamokt` — which is what lets the
 * migration delete imports rather than rewrite them at ~200 call sites.
 */
kotlin {
    explicitApi()

    jvm()
    linuxX64()
    linuxArm64()
    macosArm64()

    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlin.serialization.json)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
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
