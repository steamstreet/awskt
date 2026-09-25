# Migrating from awskt 2.x to 3.x

awskt 3 replaces the AWS SDK for Kotlin with a small hand-written client, the `aws/*` modules, so
that the same code runs on the JVM and as a Kotlin/Native Lambda. Most of the consumer-visible change
follows from that. SDK types no longer appear in awskt's public API, and awskt no longer brings the
SDK onto your classpath.

This guide covers moving a **JVM** consumer from 2.2.x to 3.1.1 or later. Moving to native is a
later, separate step. Get the application running on 3.x on the JVM first.

Work through the sections in order. The first three are dependency changes, and the rest are source
changes, roughly from most to least common.

## 1. Coordinates

| | Group | Artifact | Example |
|---|---|---|---|
| 2.x | `com.steamstreet` | `awskt-<module>` | `com.steamstreet:awskt-dynamokt:2.2.20` |
| 3.1.0 and later | `com.steamstreet.awskt` | `<module>` | `com.steamstreet.awskt:dynamokt:3.1.1` |

```kotlin
dependencies {
    implementation("com.steamstreet.awskt:dynamokt:3.1.1")
    implementation("com.steamstreet.awskt:lambda-api-gateway-ktor:3.1.1")
    implementation("com.steamstreet.awskt:aws-sdk-credentials:3.1.1") // see section 4
}
```

**Do not use 3.0.0.** It has the same code as 3.1.0 but was published under the old
`com.steamstreet:awskt-*` coordinates, which are no longer maintained. Nothing will follow it there,
so a build pinned to it can never take a fix.

Most modules are now Kotlin Multiplatform. Gradle picks the `-jvm` variant automatically. A Maven
or other non-Gradle consumer must name it, for example `dynamokt-jvm`.

## 2. Toolchain

- **Kotlin 2.3.x.** awskt 3.1 is built with Kotlin 2.3.21. Kotlin/Native and any consumer that reads
  klibs needs at least 2.3.0, because Ktor 3.4 and later ship klibs a 2.2 compiler cannot read. A
  JVM-only consumer on 2.2 may compile, but that has not been verified; use 2.3.
- **Ktor 3.5 or later.** `aws-core` exposes `ktor-client-core` and `lambda-api-gateway-ktor` exposes
  `ktor-server-core`, so Gradle raises an older Ktor to 3.5.2. Align your own Ktor dependencies with
  it rather than letting it happen silently. Below 3.5, native Curl responses hang on large bodies.
- **OkHttp 5 on the JVM, from 3.1.2.** `aws-core` uses Ktor's OkHttp engine, and so brings
  `okhttp-jvm` 5.x and `okio` onto the runtime classpath in place of `ktor-client-cio`. Through
  3.1.1 the engine was CIO, which opens a new connection for every call. Under load that runs out
  of ephemeral ports, and it fails with `java.net.BindException: Can't assign requested address`.
  If you worked around that by passing your own `httpClient`, you can remove the workaround. If
  something else in your build pins OkHttp 4, Gradle raises it to 5. OkHttp 5 keeps the `okhttp3`
  package and is intended as a drop-in upgrade, but test that other code against it.
- **Skip 3.1.2 and 3.1.3 on the JVM if you write through awskt without an outer retry.** Both
  releases can fail a non-idempotent write, such as `PutEvents` or `PutItem`, with
  `java.io.IOException: unexpected end of stream` when AWS has closed an idle pooled connection.
  Lambda is the most exposed, because pooled connections survive a freeze. A DynamoDB-stream or SQS
  batch retry recovers it, but a direct call or a long-lived server loses the write. From 3.1.4,
  awskt discards such a connection before writing to it. No write is ever resent after it may have
  reached AWS, so nothing needs to change in your code.
- Java 17 and `-Xcontext-parameters` are unchanged.

## 3. The AWS SDK is no longer re-exported

In 2.x several awskt modules declared the SDK as an `api` dependency. That meant consumers compiled
against SDK types without ever declaring them. 3.x removes every one of these:

| Module | 2.x re-exported |
|---|---|
| `dynamo`, `dynamokt`, `dynamokt-exposed` | `aws.sdk.kotlin:dynamodb` |
| `events`, `test` | `aws.sdk.kotlin:eventbridge` |
| `lambda-api-gateway-ktor` | `:cognito` and `ktor-server-auth-jwt` (see section 9) |

