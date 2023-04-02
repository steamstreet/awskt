@file:Suppress("UnstableApiUsage")

rootProject.name = "steamstreet-common"


dependencyResolutionManagement {
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
        }
    }
}
include("standards")
include("env")
