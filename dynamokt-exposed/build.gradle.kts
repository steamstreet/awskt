plugins {
    id("steamstreet-common.multiplatform-library-conventions")
    id("steamstreet-common.container-test-conventions")
}

description = "Exposed-style type-safe ORM for DynamoDB"

/**
 * Multiplatform with `jvm()` only, and the sources live in **`jvmMain`, not `commonMain`** — plan
 * Decision 12, and it is deliberate rather than lazy.
 *
 * `Column.kt` uses `enumClass.java.enumConstants` inside the public `EnumerationColumn<T>` and
 * `EnumerationByNameColumn<T>`. Placing those in `commonMain` would fail even with only the JVM
 * target enabled once a second target is added, and porting them buys nothing: no Lambda handler
 * uses `dynamokt-exposed`. Keeping them in `jvmMain` makes the problem disappear instead of trading
 * it for two unnecessary public API breaks. Convert if and when a native consumer actually appears.
 */
kotlin {
    explicitApi()

    jvm()

    sourceSets {
        jvmMain {
            dependencies {
                api(project(":dynamo"))
                // The hand-written client replaces the AWS SDK here too — `dynamokt-exposed` builds
                // requests directly rather than through `dynamokt`, so it has its own swap.
                api(project(":aws:aws-dynamodb"))
                api(libs.kotlin.coroutines.core)
                api(project(":standards"))
            }
        }

        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                // M5a executes against SdkBackedDynamoDb so the type swap is validated against
                // known-good AWS SDK behaviour. Test-only; never in the production graph.
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
                description.set("Exposed-style type-safe ORM for DynamoDB")
            }
        }
    }
}

// `jvmTest`, not `test`: a multiplatform module has no `test` task.
tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

tasks.withType<Test> {
    // Forwards the implementation switch into the test JVM; see dynamokt's build file.
    // M5b flipped this default from `sdk` to `native`.
    systemProperty("awskt.dynamodb.impl", System.getProperty("awskt.dynamodb.impl") ?: "native")
}
