# AWSKT - Kotlin/AWS Commons

A comprehensive collection of Kotlin libraries for building AWS applications, with special focus on AWS Lambda functions. The libraries support both JVM-only and multiplatform (JVM/JS/iOS) targets.

## Installation

Add the following to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.steamstreet:awskt-standards:VERSION")
    implementation("com.steamstreet:awskt-dynamokt:VERSION")
    implementation("com.steamstreet:awskt-lambda-core:VERSION")
    // Add other modules as needed
}
```

## Core Libraries

### standards
Language-level extensions and utilities optimized for AWS environments. Provides foundational Kotlin extensions that enhance productivity when working with AWS services.

**Key Features:**
- Common AWS utility functions
- Extension functions for AWS SDK types
- Result handling and error utilities

### env
Unified configuration management across multiple sources. Seamlessly access configuration values from environment variables, system properties, or AWS Secrets Manager.

**Usage:**
```kotlin
// Automatically resolves from env var, system property, or AWS secret
val apiKey = getEnvironmentVariable("API_KEY")
val dbPassword = getSecret("db/password")
```

### logging
Structured logging utilities with Kotlin Serialization support. Provides explicit, type-safe logging without requiring a Logger implementation.

**Usage:**
```kotlin
logInfo("Some info")
logWarning("Some warning", t)

// Log structured data
@Serializable
data class UserEvent(val name: String, val action: String)
logValue("User action", "event", UserEvent("Alice", "login"))
```

Lambda-Core automatically configures CloudWatch Logs integration for proper structured logging.

### serialization
JSON serialization utilities built on Kotlin Serialization. Provides common serialization patterns and utilities for AWS services.

**Key Features:**
- Pre-configured JSON serializers for AWS services
- Custom serializers for AWS-specific types
- Utility functions for JSON conversion

### events
Event handling and EventBridge integration. Simplifies working with AWS EventBridge for event-driven architectures.

**Usage:**
```kotlin
// Define event schema
@Serializable
data class OrderEvent(val orderId: String, val status: String)

