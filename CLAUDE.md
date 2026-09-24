# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AWSKT is a Kotlin multiplatform library collection for building AWS applications, particularly AWS Lambda functions. The
project is organized as a Gradle multi-module build with both JVM-only and multiplatform modules. Multiplatform modules
target the JVM and Kotlin/Native (`linuxX64`, `linuxArm64`, `macosArm64`), so that the same code runs as a native Lambda.

Since 3.0 the library does not use the AWS SDK for Kotlin at runtime. The `aws/*` modules are a small hand-written client
(SigV4 signing plus a Ktor transport). The SDK appears only in JVM tests, in `aws-dynamodb-sdk-adapter`, in
`aws-sdk-credentials`, and in `lambda-dynamo-streams`'s Kinesis redrive. `NATIVE-AWS-CLIENT-PLAN.md` records the design and
its decisions.

## Build Commands

### Core Development Commands

```bash
# Build all modules
./gradlew build

# Run all tests
./gradlew test

# Full verification (includes all targets)
./gradlew check

# Clean and rebuild
./gradlew clean build

# Run tests with detailed output
./gradlew test --info
```

### Publishing Commands

```bash
# Publish to local repository
./gradlew publishToMavenLocal

# Create development snapshot
./gradlew snapshot

# Release to Maven Central: use the script, never `./gradlew final` alone
scripts/release.sh --dry-run
scripts/release.sh
```

`./gradlew final` exits 0 without publishing anything: the Central deployment stops at `VALIDATED`. It also skips the
`gradle-plugin` included build. `scripts/release.sh` runs the clean check, `final`, the plugin publish, the explicit
publish of both deployments, and a check that the artifacts answer on repo1. See "Releasing" in `AGENTS.md`.

## Project Architecture

### Module Structure

The project follows a multi-module architecture with these key components:

#### AWS Clients (under `aws/`)

- **aws-signing**: SigV4 signing
- **aws-core**: Signed transport: credentials, endpoints, retries, error mapping. It depends on `aws-signing` and Ktor
  only, never on `env`, `standards` or `logging` (plan Decision 6), and never on the AWS SDK
- **aws-dynamodb, aws-eventbridge, aws-s3, aws-sqs, aws-sns, aws-kinesis, aws-kms, aws-lambda, aws-scheduler,
  aws-secretsmanager, aws-ses, aws-cloudwatch-logs, aws-bedrock-runtime, aws-opensearch**: One client per service,
  each depending on `aws-core` only
- **aws-dynamodb-sdk-adapter** (JVM): A `DynamoDb` backed by the SDK's `DynamoDbClient`, for migrations
- **aws-sdk-credentials** (JVM): Lends the SDK's default credential chain to every awskt client. `aws-core`'s own
  default reads environment variables only

#### Core Libraries

- **standards**: Language-level extensions and utilities for AWS environments
- **env**: Environment variable and secrets management
- **logging**: Structured logging utilities with Kotlin Serialization support
- **dynamo**: `AttributeValue` and its serialization, shared by `dynamokt` and `aws-dynamodb`
- **dynamokt**: Type-safe DynamoDB client with coroutine support
- **dynamokt-exposed**: An Exposed-style table DSL over `dynamokt`
- **events**: Event handling and EventBridge integration
- **serialization**: JSON serialization utilities
- **jwt**: JWT verification (RS256/ES256 with JWKS) and ES256 signing on JVM and native, via cryptography-kotlin

#### Lambda Modules (under `lambda/`)

- **lambda-core**: Base Lambda functionality and annotations
- **lambda-api-gateway**: API Gateway proxy handler
- **lambda-api-gateway-ktor**: Ktor integration for API Gateway
- **lambda-api-gateway-ktor-jwt** (JVM): `JWTPrincipal` and the `ApiGatewayJWT` plugin
- **lambda-coroutines**: Coroutine context for handlers, including `lambdaContext`
- **lambda-native**: The Kotlin/Native Lambda runtime; `lambda-native-smoke` is its live smoke test
- **lambda-appsync**: AppSync resolver support
- **lambda-dynamo-streams**: DynamoDB Streams processing
- **lambda-eventbridge**: EventBridge event handling
- **lambda-kinesis**: Kinesis stream processing
- **lambda-sns**: SNS message handling
- **lambda-sqs**: SQS message processing
- **lambda-logging**: Lambda-specific logging configuration

