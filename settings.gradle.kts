@file:Suppress("UnstableApiUsage")

rootProject.name = "aws-kt"

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

    versionCatalogs {
        create("libs") {
            val kotlinSerializationVersion = version("kotlin-serialization", "1.5.1")
            val awsVersion = version("aws", "1.0.18")

            library(
                "kotlin-serialization-core", "org.jetbrains.kotlinx",
                "kotlinx-serialization-core"
            ).versionRef(
                kotlinSerializationVersion
            )
            library(
                "kotlin-serialization-json", "org.jetbrains.kotlinx",
                "kotlinx-serialization-json"
            ).versionRef(
                kotlinSerializationVersion
            )

            library("aws-lambda-core", "com.amazonaws:aws-lambda-java-core:1.2.0")
            library("aws-lambda-events", "com.amazonaws:aws-lambda-java-events:3.8.0")

            fun aws(artifact: String) {
                library("aws-${artifact}", "aws.sdk.kotlin", artifact).versionRef(awsVersion)
            }

            aws("secretsmanager")
            aws("dynamodb")
            aws("dynamodbstreams")
            aws("lambda")
            aws("eventbridge")
            aws("s3")
            aws("appconfigdata")

            val ktorVersion = version("ktor", "2.2.3")
            library("ktor-server-core", "io.ktor", "ktor-server-core").versionRef(ktorVersion)
            library("ktor-server-host-common", "io.ktor", "ktor-server-host-common").versionRef(ktorVersion)
            library("ktor-server-test-host", "io.ktor", "ktor-server-test-host").versionRef(ktorVersion)


            val slf4jVersion = version("slf4j", "2.0.9")

            library("slf4j-api", "org.slf4j", "slf4j-api").versionRef(slf4jVersion)
            library("slf4j-simple", "org.slf4j", "slf4j-simple").versionRef(slf4jVersion)
            library("slf4j-jcl", "org.slf4j", "jcl-over-slf4j").versionRef(slf4jVersion)
            library("slf4j-log4j", "org.slf4j", "log4j-over-slf4j").versionRef(slf4jVersion)

            library("slf4j-lambda", "org.jlib", "jlib-awslambda-logback").version("1.0.0")
            library("slf4j-logback-classic", "ch.qos.logback:logback-classic:1.4.14")

            library("logstash-logback-encoder", "net.logstash.logback:logstash-logback-encoder:6.6")

            library("aws-dynamodb-local", "com.amazonaws:DynamoDBLocal:1.12.0")

            library(
                "kotlin-coroutines-core", "org.jetbrains.kotlinx",
                "kotlinx-coroutines-core"
            ).version("1.6.4")
            library(
                "kotlin-coroutines-test", "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.4"
            )
            library(
                "kotlin-coroutines-slf4j", "org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.6.4"
            )
            library("kotlin-date-time", "org.jetbrains.kotlinx", "kotlinx-datetime").version("0.4.0")
            library("kluent", "org.amshove.kluent:kluent:1.73")

            library("jackson", "com.fasterxml.jackson.module:jackson-module-kotlin:2.9.6")

            library("mockk", "io.mockk:mockk:1.13.5")
            library("event-ruler", "software.amazon.event.ruler:event-ruler:1.7.0")
        }
    }
}
include("appsync")
include("standards")
include("env")
include("logging")
include("dynamokt")
include("events")
include("test")
include(":lambda:lambda-api-gateway")
include(":lambda:lambda-api-gateway-ktor")
include(":lambda:lambda-appsync")
include(":lambda:lambda-core")
include(":lambda:lambda-coroutines")
include(":lambda:lambda-dynamo-streams")
include(":lambda:lambda-eventbridge")
include(":lambda:lambda-sns")
include(":lambda:lambda-sqs")
include(":lambda:lambda-logging")
