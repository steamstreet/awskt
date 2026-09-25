# AWSKT - Kotlin/AWS Commons

Kotlin libraries for building AWS applications, with a focus on AWS Lambda. Most modules are Kotlin
Multiplatform and target the JVM and Kotlin/Native (`linuxX64`, `linuxArm64`, `macosArm64`), so the
same handler code runs on the JVM or as a native `provided.al2023` Lambda.

Since 3.0, awskt does not use the AWS SDK for Kotlin at runtime. The `aws/*` modules are a small
hand-written client: SigV4 signing plus a Ktor transport.

**Moving from 2.x?** Read [Migrating from awskt 2.x to 3.x](docs/migrating-2.x-to-3.x.md). The
coordinates changed, and so did the default credentials chain.

## Installation

```kotlin
dependencies {
    implementation("com.steamstreet.awskt:dynamokt:VERSION")
    implementation("com.steamstreet.awskt:lambda-api-gateway-ktor:VERSION")
    implementation("com.steamstreet.awskt:aws-sqs:VERSION")
    // Add other modules as needed
}
```

Artifacts publish as `com.steamstreet.awskt:<module>` from 3.1.0 on. Versions through 3.0.0 used
`com.steamstreet:awskt-<module>`; those coordinates are no longer maintained, and 3.0.0 should not be
used. Maven and other non-Gradle builds must name the platform variant of a multiplatform module,
for example `dynamokt-jvm`.

Requires Kotlin 2.3 and Ktor 3.5 or later. Handlers in `lambda-sqs` and `lambda-sns` declare context
parameters, so a build that subclasses them needs `-Xcontext-parameters`.

## AWS clients

Each service module exposes an interface, a factory function that takes a configuration lambda, and
suspending calls. Every service module depends only on `aws-core`.

```kotlin
val sqs = Sqs { region = "us-west-2" }
sqs.sendMessage(SendMessageRequest(queueUrl = queueUrl, messageBody = body))
```

| Module | Provides |
|---|---|
| `aws-signing` | SigV4 signing (`SigV4`, `AwsCredentials`) |
| `aws-core` | The signed transport: credentials, endpoints, retries, error mapping, `AwsServiceClient` |
| `aws-dynamodb` | `DynamoDb`: items, query, scan, batch and transact operations, table create/describe/delete |
| `aws-eventbridge` | `EventBridge.putEvents` |
| `aws-kinesis` | Put records, shards, iterators, get records |
| `aws-kms` | Encrypt, decrypt, re-encrypt, data keys, random, sign, verify |
| `aws-lambda` | `invoke`, `invokeWithResponseStream`, and `listFunctions` / `listFunctionsPaginated` |
| `aws-opensearch` | A signed OpenSearch transport: `request`, `search`, `bulk`, `getDocument` |
| `aws-s3` | Get, put, head and delete object, and `S3Presigner` |
| `aws-scheduler` | EventBridge Scheduler schedules |
| `aws-secretsmanager` | Get, put and batch-get secret values |
| `aws-ses` | SES v2 `sendEmail` |
| `aws-sns` | Publish, batch publish, mobile push endpoints and APNs/FCM payloads |
| `aws-sqs` | Send, receive and delete (single and batch), visibility, queue URLs |
| `aws-bedrock-runtime` | `converse` and `converseStream` |
| `aws-cloudwatch-logs` | Logs Insights queries |
| `aws-dynamodb-sdk-adapter` (JVM) | `SdkBackedDynamoDb`: the `DynamoDb` interface backed by the SDK's `DynamoDbClient` |
| `aws-sdk-credentials` (JVM) | The AWS SDK's default credential chain for every awskt client |

### Credentials

By default, clients read credentials from **environment variables only**, which is what Lambda
provides. On ECS or EC2, or on a machine that uses `~/.aws` profiles or SSO, add
`aws-sdk-credentials` and install the SDK's chain once at startup:

