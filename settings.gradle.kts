@file:Suppress("UnstableApiUsage")

rootProject.name = "steamstreet-common"

include("env")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            val kotlinVersion = version("kotlin", "1.7.22")
            val kotlinSerializationVersion = version("kotlin-serialization", "1.4.1")
            val awsVersion = version("aws", "2.18.31")

            library("kotlin-serialization-core", "org.jetbrains.kotlinx",
                "kotlinx-serialization-core").versionRef(
                kotlinSerializationVersion
            )
            library("kotlin-serialization-json", "org.jetbrains.kotlinx",
                "kotlinx-serialization-json").versionRef(
                kotlinSerializationVersion
            )

            library("aws-s3", "software.amazon.awssdk", "s3").versionRef("aws")
            library("aws-secrets", "software.amazon.awssdk", "secretsmanager").versionRef("aws")
        }
    }
}