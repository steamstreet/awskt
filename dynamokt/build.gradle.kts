plugins {
    id("steamstreet-common.multiplatform-library-conventions")
    id("steamstreet-common.container-test-conventions")
}

description = "Helpers for building DynamoDB applications in Kotlin"

/**
 * Multiplatform, but with `jvm()` as the only target **for now**.
 *
 * The target list is not the point of this conversion — `commonMain` is. Source in `commonMain`
 * compiles against the *common* stdlib even when only the JVM target is enabled, which is what
 * surfaces the JVM couplings (`java.util.Base64`, `java.time`, `Dispatchers.IO`,
 * `KClass.java.enumConstants`) as compile errors now, instead of leaving them for whoever first
 * adds `linuxArm64`. Adding native targets then becomes a build-file edit rather than archaeology.
 *
 * `js`/`wasm` are permanently out: `commonMain` uses `runBlocking`, which resolves only across the
 * jvm+native `concurrent` source set.
 */
kotlin {
    explicitApi()

    jvm()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":dynamo"))
                // The hand-written client replaces `libs.aws.dynamodb`. That swap is the point of
                // the milestone: nothing in dynamokt's production graph references the AWS SDK.
                api(project(":aws:aws-dynamodb"))
                api(libs.kotlin.coroutines.core)
                api(libs.kotlin.serialization.json)
                // `api`, not `implementation`: dates.kt exposes kotlinx.datetime types in its
                // public signatures, so a consumer cannot use them without this on the classpath.
                api(libs.kotlin.date.time)
                api(project(":standards"))
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        jvmTest {
            dependencies {
                // M5a runs the existing suites against SdkBackedDynamoDb, so behaviour is still the
                // AWS SDK's while the *type* swap is validated. Test-only: the adapter never enters
                // the production graph.
                implementation(project(":aws:aws-dynamodb-sdk-adapter"))
                implementation(libs.kluent)
                implementation(libs.kotlin.coroutines.test)
                implementation(libs.testcontainers.junit.jupiter)
                implementation(libs.testcontainers.localstack)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("Helpers for building DynamoDB applications in Kotlin")
            }
        }
    }
}

// `jvmTest`, not `test`: a multiplatform module has no `test` task, so `tasks.test` would silently
// configure nothing at all.
tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    systemProperty("java.library.path", File(projectDir, "dynamo_libs").canonicalPath)
}

tasks.withType<Test> {
    // Forwards the implementation switch into the test JVM. Without this, `-Dawskt.dynamodb.impl`
    // only ever reaches the Gradle daemon and the flip silently does nothing.
    systemProperty("awskt.dynamodb.impl", System.getProperty("awskt.dynamodb.impl") ?: "sdk")
}