// Publish events
eventBridge.putEvent(OrderEvent("123", "completed"))
```

## DynamoKt

### dynamokt
Type-safe DynamoDB client with full coroutine support. Provides a Kotlin-first API for DynamoDB operations with compile-time safety.

**Key Features:**
- Type-safe table operations
- Automatic serialization/deserialization
- Transaction support
- Query and scan builders
- Secondary index support

### [Detailed Documentation](docs/dynamokt.md)

## Lambda Modules

### lambda-core
Foundation for all Lambda functions. Provides base classes, annotations, and core Lambda functionality.

**Key Features:**
- Lambda handler base classes
- Automatic CloudWatch Logs configuration
- Request/response serialization
- Error handling and recovery
- Context management

**Usage:**
```kotlin
class MyHandler : LambdaHandler<Request, Response> {
    override suspend fun handleRequest(input: Request, context: Context): Response {
        logInfo("Processing request", "id" to input.id)
        return Response("Success")
    }
}
```

### lambda-api-gateway
API Gateway proxy integration for REST APIs. Simplifies building REST APIs with Lambda.

**Key Features:**
- Request/response mapping
- Path parameter extraction
- Query string parsing
- Header management
- CORS support

**Usage:**
```kotlin
class ApiHandler : ApiGatewayHandler() {
    override suspend fun handleRequest(event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent {
        return response(200) {
            body = Json.encodeToString(Result("success"))
            headers = mapOf("Content-Type" to "application/json")
        }
    }
}
```

### lambda-api-gateway-ktor
Ktor server integration for API Gateway. Run full Ktor applications in Lambda.

**Key Features:**
- Full Ktor routing support
- Middleware and plugins
- Content negotiation
- Authentication/Authorization
- Seamless Lambda deployment

**Usage:**
```kotlin
class KtorHandler : ApiGatewayKtorHandler() {
    override fun Application.module() {
        routing {
            get("/users/{id}") {
                val id = call.parameters["id"]
                call.respond(User(id!!, "John"))
            }
        }
    }
}
```

### lambda-api-gateway-ktor-jwt
`JWTPrincipal` and the `ApiGatewayJWT` authentication plugin, for reading Cognito claims off an API
Gateway request. JVM only — `ktor-server-auth-jwt` wraps `com.auth0:java-jwt` and publishes no klibs.

**Usage:**
```kotlin
class KtorHandler : APIGatewayLambdaServer() {
    override fun Application.module() {
        install(ApiGatewayJWT)
        routing {
            authenticate("api-gateway-jwt") {
                get("/me") {
                    val principal = call.principal<JWTPrincipal>()
                    call.respond(principal!!.subject)
                }
            }
        }
    }
}
```

> **Breaking change in 3.0.** These declarations used to ship inside `lambda-api-gateway-ktor`, which
> made every consumer of that adapter resolve `ktor-server-auth-jwt`, `com.auth0:java-jwt` and
> `com.auth0:jwks-rsa` whether or not it used them. They now live in this separate artifact, in the
> **same package**, so no imports change — but a build that uses `JWTPrincipal` or `ApiGatewayJWT`
> and does not already declare `ktor-server-auth-jwt` itself must add:
> ```kotlin
> implementation("com.steamstreet:awskt-lambda-api-gateway-ktor-jwt:VERSION")
> ```

### lambda-appsync
AppSync resolver support for GraphQL APIs. Build GraphQL resolvers with type safety.

**Key Features:**
- Direct resolver pattern
- Pipeline resolver support
- Type-safe field resolution
- Batch resolver optimization

**Usage:**
```kotlin
class UserResolver : AppSyncResolver<GetUserRequest, User> {
    override suspend fun resolve(request: GetUserRequest): User {
        return dynamoKt.get(request.id)
    }
}
```

### lambda-dynamo-streams
DynamoDB Streams event processing. Handle table changes with type-safe stream records.

**Key Features:**
- Stream record deserialization
- Change type detection (INSERT/MODIFY/REMOVE)
- Old/new image comparison
- Batch processing support

**Usage:**
```kotlin
class StreamHandler : DynamoStreamHandler<User>() {
    override suspend fun handleRecord(record: StreamRecord<User>) {
        when (record.eventName) {
            INSERT -> handleNewUser(record.newImage)
            MODIFY -> handleUserUpdate(record.oldImage, record.newImage)
            REMOVE -> handleUserDeletion(record.oldImage)
        }
    }
}
```

### lambda-eventbridge
EventBridge event handling. Process custom events from EventBridge.

**Key Features:**
- Type-safe event deserialization
- Event pattern matching
- Rule-based routing
- Event replay support

**Usage:**
```kotlin
class OrderEventHandler : EventBridgeHandler<OrderEvent>() {
    override suspend fun handleEvent(event: OrderEvent, context: Context) {
        when (event.status) {
            "pending" -> processPendingOrder(event)
            "completed" -> finalizeOrder(event)
        }
    }
}
```

### lambda-kinesis
Kinesis stream processing. Handle high-throughput streaming data.

**Key Features:**
- Record deserialization
- Batch processing
- Checkpointing support
- Error handling with DLQ

**Usage:**
```kotlin
class KinesisProcessor : KinesisHandler<SensorData>() {
    override suspend fun processRecords(records: List<SensorData>) {
        records.forEach { data ->
            if (data.temperature > threshold) {
                sendAlert(data)
            }
        }
    }
}
```

### lambda-sns
SNS message processing. Handle notifications from SNS topics.

**Key Features:**
- Message deserialization
- Message attributes support
- Subscription confirmation
- Error handling

**Usage:**
```kotlin
class NotificationHandler : SNSHandler<Notification>() {
    override suspend fun handleMessage(message: Notification, context: Context) {
        sendEmail(message.recipient, message.content)
        logInfo("Notification sent", "recipient" to message.recipient)
    }
}
```

### lambda-sqs
SQS message processing. Build reliable queue processors.

**Key Features:**
- Message deserialization
- Batch processing
- Dead letter queue support
- Message visibility timeout
- FIFO queue support

**Usage:**
```kotlin
class QueueProcessor : SQSHandler<Job>() {
    override suspend fun processMessage(message: Job): ProcessingResult {
        return try {
            executeJob(message)
            ProcessingResult.Success
        } catch (e: Exception) {
            ProcessingResult.Retry
        }
    }
}
```

### lambda-logging
Lambda-specific logging configuration. Enhanced logging for Lambda environments.

**Key Features:**
- Automatic CloudWatch Logs integration
- Correlation ID tracking
- Request/response logging
- Performance metrics
- Error aggregation

## Supporting Modules

### appsync
AppSync utilities and helpers. Additional tools for GraphQL API development.

**Key Features:**
- GraphQL schema utilities
- Subscription helpers
- Real-time data synchronization
- Offline support utilities

### cognito
Cognito integration utilities. Simplify user authentication and authorization.

**Key Features:**
- User pool management
- Token validation
- Custom authorizers
- User attributes handling
- MFA support

**Usage:**
```kotlin
// Validate JWT token
val claims = cognito.validateToken(token)

// Get user attributes
val user = cognito.getUser(userId)
```

### test
AWS testing utilities and mocks. Comprehensive testing support for AWS services.

**Key Features:**
- Local DynamoDB testing
- Lambda test harness
- Mock AWS services
- Integration test utilities
- Testcontainers integration

**Usage:**
```kotlin
class UserServiceTest {
    @Test
    fun testUserCreation() = runTest {
        withLocalDynamoDB { dynamo ->
            val service = UserService(dynamo)
            val user = service.createUser("Alice")
            
            user.name shouldBe "Alice"
        }
    }
}
```

## Build Configuration

### Gradle Setup

The project uses convention plugins for consistent build configuration:

```kotlin
plugins {
    id("steamstreet-common.jvm-library-conventions") // For JVM-only modules
    id("steamstreet-common.multiplatform-library-conventions") // For multiplatform
}
```

### Key Features
- Java 17 toolchain
- Explicit API mode for better API stability
- Context receivers support (`-Xcontext-receivers`)
- Kotlin coroutines throughout
- Comprehensive test coverage

## Contributing

1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Ensure all tests pass: `./gradlew test`
5. Submit a pull request

## License

Licensed under the Apache License 2.0. See LICENSE file for details.