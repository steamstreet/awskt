# Kotlin/Native Lambda Support for awskt

## Context

We built a working Kotlin/Native AWS Lambda setup in the curiosity project as a proof of concept. Now we want to move that infrastructure into awskt so any project can use it. Work should happen on the `2.3.x` branch which already has linuxArm64 targets for dynamo, dynamokt, env, and standards.

## What We Built (in curiosity)

### 1. LambdaRuntime — Custom Runtime API Loop

A minimal implementation that polls the AWS Lambda Runtime API over HTTP. Located at `curiosity/server/server-native-runtime/src/nativeMain/kotlin/.../runtime/LambdaRuntime.kt`:

```kotlin
class LambdaRuntime(
    private val handler: suspend (String) -> String
) {
    private val runtimeApi = getEnv("AWS_LAMBDA_RUNTIME_API")
        ?: error("AWS_LAMBDA_RUNTIME_API not set")
    private val baseUrl = "http://$runtimeApi/2018-06-01"
    private val client = HttpClient(Curl) { expectSuccess = false }

    suspend fun run() {
        while (true) { processNextInvocation() }
    }

    private suspend fun processNextInvocation() {
        val nextResponse = client.get("$baseUrl/runtime/invocation/next")
        val requestId = nextResponse.headers["Lambda-Runtime-Aws-Request-Id"]
            ?: error("No request ID")
        val eventBody = nextResponse.bodyAsText()

        try {
            val result = handler(eventBody)
            client.post("$baseUrl/runtime/invocation/$requestId/response") {
                contentType(ContentType.Application.Json)
                setBody(result)
            }
        } catch (e: Exception) {
            val errorPayload = buildJsonObject {
                put("errorMessage", e.message ?: "Unknown error")
                put("errorType", e::class.simpleName ?: "Exception")
            }.toString()
            client.post("$baseUrl/runtime/invocation/$requestId/error") {
                contentType(ContentType.Application.Json)
                setBody(errorPayload)
            }
        }
    }
}
```

### 2. Key Technical Findings

#### libcrypt.so.1 Problem
Kotlin/Native's sysroot links against glibc 2.25 which requires `libcrypt.so.1`. Amazon Linux 2023 only has `libcrypt.so.2`. Solution: Lambda Layer with `libcrypt.so.1` extracted from AL2 Docker image.

```bash
docker run --rm --platform linux/arm64 amazonlinux:2 sh -c 'cat /lib64/libcrypt-2.26.so' > lambda-layer/lib/libcrypt.so.1
```

The layer zip needs `lib/libcrypt.so.1` and the Lambda needs env var `LD_LIBRARY_PATH=/opt/lib:/lib64:/usr/lib64`.

#### HTTPS/TLS
- CIO engine does NOT support TLS on Kotlin/Native ("TLS sessions are not supported on Native platform")
- Curl engine works but needs CA cert path: `engine { caInfo = "/etc/pki/tls/certs/ca-bundle.crt" }`

#### Runtime
- Must use `Runtime.PROVIDED_AL2023` with the libcrypt layer
- Architecture: `Architecture.ARM_64` (linuxArm64)
- Handler must be named `bootstrap`
- Cold start: ~35-60ms (vs 2-5+ seconds JVM)

### 3. Gradle Convention Plugin Pattern

From `curiosity/buildSrc/src/main/kotlin/curiosity-native-lambda.gradle.kts`:

```kotlin
plugins {
    kotlin("multiplatform")
    id("kotlinx-serialization")
}

kotlin {
    linuxArm64()
    sourceSets {
        nativeMain {
            dependencies {
                implementation(project(":server:server-native-runtime"))
            }
        }
    }
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

tasks.register<Zip>("packageLambda") {
    dependsOn("linkReleaseExecutableLinuxArm64")
    archiveFileName.set("${project.name}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("lambda"))
    from(layout.buildDirectory.dir("bin/linuxArm64/releaseExecutable")) {
        rename(".*\\.kexe", "bootstrap")
    }
    filePermissions { unix("rwxr-xr-x") }
}
```

Each consumer just needs:
```kotlin
plugins { id("curiosity-native-lambda") }
kotlin {
    linuxArm64 {
        binaries { executable { entryPoint = "com.example.main" } }
    }
}
```

### 4. CDK Helper

```kotlin
fun nativeFunction(id: String, functionName: String, moduleName: String, ...) = Function(id) {
    functionName(functionName)
    code(Code.fromAsset("../server/$moduleName/build/lambda/$moduleName.zip"))
    handler("bootstrap")
    architecture(Architecture.ARM_64)
    runtime(Runtime.PROVIDED_AL2023)
    layers(listOf(nativeLibsLayer))
    environment(mapOf(
        "Stage" to app.env,
        "LD_LIBRARY_PATH" to "/opt/lib:/lib64:/usr/lib64"
    ))
}
```

## Goal for awskt

