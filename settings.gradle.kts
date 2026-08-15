@file:Suppress("UnstableApiUsage")

rootProject.name = "aws-kt"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// `pluginManagement` has to be the first block in the file and a top-level one. It used to sit
// nested inside `dependencyResolutionManagement`, where it resolved against the outer Settings
// receiver and happened to work; `includeBuild` for plugin resolution does not tolerate that.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }

    // Publishes `com.steamstreet.awskt.native-lambda`. An included build rather than `buildSrc`
    // because buildSrc plugins are invisible to downstream projects, and the point of this one is
    // that consumers can apply it.
    includeBuild("gradle-plugin")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()

        maven("https://s3-us-west-2.amazonaws.com/dynamodb-local/release")
    }
}
include(":aws:aws-signing")
include(":aws:aws-bedrock-runtime")
include(":aws:aws-core")
include(":aws:aws-dynamodb")
include(":aws:aws-eventbridge")
include(":aws:aws-kms")
include(":aws:aws-s3")
include(":aws:aws-scheduler")
include(":aws:aws-secretsmanager")
include(":aws:aws-sns")
include(":aws:aws-sqs")
include(":aws:aws-dynamodb-sdk-adapter")
include("appsync")
include("cognito")
include("standards")
include("env")
include("logging")
include("dynamo")
include("dynamokt")
include("dynamokt-exposed")
include("events")
include("serialization")
include("test")
include(":lambda:lambda-api-gateway")
include(":lambda:lambda-api-gateway-ktor")
include(":lambda:lambda-appsync")
include(":lambda:lambda-core")
include(":lambda:lambda-coroutines")
include(":lambda:lambda-default")
include(":lambda:lambda-dynamo-streams")
include(":lambda:lambda-eventbridge")
include(":lambda:lambda-kinesis")
include(":lambda:lambda-native")
include(":lambda:lambda-native-smoke")
include(":lambda:lambda-sns")
include(":lambda:lambda-sqs")
include(":lambda:lambda-logging")