If your code still uses SDK clients directly, for example a `DynamoDbClient` for table
administration, declare them yourself:

```kotlin
implementation("aws.sdk.kotlin:dynamodb:1.5.123")
```

`lambda-dynamo-streams` still exposes `aws.sdk.kotlin:kinesis` on the JVM, because
`DynamoKtStreamHandler` takes a `KinesisClient` for dead-letter redrive.

## 4. Credentials: read this before deploying

**This change does not show up when you compile. It fails at runtime.**

In 2.x, awskt clients used the SDK's full default chain. In 3.x every awskt client (DynamoKt,
`EventBridgeSubmitter`, and each `aws-*` client) falls back to `defaultCredentialsProvider()` from
`aws-core`. By default that chain reads **environment variables only**: `AWS_ACCESS_KEY_ID`,
`AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` and `AWS_CREDENTIAL_EXPIRATION`. That is enough inside
Lambda. Everywhere else it fails:

- **ECS and Fargate task roles.** ECS exports only `AWS_CONTAINER_CREDENTIALS_RELATIVE_URI`, not the
  credentials themselves. The error message names this case.
- **A developer laptop** that uses `~/.aws` profiles, `AWS_PROFILE`, SSO or `credential_process`.
- **EC2 instance roles (IMDS) and web identity.**

The fix on the JVM is the `aws-sdk-credentials` module. It lends the SDK's own default chain to
awskt. Install it once at startup, before or after clients are built:

```kotlin
import com.steamstreet.awskt.core.AwsCredentialsDefaults
import com.steamstreet.awskt.credentials.sdk.sdkDefaultChainCredentialsProvider

fun main() {
    AwsCredentialsDefaults.provider = sdkDefaultChainCredentialsProvider()
    // ...
}
```

Every awskt client that is not given an explicit `credentialsProvider` then resolves through the
SDK's chain: system properties, environment, web identity, profiles and SSO, ECS container
credentials, then IMDS. The override is read on every resolve, not when a client is built, so a
client created in a companion object or a lazy picks it up too.

Other ways to supply credentials:

- Pass a provider to one client: `DynamoDb { credentialsProvider = sdkDefaultChainCredentialsProvider() }`.
- Wrap any SDK provider: `SdkCredentialsProvider(ProfileCredentialsProvider(profileName = "dev"))`.
- Use fixed credentials, which suits LocalStack and tests:
  `StaticCredentialsProvider(AwsCredentials(accessKeyId, secretAccessKey))`.
- Implement `AwsCredentialsProvider` yourself. It is a `fun interface` with
  `suspend fun resolve(): AwsCredentials`.

`aws-sdk-credentials` is JVM-only and pulls in `aws.sdk.kotlin:aws-config`, not a service client. A
native Lambda does not need it, because Lambda supplies credentials in the environment.

**Region works the same way.** awskt resolves the region from explicit config, `AWS_REGION`,
`AWS_DEFAULT_REGION` or the `aws.region` system property. It does **not** read `~/.aws/config`. Set
`AWS_REGION` locally if you relied on a profile's region.

## 5. DynamoKt

### `AttributeValue`, `AttributeValueUpdate` and `AttributeAction` are awskt types

These moved from `aws.sdk.kotlin.services.dynamodb.model` to `com.steamstreet.dynamokt`.
`AttributeValue` lives in the `dynamo` artifact, which `dynamokt` exposes.

```kotlin
// 2.x
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
// 3.x
import com.steamstreet.dynamokt.AttributeValue
```

The variant names (`S`, `N`, `B`, `Bool`, `Null`, `M`, `L`, `Ss`, `Ns`, `Bs`) and the
`asS()` / `asSOrNull()` accessors are the same, so most code only needs the import changed. The
differences:

- `AttributeValueUpdate` is a data class, not an SDK builder:
  `AttributeValueUpdate(value, AttributeAction.Put)` instead of `AttributeValueUpdate { ... }`.
- `AttributeAction` is an enum (`Put`, `Delete`, `Add`) with no `SdkUnknown`.
- `Ss`, `Ns` and `Bs` compare as sets, so order and duplicates no longer affect equality.
- `Null` defaults to `true`.
- `AttributeValueSerializer` is an `object`, so write `AttributeValueSerializer`, not
  `AttributeValueSerializer()`.

### Clients, builders and sessions

`DynamoKt` now builds and holds the awskt `DynamoDb` (`com.steamstreet.awskt.dynamodb`), not the
SDK's `DynamoDbClient`. Credentials are the awskt `AwsCredentialsProvider`.

