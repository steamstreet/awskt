@file:Suppress("UnstableApiUsage")

rootProject.name = "steamstreet-common"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()

        maven {
            url = uri("https://maven.pkg.jetbrains.space/steamstreet/p/vg/vegasful")

            credentials {
                username = extra["steamstreet.space.username"] as String
                password = extra["steamstreet.space.password"] as String
            }
        }

    }

    pluginManagement {
        repositories {
            gradlePluginPortal()
            mavenCentral()
        }
    }

    versionCatalogs {
        create("libs") {
            val kotlinSerializationVersion = version("kotlin-serialization", "1.4.1")
            val awsVersion = version("aws", "2.18.31")

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

            library("aws-secrets", "software.amazon.awssdk", "secretsmanager").versionRef(awsVersion)

            val slf4jVersion = version("slf4j", "1.8.0-beta4")

            library("slf4j-api", "org.slf4j", "slf4j-api").versionRef(slf4jVersion)
            library("slf4j-simple", "org.slf4j", "slf4j-simple").versionRef(slf4jVersion)
            library("slf4j-jcl", "org.slf4j", "jcl-over-slf4j").versionRef(slf4jVersion)
            library("slf4j-log4j", "org.slf4j", "log4j-over-slf4j").versionRef(slf4jVersion)
            library("aws-lambda-logback", "org.jlib", "jlib-awslambda-logback").version("1.0.0")

            library("logstash-logback-encoder", "net.logstash.logback:logstash-logback-encoder:6.6")
        }
    }
}
include("standards")
include("env")
include("logging")
