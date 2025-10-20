# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AWSKT is a Kotlin multiplatform library collection for building AWS applications, particularly AWS Lambda functions. The
project is organized as a Gradle multi-module build with both JVM-only and multiplatform (JVM/JS/iOS) modules.

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

# Release to Maven Central
./gradlew final
```

## Project Architecture

### Module Structure

The project follows a multi-module architecture with these key components:

#### Core Libraries

- **standards**: Language-level extensions and utilities for AWS environments
- **env**: Environment variable and secrets management
- **logging**: Structured logging utilities with Kotlin Serialization support
- **dynamokt**: Type-safe DynamoDB client with coroutine support
- **events**: Event handling and EventBridge integration
- **serialization**: JSON serialization utilities

#### Lambda Modules (under `lambda/`)

- **lambda-core**: Base Lambda functionality and annotations
- **lambda-api-gateway**: API Gateway proxy handler
- **lambda-api-gateway-ktor**: Ktor integration for API Gateway
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

### Build Configuration

- Uses custom Gradle convention plugins:
    - `steamstreet-common.jvm-library-conventions` for JVM-only modules
    - `steamstreet-common.multiplatform-library-conventions` for multiplatform modules
- Enforces Java 17 toolchain
- Uses explicit API mode for better API stability
- Supports context receivers (`-Xcontext-receivers`)

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

- **AWS SDK**: Primary AWS service integration
- **Kotlin Coroutines**: Async programming model
- **Kotlin Serialization**: JSON handling and structured logging
- **Ktor**: HTTP server framework for Lambda API Gateway integration

### Code Style

- Explicit API mode is enforced - all public APIs must have explicit visibility
- Context receivers are enabled for advanced Kotlin patterns
- No dedicated linting tools (Detekt/Ktlint) are currently configured
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

The project publishes to Maven Central under the `com.steamstreet` group with artifact IDs prefixed with `awskt-`.

Current branch: `2.1.x` (development branch)  
Main branch: `main` (stable releases)
- Before commit, always optimize imports and format to default Kotlin style any changed files