| 2.x | 3.x |
|---|---|
| `builder: (CredentialsProvider?) -> DynamoDbClient` | `builder: (AwsCredentialsProvider?) -> DynamoDb` |
| `defaultCredentials: CredentialsProvider?` | `defaultCredentials: AwsCredentialsProvider?` |
| `session(CredentialsProvider?)` | `session(AwsCredentialsProvider?)` |
| `DynamoKt.defaultClientBuilder` returned `DynamoDbClient` (built with `runBlocking`) | returns `DynamoDb`, built lazily without blocking |
| `DynamoKtSession.dynamo: DynamoDbClient` | `DynamoKtSession.dynamo: DynamoDb` |
| `describeTable()` returned the SDK `TableDescription` | returns `com.steamstreet.awskt.dynamodb.TableDescription` |

```kotlin
// 2.x
DynamoKt("table", builder = {
    DynamoDbClient { region = "us-west-2"; endpointUrl = Url.parse(ep); credentialsProvider = it }
})

// 3.x: endpointUrl is a String
DynamoKt("table", builder = { credentials ->
    DynamoDb {
        region = "us-west-2"
        endpointUrl = ep
        if (credentials != null) credentialsProvider = credentials
    }
})
```

If you need the SDK client underneath during the move, `aws-dynamodb-sdk-adapter` provides
`SdkBackedDynamoDb(DynamoDbClient { ... })`. It is a `DynamoDb` backed by the SDK:
`builder = { SdkBackedDynamoDb(sdkClient) }`. This is also how to hand `test`'s
`DynamoLocalBuilder.client`, which is still an SDK client, to DynamoKt.

### Date helpers return kotlinx-datetime

`AttributeValue.localDate`, `.localTime` and `.localDateTime` now return `kotlinx.datetime.LocalDate`,
`LocalTime` and `LocalDateTime` instead of the `java.time` types. `kotlinx-datetime` is now an `api`
dependency of `dynamokt`. Use `.toJavaLocalDate()` and similar where you still need `java.time`. The
`instant` helpers were already `kotlin.time.Instant` and are unchanged.

### Enum serializers take the constants

```kotlin
// 2.x
EnumSerializer(Color::class, Color.RED)
NullableEnumSerializer(Color::class)
// 3.x
EnumSerializer(Color.entries, Color.RED)
NullableEnumSerializer(Color.entries)
```

The `enumAttribute<T>()` delegates are unchanged. In `dynamokt-exposed`, `EnumerationColumn` and
`EnumerationByNameColumn` likewise take `entries: List<T>` instead of `KClass<T>`.

### Other DynamoKt changes

- `ExpressionBuilder.apply(scan: ScanRequest.Builder)` is gone. Use `ExpressionBuilder.build()`,
  which returns the expression, names and values, or null.
- `Transaction` implements `AutoCloseable` instead of `java.io.Closeable`. `use { }` still works.

## 6. dynamokt-exposed

- `Database(client)` takes a `DynamoDb`.
- `DatabaseBuilder.client { }` configures a `DynamoDbConfig`.
- `Database.connect()` is no longer `suspend`.

## 7. Events

`EventBridgeSubmitter` takes the awskt `EventBridge` (`com.steamstreet.awskt.eventbridge`), and its
default no longer blocks on `fromEnvironment`:

```kotlin
// 2.x
EventBridgeSubmitter(bus, source, EventBridgeClient.fromEnvironment { region = "us-west-2" })
// 3.x
EventBridgeSubmitter(bus, source, EventBridge { region = "us-west-2" })
```

## 8. Lambda handlers

- **`logFailures` is an awskt enum.** On `DynamoKinesisStreamHandler` and `DynamoKtStreamHandler` it
  was `java.util.logging.Level?`. It is now `StreamFailureLogLevel?` (`INFO`, `WARNING`, `SEVERE`)
  in `com.steamstreet.aws.lambda`:
  ```kotlin
  logFailures = StreamFailureLogLevel.SEVERE   // was Level.SEVERE
  ```
- **`lambdaContext` is an awskt interface.** In `lambda-coroutines` it is now `LambdaContext`, with
  `requestId`, `functionName` and `remainingTimeInMillis`, instead of the AWS `Context`. Replace
  `lambdaContext.awsRequestId` with `lambdaContext.requestId`. On the JVM the raw context is
  `awsLambdaContext`, and a test that assigns it writes `lambdaContext = JvmLambdaContext(ctx)`.

