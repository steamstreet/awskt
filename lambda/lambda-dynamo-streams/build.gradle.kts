plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

// Multiplatform so a DynamoDB stream handler can be written for Kotlin/Native, with the same target
// set as `:lambda:lambda-native`. `dynamo`, `dynamokt` and `lambda-kinesis` already publish klibs for
// all of them, so the model was never the obstacle.
//
// `parseDynamoStreamPayload` is the reason this was worth doing: a DynamoDB stream reaches a Lambda
// as a direct batch, as Kinesis records whose Base64 payload holds the event, or as an SQS message
// naming a shard and sequence range after a Kinesis failure — and one handler is expected to cope
// with all three. That logic now exists once, in `commonMain`.
//
// ### The DLQ redrive path
//
// Resolving an SQS-delivered `KinesisBatchInfo` back into records needs a Kinesis client, and
// `aws.sdk.kotlin.services.kinesis` is JVM-only. `:aws:aws-kinesis` — the hand-written client —
// closes that: `Kinesis.resolveRedrive` in `commonMain` does the same job on every target, and
// `dynamoStreamBatchLambda` takes a client and wires it up.
//
// `parseDynamoStreamPayload` still takes the fetch as a parameter rather than requiring a client,
// because a handler with no failure destination configured never sees one of these messages and
// should not have to construct a client it will not use. Passing nothing skips redriven records,
// which is what the JVM handler has always done when built without a `KinesisClient`.
//
// The JVM `DynamoKtStreamHandler` still takes an SDK `KinesisClient` and uses its own copy of this
// logic. Migrating it to the hand-written client would delete that copy and drop `aws-sdk-kotlin`
// from this module's graph entirely, but it changes a public constructor's parameter type, so it is
// left as a deliberate decision rather than made in passing.
//
// ### Two API changes
//
// `logFailures` takes `StreamFailureLogLevel` instead of `java.util.logging.Level`, which has no
// native equivalent. Entry names are unchanged (INFO/WARNING/SEVERE), so consumers change an import.
//
// `RecordInfo`, `StreamHandlingException` and the processing functions moved from the default package
// layout into `commonMain` under `com.steamstreet.aws.lambda` — the package they were already
// declared in, so imports are unaffected.
kotlin {
    explicitApi()

    jvm()
    linuxX64()
    linuxArm64()
    macosArm64()

    sourceSets {
        commonMain {
            dependencies {
                api(projects.lambda.lambdaCoroutines)
                api(projects.lambda.lambdaKinesis)
                // The hand-written client, for the DLQ redrive path in KinesisRedrive.kt. This is
                // what makes that path available off the JVM.
                api(projects.aws.awsKinesis)
                api(projects.dynamokt)
                api(projects.standards)
                implementation(projects.logging)
                implementation(libs.kotlin.serialization.json)
            }
        }

        jvmMain {
            dependencies {
                // JVM-only, and the reason the redrive path cannot be common.
                api(libs.aws.kinesis)
                implementation(libs.logstash.logback.encoder)
            }
        }

        nativeMain {
            dependencies {
                api(projects.lambda.lambdaNative)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlin.coroutines.test)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.kluent)
                implementation(libs.mockk)
                implementation(projects.test)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Help for building Dynamo stream handler lambdas in Kotlin")
            }
        }
    }
}