#### Supporting Modules

- **appsync**: AppSync utilities
- **cognito**: Cognito integration
- **test**: AWS testing utilities and mocks
- **gradle-plugin** (included build): `com.steamstreet.awskt.native-lambda`, for packaging native Lambdas

### Build Configuration

- Uses custom Gradle convention plugins:
    - `steamstreet-common.jvm-library-conventions` for JVM-only modules
    - `steamstreet-common.multiplatform-library-conventions` for multiplatform modules
- Enforces Java 17 toolchain and Kotlin 2.3
- Uses explicit API mode for better API stability
- Supports context parameters (`-Xcontext-parameters`)
- ABI dumps live in each module's `api/` directory. `checkKotlinAbi` runs in `check`; after an intended public API
  change, regenerate the dumps with `./gradlew :module:updateKotlinAbi`

## Testing

### Test Framework

- Uses Kotlin's built-in test framework with JUnit Platform
- **Kluent** for fluent assertions
- **MockK** for mocking
- **Testcontainers** for integration testing (especially DynamoDB Local)
- **kotlinx-coroutines-test** for coroutine testing

### Test Commands

```bash
# Run all tests
./gradlew test

# Run tests for specific module
./gradlew :dynamokt:test

# Run multiplatform tests
./gradlew jvmTest jsTest

# Run with container tests
./gradlew test -Djava.library.path=dynamo_libs
```

### Test Patterns

- Unit tests in `src/test/kotlin/` (JVM) or `src/commonTest/kotlin/` (multiplatform)
- Integration tests use Testcontainers for realistic AWS service testing
- Coroutine tests use `runTest` from kotlinx-coroutines-test
- Mock-based testing with MockK for external dependencies

## Development Workflow

### Key Libraries and Dependencies

- **awskt `aws/*` clients**: AWS service integration (the AWS SDK only in JVM tests and the JVM bridge modules)
- **Kotlin Coroutines**: Async programming model
- **Kotlin Serialization**: JSON handling and structured logging
- **Ktor**: HTTP server framework for Lambda API Gateway integration

### Code Style

- Explicit API mode is enforced - all public APIs must have explicit visibility
- Context receivers are enabled for advanced Kotlin patterns
- ktfmt is configured for code formatting (Kotlin default style)
  - Format all code: `./gradlew ktfmtFormat`
  - Check formatting: `./gradlew ktfmtCheck`
  - Format specific module: `./gradlew :module-name:ktfmtFormat`
  - **Note:** ktfmt does NOT run automatically - must be invoked manually
- Follow existing patterns in similar modules when adding new functionality

### Adding New Modules

1. Add module to `settings.gradle.kts`
2. Create `build.gradle.kts` using appropriate convention plugin
3. Follow the package structure: `com.steamstreet.{module-name}`
4. Add tests following existing patterns
5. Update dependencies in root `build.gradle.kts` if needed

## Common Patterns

### Lambda Function Development

- Extend appropriate base classes from `lambda-core`
- Use structured logging from the `logging` module
- Leverage `env` module for configuration management
- Use `dynamokt` for type-safe DynamoDB operations

### DynamoDB Integration

- Use `DynamoKt` for type-safe operations
- Implement `Item` interface for data classes
- Use `MutableItem` for updateable entities
- Leverage `Transaction` for atomic operations

### Event Processing

- Use `EventBridge` utilities for event publishing
- Implement appropriate handler interfaces for stream processing
- Use `events` module for event schema definitions

## Publishing

The project publishes to Maven Central as `com.steamstreet.awskt:<module>`, with no `awskt-` prefix, from 3.1.0 on.
Versions through 3.0.0 published as `com.steamstreet:awskt-<module>`; those coordinates are not maintained.

Versions come from nebula.release and follow the branch. The current release line is `3.1.x`, where the default release
scope is patch. `main` was last updated in 2023 and is not where releases are cut.

Consumers moving from 2.x should read `docs/migrating-2.x-to-3.x.md`.

## Commit Guidelines

Before committing changes:
- Run `./gradlew ktfmtFormat` to format all changed files to Kotlin default style
- Optimize imports in changed files
- Ensure tests pass for affected modules