```kotlin
AwsCredentialsDefaults.provider = sdkDefaultChainCredentialsProvider()
```

A client can also be given its own provider through `credentialsProvider` in its configuration. The
region comes from configuration, `AWS_REGION`, `AWS_DEFAULT_REGION` or the `aws.region` system
property, never from `~/.aws/config`.

## Core libraries

### standards
General Kotlin helpers: `asyncLazy`, `mutableLazy`, `cached(Duration)`, `whenNotNull`,
string and collection extensions, and exception types such as `NotFoundException`. Also targets JS,
Wasm and iOS.

```kotlin
val config = asyncLazy { loadConfig() }          // config.get() suspends
var token: String by cached(5.minutes) { fetchToken() }
```

### env
Reads environment variables through `Env` and the `env()` delegate. A value of the form
`Secret_<id>[.jsonKey]`, or a separate `Secret_<KEY>` variable, is resolved from Secrets Manager: on
the JVM through the AWS SDK, and on native through `aws-secretsmanager`. Resolved values are not
cached, so read them once. On the JVM only, `AppConfig.<app>.<env>.<config>.<key>` values are
resolved from AppConfig.

```kotlin
val tableName = Env["TABLE_NAME"]                // throws if missing
val region: String? = Env.optional("AWS_REGION")
val apiKey by env("API_KEY")
```

### logging
Structured JSON logging. Common code uses the suspending `log` object, whose context follows
coroutines; the JVM adds slf4j helpers such as `logInfo` and `logValue`.

```kotlin
log.ctx({ "requestId" `is` id }) {
    log.info("Processing")
    log.data("User created", user, field = "user")
}
```

### serialization
`JsonObject` helpers: `copy`, `copyOrBuild`, `diff`, `comprehensiveDiff` and `deepEquals`.

### events
Typed EventBridge publishing. The default poster is an `EventBridgeSubmitter` built on
`aws-eventbridge`, configured from the `EventBusArn` and `EventPosterSource` environment variables.

```kotlin
@Serializable data class OrderEvent(val orderId: String, val status: String)
val OrderCompleted = eventSchema<OrderEvent>("OrderCompleted")

OrderCompleted.post(OrderEvent("123", "completed"))
```

### jwt
JWT verification and signing on the JVM and native, where `com.auth0:java-jwt` cannot run.
Verifies RS256 and ES256 against a cached JWKS, and signs ES256, for example a Sign in with Apple
client secret. Built on cryptography-kotlin: the JDK's providers on the JVM, and a statically linked
OpenSSL 3 on native.

```kotlin
val apple = JwtVerifier(
    keys = JwksKeySource("https://appleid.apple.com/auth/keys"),
    issuers = setOf("https://appleid.apple.com"),
    audiences = setOf("com.example.app"),
)
val userId = apple.verify(idToken).claims.subject

val clientSecret = Es256Signer.fromPrivateKey(p8, keyId = keyId).sign {
    issuer = teamId; subject = clientId
    audience("https://appleid.apple.com")
    issuedAt = now; expiresAt = now + 180.days
}
```

`JwtVerifier` requires both issuers and an audience rule, and takes the accepted algorithms from
its own configuration, never from the token.

Cognito access tokens carry no `aud`. They name the app client in `client_id` and set `token_use`
to `access`, and `cognitoAccessToken` checks both, along with the pool's issuer and keys:

```kotlin
val cognito = JwtVerifier.cognitoAccessToken("us-east-1", "us-east-1_AbCdEf123", setOf(appClientId))
val userId = cognito.verify(accessToken).claims.subject
```

For other issuers, `JwtAudience.Claim` checks a claim other than `aud`, and `requiredClaims` requires
exact string values.

## DynamoKt

### dynamokt
A single-table DynamoDB library built on `aws-dynamodb`. `DynamoKt` holds the table configuration,
and sessions provide get, put, update, query, scan and transactions over `Item` and `MutableItem`,
with typed attribute delegates.

