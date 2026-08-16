plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

description = "A hand-written Kinesis Data Streams client for Kotlin Multiplatform."

/**
 * Depends on **`aws-core` only** — Decision 6, as for every other `aws-*` service module.
 *
 * Note this is a different artifact from `:lambda:lambda-kinesis`, which handles Kinesis events
 * *arriving* at a Lambda and has no client in it at all. The two are complementary and neither
 * depends on the other: `awskt-lambda-kinesis` consumes an invocation payload, `awskt-aws-kinesis`
 * calls the service.
 *
 * ### Scope
 *
 * The data plane, and within it the operations that a producer or a polling consumer needs:
 * `PutRecord`, `PutRecords`, `GetShardIterator`, `GetRecords`, `ListShards`. Stream lifecycle is
 * out, as everywhere else here.
 *
 * The immediate driver is `:lambda:lambda-dynamo-streams`, whose DLQ redrive path resolves an
 * SQS-delivered `KinesisBatchInfo` back into records with `GetShardIterator` + `GetRecords`. That
 * path was JVM-only for want of a native Kinesis client, and this is what closes it.
 *
 * `SubscribeToShard` (enhanced fan-out) is deliberately absent: it is an HTTP/2 stream of
 * `application/vnd.amazon.eventstream` frames rather than a JSON call, so it needs the streaming
 * path `aws-bedrock-runtime` uses for `ConverseStream` — a different piece of work from this one.
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
                api(project(":aws:aws-core"))
                api(libs.kotlin.serialization.json)
                implementation(libs.kotlin.coroutines.core)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.ktor.client.mock)
                implementation(libs.kotlin.coroutines.test)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("A hand-written Kinesis Data Streams client for Kotlin Multiplatform.")
            }
        }
    }
}
