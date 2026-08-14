plugins {
    id("steamstreet-common.multiplatform-library-conventions")
    id("steamstreet-common.container-test-conventions")
}

description = "Exposed-style type-safe ORM for DynamoDB"

/**
 * Multiplatform with `commonMain` sources and native targets — the deferral in plan Decision 12
 * (keeping this JVM-only until "a native consumer actually appears") has now been acted on.
 *
 * The one JVM coupling that blocked the move — `KClass.java.enumConstants` in the public
 * `EnumerationColumn<T>` / `EnumerationByNameColumn<T>` — is gone: those classes now hold the enum
 * constants as a `List<T>`, supplied by the already-reified `enumeration<T>()` /
 * `enumerationByName<T>()` factories via `enumEntries<T>()`. This mirrors the same swap made in the
 * sibling `dynamokt` module. Factory call sites are unchanged; the column constructors are the only
 * public break.
 *
 * Targets match `dynamokt`. `js`/`wasm` stay out for the same reason: the transitive graph resolves
 * only across the jvm+native `concurrent` source set.
 */
kotlin {
    explicitApi()

    jvm()
    linuxX64()
    linuxArm64()
    macosArm64()

    sourceSets {
        commonMain {
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