```kotlin
val db = DynamoKt(table = "app", pkName = "pk", skName = "sk")
val session = db.session()
session.put("user#1", "profile") { set("name", "Alice") }
val name = session.get("user#1", "profile").getString("name")
```

[Detailed documentation](docs/dynamokt.md)

### dynamo
`AttributeValue` and its serializer, and the DynamoDB stream models, shared by `dynamokt` and
`aws-dynamodb`.

### dynamokt-exposed
An Exposed-style typed DSL for DynamoDB: `Table`, typed columns, `Op` expressions and `Database`.
See its own [README](dynamokt-exposed/README.md).

## Lambda modules

JVM handlers are abstract classes you subclass. Native handlers are `main` functions built with the
`*Lambda { }` entry points listed for each module.

### lambda-coroutines
The coroutine foundation: `lambdaContext`, `lambdaJson` and, on the JVM, the handler base classes
`SuspendingLambda`, `IOLambda<T, R>` and `InputLambda<T>`.

```kotlin
class MyHandler : IOLambda<Request, Response>(Request.serializer(), Response.serializer()) {
    override suspend fun handle(input: Request) = Response("ok ${input.id}")
}
```

### lambda-native
The Kotlin/Native custom runtime for `provided.al2023`. Native targets only.

```kotlin
fun main() = nativeLambdaIO<Request, Response> { request -> Response("ok ${request.id}") }
```

The `com.steamstreet.awskt.native-lambda` Gradle plugin, from the `gradle-plugin` included build,
packages a `linuxArm64` executable as a Lambda bootstrap zip.

### lambda-core
JVM only. `@AWSLambdaConstructor`, `MockLambdaContext` for tests, and the `aws-lambda-java-core`
dependency.

### lambda-api-gateway
Serializable API Gateway models for REST proxy (`ApiGatewayProxyRequest`/`Response`) and HTTP API
v2 (`ApiGatewayV2HttpRequest`/`Response`), with JVM base classes `ApiGatewayProxyHandler` and
`ApiGatewayV2HttpHandler`.

```kotlin
class ApiHandler : ApiGatewayProxyHandler() {
    override suspend fun handle(input: ApiGatewayProxyRequest) =
        ApiGatewayProxyResponse(statusCode = 200, body = """{"result":"success"}""")
}
```

### lambda-api-gateway-ktor
Runs a Ktor `Application` behind API Gateway, REST or HTTP API, with no server engine. JVM:
`APIGatewayLambdaServer` and `APIGatewayV2LambdaServer`. Native: `apiGatewayKtorLambda { }` and
`apiGatewayV2KtorLambda { }`.

```kotlin
class KtorHandler : APIGatewayLambdaServer() {
    override fun Application.module() {
        routing { get("/users/{id}") { call.respondText(call.parameters["id"]!!) } }
    }
}
```

### lambda-api-gateway-ktor-jwt
JVM only. `JWTPrincipal` and the `ApiGatewayJWT` plugin, which build a Ktor principal from the
Cognito claims API Gateway has already verified. It does not validate tokens itself.

```kotlin
class KtorHandler : APIGatewayLambdaServer() {
    override fun Application.module() {
        install(ApiGatewayJWT)
        routing {
            authenticate("api-gateway-jwt") {
                get("/me") { call.respond(call.principal<JWTPrincipal>()!!.subject) }
            }
        }
    }
}
```

These declarations shipped inside `lambda-api-gateway-ktor` before 3.0. They are now in this
separate artifact, in the same package:
`implementation("com.steamstreet.awskt:lambda-api-gateway-ktor-jwt:VERSION")`.

### lambda-appsync
Direct-Lambda AppSync resolvers routed by `type` and `field`. JVM: `appSync(input, output, context) { }`.
Native: `appSyncLambda { }`.

```kotlin
appSync(input, output, context) {
    type("Query") {
        field("getUser", GetUserArgs.serializer(), User.serializer()) { args -> loadUser(args.id) }
    }
}
```