## 9. JWT authentication moved to its own module

`JWTPrincipal`, `ApiGatewayJWT` and `ApiGatewayJWTConfig` moved out of `lambda-api-gateway-ktor` into
`lambda-api-gateway-ktor-jwt`. The package (`com.steamstreet.aws.lambda.apigateway.ktor`) is
unchanged, so no imports change. Add the dependency:

```kotlin
implementation("com.steamstreet.awskt:lambda-api-gateway-ktor-jwt:3.1.1")
```

`lambda-api-gateway-ktor` also stopped re-exporting `:cognito` and `ktor-server-auth-jwt`. Declare
them if you use them for anything else. The JWT module is JVM-only.

## 10. The `test` module's EventBridge mock

`EventBridgeMock` implements the awskt `EventBridge` plus a small `EventBridgeAdmin` interface. It no
longer implements the SDK client by delegating to a MockK mock.

| 2.x | 3.x |
|---|---|
| `EventBridgeMock(accountId, region, mockk = ...)` | `EventBridgeMock(accountId, region)` |
| `createEventBus { name = "b" }` | `createEventBus("b")` |
| `putRule { name = "r"; eventPattern = p; eventBusName = "b" }` | `putRule(name = "r", eventPattern = p, eventBusName = "b")` |
| `listRules { ... }` | `listRules(eventBusName)`, which returns rule names |
| `putTargets(PutTargetsRequest)` | removed; use `putTarget(...)` |
| `eventsOfType()` returned `List<PutEventsRequestEntry>` | returns `List<PutEventsEntry>` |
| failures threw `AwsServiceException` | `IllegalStateException` (duplicate rule), `IllegalArgumentException` (unknown bus or rule) |

`putRule(bus, pattern, function)`, `putTarget(...)`, `clearSaved()`, `eventsOfType(schema)` and
`forwardEvents` keep their signatures. `dynamoPipe(...)` takes an awskt `EventBridge`.

## Replacing direct SDK calls

Some awskt modules cover operations a consumer used to call through the SDK directly:

- **Lambda `ListFunctions`:** `aws-lambda` has `listFunctions(request)`,
  `listFunctionsPaginated(request)` and `Flow<ListFunctionsResponse>.functions()`, with the SDK's
  names and fields. Service enumerations such as `runtime` and `state` are `String`s.
  ```kotlin
  // SDK
  lambda.listFunctionsPaginated { }.functions().firstOrNull { it.functionName == name }
  // awskt
  lambda.listFunctionsPaginated().functions().firstOrNull { it.functionName == name }
  ```
- **JWT verification and signing:** `com.auth0:java-jwt` and `jwks-rsa` are JVM-only. The `jwt`
  module covers the same Sign in with Apple and Google cases on both platforms:
  ```kotlin
  // auth0
  JWT.require(Algorithm.RSA256(jwkProvider.get(kid).publicKey as RSAPublicKey, null))
      .withIssuer(issuer).withAudience(clientId).build().verify(token)
  // awskt (build the verifier once and share it)
  JwtVerifier(JwksKeySource(jwksUrl), issuers = setOf(issuer), audiences = setOf(clientId)).verify(token)
  ```
  The awskt verifier refuses to run without an audience, so a migration that had omitted
  `withAudience` will now reject tokens meant for other clients, which is the intended result.
- **`Secret_` environment values** now resolve on native too, through `aws-secretsmanager`, with
  the same rules as the JVM. `AppConfig.` values are still resolved on the JVM only.

## Checklist

1. Switch coordinates to `com.steamstreet.awskt:<module>:3.1.1` and skip 3.0.0.
2. Move to Kotlin 2.3.x and Ktor 3.5 or later.
3. Declare any SDK artifacts you use directly.
4. Add `aws-sdk-credentials` and set `AwsCredentialsDefaults.provider` at startup, unless you run
   only in Lambda. Set `AWS_REGION` where you relied on a profile's region.
5. Fix imports and types for `AttributeValue`, `DynamoKt` builders, dates and enum serializers.
6. Update `EventBridgeSubmitter`, `logFailures`, `lambdaContext` and any JWT dependency.
7. Update tests that use `EventBridgeMock`.
8. Test outside Lambda, on a laptop and on ECS if you deploy there. Credential and region problems
   only appear at runtime.
