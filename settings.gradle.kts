@file:Suppress("UnstableApiUsage")

rootProject.name = "aws-kt"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()

        maven("https://s3-us-west-2.amazonaws.com/dynamodb-local/release")
    }

    pluginManagement {
        repositories {
            gradlePluginPortal()
            mavenCentral()
        }
    }
}
include("appsync")
include("cognito")
include("standards")
include("env")
include("logging")
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
include(":lambda:lambda-sns")
include(":lambda:lambda-sqs")
include(":lambda:lambda-logging")