### lambda-dynamo-streams
DynamoDB stream handlers that accept direct, Kinesis-wrapped and SQS-redriven batches. JVM:
`DynamoStreamHandler` and `DynamoKtStreamHandler`. Native: `dynamoStreamLambda`,
`dynamoStreamBatchLambda` and `dynamoKtStreamLambda`.

```kotlin
class StreamHandler : DynamoStreamHandler() {
    override suspend fun handleRecord(record: DynamoStreamEvent) {
        when (record.eventName) { "INSERT" -> onInsert(record); "REMOVE" -> onRemove(record) }
    }
}
```

### lambda-eventbridge
Routes EventBridge events by detail-type through an `EventSchema`. JVM: `EventBridgeFunction`.
Native: `eventBridgeLambda { }`.

```kotlin
class OrderHandler : EventBridgeFunction {
    override suspend fun EventBridgeHandlerConfig.onEvent() {
        on(OrderCompleted) { order -> finalizeOrder(order) }
    }
}
```

### lambda-kinesis
Kinesis event models and batch processing with partial failures. JVM: `KinesisHandler`. Native:
`kinesisLambda` and `kinesisBatchLambda`.

```kotlin
class Processor : KinesisHandler() {
    override suspend fun processRecord(record: KinesisRecord) {
        val data = Json.decodeFromString<SensorData>(record.kinesis.decodedData())
    }
}
```

### lambda-sns
Typed or raw SNS message processing. JVM: `SNSHandler<T>`. Native: `snsLambda` and `snsRawLambda`.

```kotlin
class NotificationHandler : SNSHandler<Notification>(Notification.serializer()) {
    context(record: SnsRecord)
    override suspend fun handleMessage(message: Notification) = sendEmail(message)
}
```

### lambda-sqs
SQS handlers. JVM: `SQSRawHandler`, `SQSHandler<T>`, and `SQSBatchHandler<T>`, which reports
partial batch failures. Native: `sqsLambda`, `sqsBatchLambda` and `sqsRawLambda`.

```kotlin
class QueueProcessor : SQSBatchHandler<Job>(Job.serializer()) {
    context(record: SQSRecord)
    override suspend fun handleMessage(message: Job) {
        executeJob(message)   // a throw fails only this record
    }
}
```

### lambda-logging
JVM only. A logback appender that writes through the Lambda runtime logger, and a default
`logback.xml` that uses it with a JSON encoder. Adding the dependency is enough.

### lambda-default
JVM only. A bundle of `env`, `standards`, `logging`, `lambda-core` and `lambda-logging`.

## Supporting modules

### appsync
Serializable AppSync resolver event models (`AppSyncContext`, `AppSyncIdentity`, `AppSyncInfo`) and
`String.appSyncContext()`.

### cognito
JVM only. `Claims` and `JsonElementClaim`, which read Cognito claims from API Gateway's authorizer
context. This is the support code for `lambda-api-gateway-ktor-jwt`.

### test
JVM only. Local and mock AWS for tests: `DynamoLocalBuilder` (in-memory DynamoDB Local, exposing an
SDK `DynamoDbClient`), `EventBridgeMock` (which implements awskt's `EventBridge`, with pattern
matching), `DynamoStreamRunner`, `SqsMock`, `S3Local` and `LambdaMock`.

```kotlin
val dynamo = DynamoLocalBuilder().apply { start() }
val db = DynamoKt("table", builder = { SdkBackedDynamoDb(dynamo.client) })
val events = EventBridgeMock()
```

## Building

```bash
./gradlew check                     # every target, tests and ABI dumps
./gradlew :aws:aws-core:allTests    # one module
./gradlew publishToMavenLocal
```

Releases are cut with `scripts/release.sh`; see "Releasing" in [AGENTS.md](AGENTS.md).

## License

MIT. See [LICENSE](LICENSE).
