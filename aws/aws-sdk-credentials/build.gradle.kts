plugins {
    id("steamstreet-common.jvm-library-conventions")
}

description = "Resolves awskt credentials through the AWS SDK's credential chain, on the JVM."

/**
 * JVM-only, and the one place the AWS SDK meets `aws-core` outside the DynamoDB adapter.
 *
 * `aws-core`'s own chain reads environment variables and nothing else — enough for a Lambda, not
 * for an ECS task role or a laptop with `~/.aws` profiles. Rather than re-implement those providers
 * in common code, this module lends the SDK's chain to every awskt client on the JVM. It is its own
 * artifact so that a consumer who only wants credentials does not take the DynamoDB SDK with them,
 * and so that `aws-core` stays free of the SDK (and native builds free of the JVM).
 */
dependencies {
    api(project(":aws:aws-core"))
    // `api`: SdkCredentialsProvider takes the SDK's CredentialsProvider, so its type is on this
    // module's surface. aws-config carries the default chain and, transitively, that interface.
    api(libs.aws.sdk.config)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlin.coroutines.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Resolves awskt credentials through the AWS SDK's credential chain.")
            }
        }
    }
}