Make the lambda handler infrastructure work on both JVM and Native by extracting common logic into `commonMain`.

### Key Insight

Both JVM and Native Lambda handlers do the same thing: receive JSON → process → return JSON. The difference is only how the JSON arrives:
- **JVM**: AWS runtime calls handler class with `InputStream`/`Context`
- **Native**: Custom code polls Runtime API over HTTP, gets JSON string

### What Needs to Happen

#### A. New Module: `lambda/lambda-native` (or integrate into existing modules)

Contains:
- `LambdaRuntime` class (the HTTP polling loop) — nativeMain only
- The `libcrypt.so.1` layer files and `packageNativeLayer` task

#### B. Refactor `lambda-coroutines` to Multiplatform

Current state (JVM-only):
- `lambda()` function wraps handler with `runBlocking`, sets up MDC context
- `lambdaInput<T>()`, `lambdaIO<T,R>()` for typed handlers
- `SuspendingLambda`, `InputLambda<T>`, `IOLambda<T,R>` abstract classes
- Global `lambdaContext: Context` and `lambdaJson: Json`
- Depends on `com.amazonaws:aws-lambda-java-core` for `Context` interface

Target state:
- **commonMain**: Common `LambdaContext` interface (requestId, functionName, remainingTimeMs), JSON handler types, shared `lambdaJson`
- **jvmMain**: Adapter from Java `Context` → common `LambdaContext`, existing `lambda()`/`lambdaInput()`/`lambdaIO()` functions unchanged
- **nativeMain**: `LambdaRuntime` implementation, adapter from Runtime API headers → common `LambdaContext`

#### C. Refactor `lambda-eventbridge` to Multiplatform

Current state (JVM-only):
- `eventBridge(input: InputStream, context: Context, ...)` entry point
- `EventBridgeHandlerConfig` with `on<T>()`, `type<T>()`, `any()` handler registration
- Event routing based on `detail-type`
- SQS batch support
- Uses `InputStream`, `Context`, SLF4J logging, `java.util.UUID`

Target state:
- **commonMain**: `eventBridge(eventJson: String, config: ...)` — pure Kotlin event routing, schema matching, error handling
- **jvmMain**: `eventBridge(input: InputStream, context: Context, ...)` — adapter that reads stream, calls common version, handles SQS batch
- **nativeMain**: Direct integration with `LambdaRuntime { json -> eventBridge(json, config) }`

#### D. Logging — Multiplatform with Native Lambda Support

Current state of `logging` module (already partially multiplatform with JVM + JS + wasmJs):
- **commonMain**: `KLogger` interface with `info()`, `warn()`, `error()`, `debug()`, `trace()`; `Logging` object; `LogContext` interface
- **jvmMain**: SLF4J-backed implementation, MDC for structured context
- **jsMain/wasmJsMain**: Console-based implementation

Needed:
- **nativeMain**: Lambda-optimized logging implementation that writes structured JSON to stdout (CloudWatch picks it up)
- Should support request ID context (from `LambdaContext`)
- Format: `{"timestamp":"...","level":"INFO","message":"...","requestId":"..."}`

#### E. Events Module — Already Partially Multiplatform

The `events` module already has `EventSchema<T>` and `ApplicationEventPoster` in commonMain. The `EventBridgeSubmitter` (JVM) uses the AWS SDK. On the 2.3.x branch with the native AWS SDK, a native implementation might be possible too.

### Existing awskt Patterns to Preserve

- `lambda-core` provides `@AWSLambdaConstructor` annotation and `MockLambdaContext`
- `lambda-coroutines` provides `lambda()`, `lambdaInput<T>()`, `lambdaIO<T,R>()`
- `lambda-eventbridge` provides `eventBridge()` with `on<T>()` routing
- `lambda-logging` provides Lambda-specific logback configuration and MDC handling
- All existing JVM consumers must continue working unchanged

### Proposed Module Structure

```
lambda/
├── lambda-core/          (keep JVM — annotation + MockContext)
├── lambda-coroutines/    (make multiplatform — common handler types)
├── lambda-eventbridge/   (make multiplatform — common event routing)
├── lambda-native/        (NEW — LambdaRuntime, native layer, packaging)
├── lambda-logging/       (keep JVM — logback/logstash specific)
├── lambda-sqs/           (keep JVM for now)
├── lambda-api-gateway/   (keep JVM)
└── lambda-api-gateway-ktor/ (keep JVM)
```

### Performance Benchmarks (from curiosity testing)

- Cold start: ~35-60ms init (native) vs 2-5+ seconds (JVM)
- HTTP fetch (HTTPS via Curl): ~460ms
- JSON serialization (1000 items round-trip): ~62ms
- Collections (10k items filter/group): <1ms
- Coroutines (10 parallel tasks): ~136ms
- Memory: 41MB used of 128MB allocated
- Binary size: ~4.1MB (zip)
