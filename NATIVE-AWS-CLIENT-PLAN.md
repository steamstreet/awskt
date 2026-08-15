# Native AWS Client for awskt — Implementation Plan

**Status**: API break approved by Jon (2026-08-09). Ships as a major version.
**Version**: **3.0.x**. This is a breaking major release, not a minor on the 2.x line.
**Target branch**: **`3.0.x`**, cut from `2.2.x` @ `20ece7b` (NOT from `claude/native-aws-kotlin-sdk-491425`, which is 212 commits behind and is an *ancestor* of 2.2.x). The branch name is load-bearing, not cosmetic: the root build applies `id("nebula.release") version "19.0.10"` (`build.gradle.kts:3`), and this repo's release lines are branch-derived — `2.0`, `2.1.x`, `2.2.x`, `2.3.x`. A branch named anything else does not produce 3.0.x artifacts. **Verified 2026-08-09** on the freshly cut branch: `./gradlew properties` reports `group: com.steamstreet`, `name: aws-kt`, `version: 3.0.0-dev.0.uncommitted+20ece7b`. The build also configures cleanly at `20ece7b` before any change, establishing the green baseline M0 builds on.
**Author**: planning pass, revised against three adversarial reviews, then re-scoped to admit S3 and revised against two more
**Effort**: ~78.5 developer-days planned, ~94 with contingency (≈19 weeks at 1 FTE). With a second developer taking M4 + M3.5, the critical path is **63 planned / ~76 with contingency (≈15 weeks)**.

> **Revision note (S3 re-scope).** The first draft excluded S3 on the evidence that the repo has zero S3 production call sites. That evidence is correct and the inference from it was wrong: it answered "is there S3 code to *port*?" when the actual requirement is "can a Kotlin/Native Lambda *call* S3?". v1 scope is now **DynamoDB + EventBridge + S3 (GetObject, PutObject, HeadObject, DeleteObject, presigned GET/PUT URLs)**. §2 states the boundary as a mechanical test so this cannot be re-cut from the same stale evidence.

---

## Verdict

**Build it — but at roughly the scope described here, in the order described here, and only after a written API-break approval.**

Three things are settled by evidence, not opinion:

1. **Waiting for AWS is not a plan.** `aws.sdk.kotlin:dynamodb` at 1.8.26 (published 2026-08-07) declares only `common` metadata + `jvm` variants; `aws.sdk.kotlin:dynamodb-linuxarm64` is a 404 on Maven Central. The 2.3.x branch was parked on `com.sonatype.central.testing.amazon`, a Sonatype publishing-pipeline test namespace containing junk versions (9.9.9, 12.12.12). That dependency must never appear in a published build.

2. **The AWS surface this codebase *ports* is small; the surface it *needs* is slightly larger.** 12 DynamoDB operations and 1 EventBridge operation cover everything running in a Lambda today. SQS is two mock-only operations. Cognito imports no AWS SDK at all.

   **S3 is different in kind and must not be reasoned about the same way.** There is nothing to port — the only S3 code in the repo is a 10-line unused stub, `public class S3Local(private val mock: S3Client = mockk<S3Client>(relaxed = true)) : S3Client by mock, MockService` at `test/src/jvmMain/kotlin/com/steamstreet/aws/test/S3Mock.kt:6-11`, with **zero usages anywhere in the repo** including `test`'s own test sources (verified by repo-wide grep). But the downstream native Lambdas require `GetObject`, `PutObject` and presigned URL generation as **new capability**. Zero existing call sites is evidence that no *migration* is needed; it is not evidence that no *client* is needed. Nothing else in this document's out-of-scope list has that property — SQS, Lambda and DynamoDB Streams really are mock-only with no forward requirement.

3. **`dynamokt` already computes everything it sends.** `Query.buildQuery()` (`dynamokt/src/main/kotlin/com/steamstreet/dynamokt/Query.kt:184-222`) sets 11 fields it computes itself; `MutableItem` builds the update expression; `ExpressionBuilder` builds filters. A mirrored SDK builder layer would reproduce a property bag with no content.

**Smallest version that delivers real value**: M0.5 + M1 — a 3.5-day vertical spike that hand-signs one `GetItem` (and one S3 `GetObject`) from a `linuxArm64` binary running on a real `provided.al2023` Lambda, followed by the `aws-signing` module passing the AWS-published SigV4 corpus in **both header and query (presign) mode**. That is 11 days, it is independently publishable as `awskt-aws-signing`, and if the spike fails the project should be abandoned before any consumer code is touched. Query signing is what makes that standalone artifact genuinely valuable: presigned-URL generation is the single most common reason a JVM or Native project reaches for a bare SigV4 signer, and it needs no HTTP client at all.

**If the calendar is fixed and 19 weeks is not available**: cut M7 (native targets + Lambda runtime + packaging, 10.5 days) and ship the hand-written client JVM-only. You still get an AWS-SDK-free `dynamokt`, a much smaller dependency graph, and a client that is *ready* to go native.

**But this cut is materially worse than it was before S3 entered scope, and the plan should not be read as endorsing it.** An AWS-SDK-free `dynamokt` has independent JVM value. A JVM-only S3 get/put client has an obvious incumbent, so S3's value is tightly coupled to M7 actually shipping. The landing point is now M6 *or* M3.5b, whichever lands last, and only if native is genuinely being abandoned. Do not let the S3 addition be used as an argument for cutting M7.

### What is a bad idea, stated plainly

- **Do not build a Smithy-AST code generator.** It costs ~2 weeks up front plus permanent maintenance, and it exists to generate 104 model types *and* 104 hand-rolled serializers, because SDK request types have private constructors and camelCase properties that kotlinx.serialization cannot reach. Plain `@Serializable data class` DTOs with `@SerialName("TableName")` produce the identical awsJson body with zero serialization code.
- **Do not trust "100 LocalStack tests green" as proof the signer works.** LocalStack accepts `DummyKey`/`DummySecret` and ships with IAM enforcement disabled. Nothing in a LocalStack run ever verifies a signature. This is why M0.5 and the M3 live-AWS smoke test exist.
- **Do not treat M5 as a "three sed rules" port.** Nine files in `dynamokt` need real rewrites, `test/src/jvmMain/.../DynamoStreamRunner.kt` breaks, and `dates.kt` / `delegates.kt` carry unlisted public API breaks. See §7.
- **Do not reproduce `TableDescription`'s full 36-type closure.** `DynamoKtSession.describeTable()` (`dynamokt/src/main/kotlin/com/steamstreet/dynamokt/DynamoKtSession.kt:25-29`) has zero callers in the repo.
- **Do not build a restXml serializer.** `GetObject`, `PutObject`, `HeadObject` and `DeleteObject` have **zero body-bound members** in the S3 Smithy model — every input and output member carries an `smithy.api#http*` binding, so there is literally nothing for a serializer to serialize. The only XML in scope is the error document, which is three tags. If you find yourself writing an XML *encoder*, or reaching for a pull parser, you have wandered into bucket, list, multipart or copy operations and should stop. §2 states the boundary as a mechanical test.

---

## 1. Module Overview

**Module Name**: `aws/aws-signing`, `aws/aws-core`, `aws/aws-dynamodb`, `aws/aws-eventbridge`, `aws/aws-s3`, `aws/aws-secretsmanager`, `aws/aws-kms`, `aws/aws-sqs`, `aws/aws-sns`, `aws/aws-scheduler`, `aws/aws-bedrock-runtime`

**Dependencies**:
- `aws-signing` → KotlinCrypto only. **No Ktor. No awskt modules.** That constraint is what lets AWS's own fixture corpus drive the signer directly. It carries **both** header and query-string (presign) signing — query signing is pure string/byte work with no S3 knowledge, no HTTP client and no I/O.
- `aws-core` → `aws-signing`, Ktor client core, kotlinx-serialization, kotlinx-coroutines. **No `:standards`, `:env`, or `:logging`** — see Decision 6.
- `aws-dynamodb` → `:dynamo` (for `AttributeValue`), `aws-core`.
- `aws-eventbridge` → `aws-core`.
- `aws-s3` → `aws-core`. **NOT `:dynamo`, NOT `:standards`/`:env`/`:logging`** (Decision 6 applies unchanged). Declares `jvm, linuxX64, linuxArm64, macosArm64` from birth, so M7 has no target-addition task for it. `aws-s3/src/jvmTest` additionally carries `aws.sdk.kotlin:s3` as a **test-only** dependency for the presign differential — see §11 criterion 9.
- `aws-secretsmanager` → `aws-core`. **NOT `:env`**, even though `env`'s `SecretsProvider` is the obvious consumer — the dependency has to run the other way (`env` may depend on this) or a caller who wants to read one secret acquires a logging framework and an AppConfig client. Decision 6 again.
- `aws-kms` → `aws-core`. Same constraint, same reason.
- `aws-sqs` → `aws-core`. Different artifact from `:lambda:lambda-sqs`, which handles SQS events
  *arriving* at a Lambda and contains no client; the two are complementary and neither depends on
  the other.
- `aws-sns` → `aws-core`, and **notably not `kotlinx-serialization-json`** — SNS is the query
  protocol, so there is no JSON in either direction and `Wire.kt` carries the codec instead. A
  `@Serializable` appearing in that module means somebody has misread the protocol. Same
  relationship to `:lambda:lambda-sns` as above.
- `aws-bedrock-runtime` → `aws-core`. The **data plane** only; the `bedrock` control plane is a
  separate service with a separate endpoint and is not covered.
- `aws-scheduler` → `aws-core`. **Unrelated to `aws-eventbridge`** despite the shared brand:
  EventBridge Scheduler is a separate service with its own endpoint (`scheduler`), its own protocol
  (restJson1, where EventBridge is AWS-JSON 1.1) and no overlapping operations.

**Goal**: Replace `aws.sdk.kotlin` in `dynamo`, `dynamokt`, `dynamokt-exposed` and `events` with a hand-written, Kotlin-Multiplatform-native client that runs on `linuxArm64` inside an AWS Lambda custom runtime, while keeping all 100 existing DynamoDB integration tests green and preserving pagination-token wire compatibility — **and** to provide a native S3 client (GetObject, PutObject, HeadObject, DeleteObject, presigned GET/PUT URLs) which has no `aws.sdk.kotlin` counterpart in this repo today and is therefore purely **additive** to the public API.

---

## 2. Scope

### In scope

| Service | Operations | Protocol |
|---|---|---|
| DynamoDB | GetItem, PutItem, UpdateItem, DeleteItem, Query, Scan, BatchGetItem, BatchWriteItem, TransactWriteItems, TransactGetItems, DescribeTable, **CreateTable** | awsJson1_0, target prefix `DynamoDB_20120810` |
| EventBridge | PutEvents | awsJson1_1, target prefix `AWSEvents` |
| **S3** | **GetObject, PutObject, HeadObject, DeleteObject, presignGetObject, presignPutObject** | restXml *service*, but these are raw-body operations with header/URI bindings only — **no XML serialization**. SigV4 with signing name `s3`, `doubleUriEncode=false`, `normalizeUriPath=false`, `x-amz-content-sha256` required, virtual-host addressing |

**S3 is in scope because of a forward requirement, not an existing call site — and that is the only row in this table for which that is true.** Every other operation here is a port. S3 is new capability for downstream Kotlin/Native Lambdas, which need object get/put and presigned URLs. The justification for it being *cheap* is a member-binding census computed directly against the S3 Smithy model (`scratchpad/x-s3.json`), not against prose:

| Shape | Members | Binding breakdown | Body-bound |
|---|---:|---|---:|
| `GetObjectRequest` | 21 | 2 `@httpLabel`, 11 `@httpHeader`, 8 `@httpQuery` | **0** |
| `GetObjectOutput` | 43 | 1 `@httpPayload` (`StreamingBlob`), 41 `@httpHeader`, 1 `@httpPrefixHeaders` | **0** |
| `PutObjectRequest` | 46 | 2 `@httpLabel`, 42 `@httpHeader`, 1 `@httpPrefixHeaders`, 1 `@httpPayload` (`StreamingBlob`) | **0** |
| `PutObjectOutput` | 22 | 22 `@httpHeader` | **0** |
| `HeadObjectRequest` / `HeadObjectOutput` | 21 / 43 | label+header+query / header+prefixHeaders | **0** / **0** |
| `DeleteObjectRequest` / `DeleteObjectOutput` | 10 / 3 | label+header+query / header | **0** / **0** |

There is nothing for a serializer to serialize. The service trait is `aws.protocols#restXml: {noErrorWrapping: true}`, so the error document root is `<Error>` directly — AWS's own reader for it (`smithy-kotlin/runtime/protocol/aws-xml-protocols/common/src/.../RestXmlErrorDeserializer.kt`) reads exactly `Code`, `Message`/`message` and `RequestId` in ~50 lines. **This supersedes the draft's claim that S3 "would drag in a restXml parser": that claim is false for these operations.** The draft's real blockers were the S3 signer variant and endpoint addressing, both of which are small and both of which this plan now absorbs explicitly.

**CreateTable is in scope and is not optional.** It was missing from the draft plan. Verified: `dynamokt/src/test/kotlin/com/steamstreet/dynamokt/BasicTests.kt:227-228` does `val dynamoClient = DynamoKt.defaultClientBuilder(null); dynamoClient.createTable { ... }` and `dynamokt-exposed/src/test/kotlin/com/steamstreet/dynamokt/exposed/ExposedTestBase.kt:49` does `database.client.createTable { ... }` — both reach control-plane operations *through the production client seam being replaced*. There are 8 inline `createTable {}` bodies plus 19 helper invocations across the test sources, and `dynamokt-exposed/src/test/.../QueryTest.kt:57,84,121,176` additionally build GSIs. Without CreateTable the headline success criterion cannot compile.

### Out of scope — permanently

- **S3 bucket, list, multipart and copy operations, and the restXml SERIALIZATION engine.** The boundary is a **two-clause mechanical test, not a judgement call**. An S3 operation is in scope only if:
  1. its input and output shapes have **zero body-bound members**, AND
  2. any `@httpPayload` target is a **blob**, not a structure.

  GetObject / PutObject / HeadObject / DeleteObject all qualify (0 body-bound each; payload target is `com.amazonaws.s3#StreamingBlob`, a blob). `ListObjectsV2` does not — `ListObjectsV2Output` has 13 members of which **12 are body-bound XML** (`IsTruncated`, `Contents`, `Name`, `Prefix`, `Delimiter`, `MaxKeys`, `CommonPrefixes`, `EncodingType`, `KeyCount`, `ContinuationToken`, `NextContinuationToken`, `StartAfter`). `CompleteMultipartUpload` does not — it has 0 body-bound members, but its `@httpPayload` targets the **structure** `com.amazonaws.s3#CompletedMultipartUpload`, which needs an XML encoder; **clause (2) is what catches it**, and a single-clause rule would wrongly admit it. `CopyObject` does not — its `@httpPayload` targets the structure `CopyObjectResult`.

  Re-check the model, not memory. Also out under the same boundary: access points, Multi-Region Access Points, S3 Express One Zone, Transfer Acceleration, Outposts, and ARN-as-bucket.
- **The 128,956-byte S3 endpoint ruleset and everything it configures.** Its `clientContextParams` are `ForcePathStyle`, `UseArnRegion`, `DisableMultiRegionAccessPoints`, `Accelerate`, `DisableS3ExpressSessionAuth` — every one already excluded by the "hardcode the `aws` partition" bullet below. A plain bucket + key resolves in one line.
- **`httpChecksum`, in either direction, for v1.** Verified against the model: exactly **25** S3 operations carry `aws.protocols#httpChecksum.requestChecksumRequired`, and **neither GetObject nor PutObject is among them**. PutObject's trait is `{requestAlgorithmMember: ChecksumAlgorithm}` (algorithm *selectable*, never required); GetObject's is `{requestValidationModeMember: ChecksumMode, responseAlgorithms: [...]}` (validation opt-in via a header we do not send). S3 attaches a server-side CRC64NVME to every object regardless, and AWS explicitly tells REST callers not to send `x-amz-sdk-checksum-algorithm`. Reversible in ~0.5 day from vendored Apache-2.0 smithy-kotlin source (`runtime-core` `Crc32.kt` / `Crc32c.kt`, tests included) if a bucket policy ever demands it — note the header value is base64 of the big-endian digest, not hex. **Superseded by a real completeness check** — see M3.5b and Risk 30; "no checksum" must not mean "no integrity check at all".
- **The 200-OK-with-error-body quirk.** Real, but it applies only to `CopyObject`, `CompleteMultipartUpload` and `UploadPartCopy` — all three out of scope by the boundary test. GetObject and PutObject declare `@http(code: 200)` with modeled 4xx errors and never return an error under 200. Do not build a defensive body sniff for a case that cannot occur.
- **`SdkBackedS3`, and any S3 SDK adapter.** No S3 code exists to migrate and no test suite needs protecting — see Decision 15.
- **SQS client.** Two operations, mock-only (`test/src/jvmMain/.../SqsMock.kt:46,60`). The `com.steamstreet.aws.sqs` package in production code is a first-party Lambda-event payload package, not the SDK.
- **Lambda service client.** Mock-only; `LambdaMock.kt` also imports `com.amazonaws.services.lambda.runtime.Context` and `java.lang.reflect` and is irreducibly JVM.
- **DynamoDB Streams client.** Four operations, `test`-module only. If ever added, remember it signs as `dynamodb` against host `streams.dynamodb.*`.
- **Cognito.** The `cognito` module has zero `aws.sdk.kotlin` imports; it uses `com.auth0.jwt` via ktor-server-auth-jwt. It needs a JWT verifier, not an AWS client.
- **Making the `test` module multiplatform.** Its four mocks use `: XClient by mockk<XClient>(relaxed = true)`, which requires a full generated interface and is JVM-only. None of it runs in a Lambda.
- **Profile files, SSO, `credential_process`, `source_profile` chaining.** ~2000 lines in `aws-config`, serving developer laptops, which is exactly where the JVM build already works.
- **IMDSv2.** This codebase deploys to Lambda and containers, never bare EC2. Honor `AWS_EC2_METADATA_DISABLED` as a documented no-op.
- **Endpoint rulesets and partition tables.** Hardcode the `aws` partition and `amazonaws.com`. FIPS, dualstack, China, GovCloud are out.
- **SigV4a, chunked / `aws-chunked` signing, and event-stream signing.** Presigned-URL (query-string) signing and `UNSIGNED-PAYLOAD` were on this list in the draft and have both **moved into scope** — see M1. Keeping chunked signing out costs nothing precisely *because* `UNSIGNED-PAYLOAD` is in: it provides the same streaming capability with one string literal instead of an 8-KiB-minimum chunk framing protocol with chained per-chunk signatures, a completion chunk and a trailer chunk. `STREAMING-AWS4-HMAC-SHA256-PAYLOAD` and its trailer variants are never emitted. **Removing chunked signing from this list would be the single most expensive unforced error available here.**
- **CRC32 response validation.** The Kotlin SDK does not do it either — the DynamoDB Smithy model has zero `httpChecksum` traits, and neither S3 target operation requires one (see the `httpChecksum` bullet above).
- **Server-side encryption with customer-provided keys (SSE-C), object lock, tagging, ACLs, and the ~30 remaining GetObject/PutObject headers.** Bind roughly 10 headers per operation, not all 42. Adding a header later is one nullable field; it needs no design.
- **Range-GET as a resumable-download abstraction.** Expose the single `Range` request header and the `Content-Range` response field. Do not build chunked-download orchestration on top of it — that is the same workstream as streaming and multipart.
- **A Smithy code generator, and any committed generated source.**
- **JS and Wasm targets for `dynamokt`.** Its commonMain uses `runBlocking` (`DynamoKt.kt:65`, `Item.kt`, `Transaction.kt`, `delegates.kt`), which resolves only across the jvm+native `concurrent` source set. Record as an ADR.
- **iOS (and every other Apple mobile) target for the `aws-*` modules. Decided by Jon, 2026-08-10: these are server-side only.** Written down because the repo makes the opposite look like an oversight — `logging`, `standards` and `serialization` all declare `iosArm64()`/`iosSimulatorArm64()`, so an `aws-*` module without them reads as an omission rather than a decision. It is a decision. `aws-signing` would in fact be nearly free to add (KotlinCrypto only, no Ktor) and `ktor-client-darwin` is already in the version catalog for `aws-core`, so the cost is not the argument — the argument is that shipping AWS credentials to a mobile device is the wrong shape, and a target with no consumer is still public API to maintain. This is the same reasoning as Decision 11's refusal to add `js(IR)` to `aws-eventbridge`.

  **`macosArm64` is NOT an exception to this and must not be "cleaned up" as an unused Apple target.** It exists so the development machine can *execute* native tests: `linuxArm64` is Tier 2 and never runs, `linuxX64` needs an ubuntu host, and without `macosArm64` there is no target on which native code is run rather than merely linked. It is test infrastructure that happens to be an Apple platform.

### Deferred to v2 — a decision with a ceiling, not an exclusion

- **Streaming request and response bodies (`ByteReadChannel` / `Flow<ByteArray>` / `ByteStream` / `SdkSource`).** The draft excluded these on the grep result "repo-wide grep returns zero hits; every binary payload in play is a plain `ByteArray`". **The grep is still accurate and the inference no longer follows.** S3's `GetObjectOutput.Body` and `PutObjectRequest.Body` both target `com.amazonaws.s3#StreamingBlob` carrying `smithy.api#streaming` — the first genuinely streaming shapes in scope.

  **Decision**: v1 materializes both directions as `ByteArray`, with an enforced and documented ceiling (`S3Config.maxBufferedUploadBytes` / `maxBufferedDownloadBytes`, default 64 MB each, throwing `S3PayloadTooLargeException` rather than OOM-ing — see M3.5b and Risk 29). `Range` on GetObject is exposed from day one and is the bounded-memory escape hatch. Streaming arrives later as `getObjectStream` / `putObjectStream`, **purely additive, never a break** — which is why the v1 request types must not bake a non-nullable `ByteArray` into a sealed shape that cannot grow. Crossing S3's 5 GiB single-PUT limit requires multipart upload, whose `CompleteMultipartUpload` has a structure payload and would break the no-serializer premise on which the whole S3 cost estimate rests — that is the hard boundary of this design. See Q10.

  Note KTOR-9737 while designing the v2 shape: a length-less streaming upload does not work, so a future streaming `putObject` must use `OutgoingContent.ReadChannelContent` with `contentLength` overridden. S3 requires an explicit `Content-Length` anyway.

### Out of scope — for v1, revisit later

- ~~Kinesis (2 ops), Secrets Manager (1 op), AppConfigData (2 ops).~~ **Secrets Manager and KMS were moved back into scope on 2026-08-14 (M8). Kinesis and AppConfigData stay out.** The original entry is kept below rather than deleted, because the reasoning it was overturned by is the point.

  The original text: *"All live in JVM-only modules (`lambda-dynamo-streams`, `env/src/jvmMain`) and none blocks a native dynamokt-based Lambda. The draft's stated reason for AppConfigData — 'the only restJson1 consumer, would force a second codec into `aws-core`' — goes stale once `aws-core` carries a protocol seam and an `AwsErrorParser` strategy for S3 (M2). The honest reason is simply that a native Lambda does not need it. They stay out; the rationale is corrected so it is not later discovered to be false and read as licence to pull them in."*

  **Why it was wrong for Secrets Manager, and why that is the same mistake §2 already caught once.** The test applied was "is there code to *port*?" — Secrets Manager has exactly one call site, in a JVM-only module, so porting it buys nothing. That is the identical inference the S3 re-scope note at the top of this document was written to prevent, and it failed the same way: the requirement is not "port the existing call site", it is **"can a Kotlin/Native Lambda call the service?"** A native Lambda that cannot read a secret cannot hold a database password, an API key or a signing secret, which is most of what a Lambda needs configuration for. One JVM call site was never evidence about that. The paragraph above closes with a warning against reading its own rationale as licence to pull these in later; that warning was aimed at scope creep, and it does not apply to a requirement the test never measured.

  **KMS was not on the out-of-scope list at all**, because nothing in this repo calls it — which under the old test made it invisible rather than excluded. It is in scope for the same reason: envelope encryption and `Decrypt` are the other half of what a Lambda does with secret material, and Secrets Manager's own `DecryptionFailure` is a KMS failure surfaced through it.

  **The cost was low precisely because the earlier milestones did their job.** Both services speak AWS-JSON 1.1 — the dialect M6 already proved with EventBridge — so M8 needed no new protocol, no new error parser and no transport change. The one addition to `aws-core` is `Base64BlobSerializer` (`Blobs.kt`), because AWS-JSON blobs are base64 strings and both services carry them.

  **Kinesis and AppConfigData remain out**, and now on the corrected test rather than the stale one: a native Lambda is *invoked with* Kinesis records rather than calling Kinesis, and AppConfigData is a polling configuration client whose one consumer (`env/src/jvmMain/AppConfig.kt`) is JVM-only by construction. Neither is something a native Lambda is blocked on. Revisit if that changes.

  **Update (2026-08-14, M9): SQS, SNS and EventBridge Scheduler were added too.** Same corrected
  test, applied to three services a native Lambda plainly needs to *call* rather than merely be
  invoked by: queueing work, publishing notifications, and scheduling future work. Note the
  AppConfigData rationale above is now doubly stale — M9 shipped `restJson1` support in `aws-core`,
  so the "second codec" objection is not merely obsolete, it is measurably four lines. AppConfigData
  stays out on the honest reason only: a polling configuration client is not something a native
  Lambda is blocked on.
- **`S3Local` / `S3Mock.kt`.** Recommend deletion, pending Q7. Zero usages repo-wide; a `mockk(relaxed = true)` over an S3 client returns empty objects, which is a worse test double than none. This is a **deletion from a published artifact**, not a no-op — see Q1 row (k).
- Container (ECS/EKS/CodeBuild) credentials. Real but JVM-only demand.
- Native targets for `dynamokt-exposed`. It is forced into scope for *compilation* but no Lambda handler uses it.
- Migrating `lambda/*` off `com.amazonaws.services.lambda.runtime` (aws-lambda-java-core). This is the actual gate for native Lambda *handlers* and is a larger, separate workstream than replacing the SDK.
- **Concurrent invocations delivered into one execution environment (AWS Lambda Managed Instances).** `lambda-native`'s `LambdaRuntime.run()` is a serial `while (true)` poll-handle-respond loop and `lambdaContext` is a process-global `lateinit var`; both are correct under the one-invocation-at-a-time delivery the classic on-demand execution environment guarantees, and neither is safe under concurrent delivery. **This is a documented non-capability, not a latent bug to be quietly patched** — supporting it means moving the context onto the coroutine context, restructuring the loop to dispatch rather than run inline, and bounding in-flight work. See **Risk 34**, and the KDoc on `LambdaRuntime`. Note this is a property of the *runtime*, not of the clients: `AwsServiceClient` and `S3` are safe to share across concurrent coroutines within one invocation.

---

## 3. Key Design Decisions

### 1. Minimal owned API, not a mirror of the SDK

Plain `@Serializable data class` request/response DTOs with `@SerialName` carrying PascalCase wire names, `encodeDefaults = false`, `explicitNulls = false`. Free suspend functions. No builders, no `Config.Builder`, no smithy runtime.

*Rationale*: The mirror's serialization layer is not free — SDK request types have private constructors and camelCase properties, so every mirrored type needs a hand-written or generated `KSerializer`. Thin DTOs need zero. And the mirror's promise of a mechanical port is false where it matters: `DynamoKt.kt:17,18,43` puts `CredentialsProvider` in three public signatures, so the API break is taken under either design.

*Rejected*: Mirror-the-SDK with a Smithy-AST code generator.

### 2. `AttributeValue` lives in `:dynamo`, keeps the SDK's exact shape

Declared in `dynamo/src/commonMain/kotlin/com/steamstreet/dynamokt/AttributeValue.kt`, `package com.steamstreet.dynamokt`, with variants `S/N/B/Bool/Ss/Ns/Bs/L/M/Null/SdkUnknown` and both `asX()`/`asXOrNull()` accessor families identical to the SDK's.

*Rationale*: `dynamo` and `dynamokt` already share the package and `dynamokt` already does `api(project(":dynamo"))`. Keeping the SDK's names costs nothing and protects ~200 call sites (asS 37, asM 22, asN 20, asL 16, asSs 10, …). `dynamo` keeps zero Ktor and zero aws-core dependency, so stream-event parsing stays transport-free.

**`B` and `Bs` must NOT be data classes.** Hand-write `equals`/`hashCode` using `contentEquals`/`contentHashCode` — `attributes.kt:53` `findDifferences` and the private `diff()` at `attributes.kt:70` rely on structural equality, and a data class over `ByteArray` silently breaks item diffing for binary attributes.

**AMENDED 2026-08-10 (Jon's decision): `Ss`, `Ns` and `Bs` compare as SETS, and this section's "keeps the SDK's exact shape" is now false for equality specifically.** Say so plainly rather than leaving the divergence to be discovered.

The `ByteArray` argument above turns out to have been the *narrow* case of a wider one, and LocalStack found the wider one on its first run: DynamoDB's set types are unordered, and the service returned `NS: ["1","-2.5"]` as `["-2.5","1"]`. With the SDK's order-sensitive `List` equality, **a set-valued attribute is not equal to itself across a write and a read** — which breaks `findDifferences` for exactly the same reason `ByteArray` did, just more often and more quietly. Whatever this section says about protecting item diffing, it has to hold for sets too or it does not hold.

Mechanics, all in `AttributeValue.kt`:
- `Ss` and `Ns` stay `data class` (so `copy()` and destructuring survive) with explicit `equals`/`hashCode` over `value.toSet()`; declaring them suppresses only the generated pair.
- `Bs` maps elements to `List<Byte>` before the set comparison, getting content semantics and order independence together.
- `Ns` compares **as strings, not as numbers**. `N` carries an exact decimal string precisely so nothing has to decide what `"1"` and `"1.0"` mean, and `equals` does not get to decide either.
- **Duplicates collapse** (`Ss(["a","a"]) == Ss(["a"])`). That agrees with the service, which rejects duplicate members outright.
- **Only equality ignores order.** The constructor still takes a `List`, so the wire form and `value` both preserve it — asserted by a test, because a "tidy-up" that sorted on construction would change the bytes we send.

Six tests pin this in `commonTest`, including that a reordered set hashes identically and is therefore usable as a map key, and that different contents are still unequal — set semantics must not quietly become "everything is equal".

### 3. `DynamoDb` is an interface, and `SdkBackedDynamoDb` is BUILT, not hypothetical

```kotlin
public interface DynamoDb : AutoCloseable { /* 12 suspend methods */ }
public fun DynamoDb(config: DynamoDbConfig.() -> Unit): DynamoDb   // → DefaultDynamoDb
```

plus, in a JVM-only module `aws/aws-dynamodb-sdk-adapter`:

```kotlin
public class SdkBackedDynamoDb(private val delegate: aws.sdk.kotlin.services.dynamodb.DynamoDbClient) : DynamoDb
```

*Rationale*: This is the single highest-value change to the draft plan. It splits the one irreversible 13-day merge into two landable halves — **M5a swaps the type** (validated by 100 green tests against known-good SDK behaviour) and **M5b flips the implementation** (revertible in one line). It also gives JVM consumers a supported SDK-backed path during and after the transition, and it is the escape hatch if AWS ever ships native klibs. ~250 lines.

*Rejected*: A final class, and treating the adapter as a hypothetical "door we keep open".

### 4. Client construction is not suspend

`DynamoDb { region = …; endpoint = … }` resolves region, endpoint and credentials lazily at first call.

*Rationale*: Deletes five `runBlocking` wrappers (`DynamoKt.kt:65`, `AppConfig.kt:23`, `SecretsManagerSecretsProvider.kt:12`, `EventBridgeSubmitter.kt:15`, `Database.kt:54`). There is no `fromEnvironment()` to await because there is no credential-chain discovery needing I/O at construction time.

### 5. Retry is a hand-rolled loop above the Ktor client

*Rationale*: **Structurally forced, not a preference.** Ktor's `shouldRetry` is `HttpRetryShouldRetryContext.(HttpRequest, HttpResponse) -> Boolean` — non-suspend, so it cannot read the response body where awsJson puts the error code, and DynamoDB throttling arrives as **HTTP 400**, not 429. `modifyRequest` is also non-suspend, so credential refresh and per-attempt re-signing are impossible. Separately, `x-amz-retry-after` is **milliseconds** whereas Ktor's `respectRetryAfterHeader` assumes RFC seconds — a 1000× error.

Put a source comment at the retry loop explaining this, so nobody "simplifies" it later and silently stops retrying throttles.

### 6. `aws-core` does not depend on `:env` or `:standards`

It reads env vars through its own `expect fun platformGetEnv(name: String): String?` and caches with `kotlinx.coroutines.sync.Mutex`.

*Rationale*: Two live traps. (a) `env/src/jvmMain/kotlin/com/steamstreet/env/env.kt:28-64` is `runBlocking { … }` and can construct a Secrets Manager or AppConfig client — routing credential lookup through it recurses (credentials → secrets client → credentials) and blocks a coroutine thread. (b) `com.steamstreet.cached` / `MutableLazy` has an **inverted expiry test**: `standards/src/jvmMain/kotlin/com/steamstreet/MutableLazyJVM.kt:38` returns "needs new value" while the value is still fresh, and the 2.3.x native actual has no locking at all.

### 7. SigV4 vectors are code-generated into `commonTest`

A Gradle task reads the vendored 42-case corpus and emits a single `SigV4TestVectors.kt` (a `List<SigV4TestVector>` of string literals) into commonTest's generated-source directory.

*Rationale*: Kotlin Multiplatform has no built-in commonTest resource loading. AWS's own smithy-kotlin hit this wall and gave up — `runtime/auth/aws-signing-tests/native/src/.../SigningSuiteTestBaseNative.kt` is an empty `// FIXME Implement native tests` stub, so **their native signer is untested against their own vectors**. Code generation sidesteps the problem entirely.

*Rejected*: expect/actual resource loading, per smithy-kotlin.

### 8. Crypto: `org.kotlincrypto.macs:hmac-sha2:0.8.0`

*Rationale*: Apache-2.0, pure Kotlin with no JCA/OpenSSL/CRT backing, publishes klibs for every required target (verified HTTP 200 on Maven Central for jvm, linuxarm64, linuxx64, macosarm64). SigV4 needs only SHA-256, HMAC-SHA-256, lowercase hex and UTF-8 bytes — no asymmetric crypto, no randomness. `kotlin.io.encoding.Base64` and `kotlin.text.HexFormat` are both Stable at this repo's Kotlin 2.2.21.

*Rejected*: `dev.whyoleg:cryptography-kotlin` (wraps OpenSSL on native — exactly the native-linking failure class that parked 2.3.x); korlibs-crypto (GitHub reports `NOASSERTION` licence, unacceptable in the auth path of a published library); Ktor utils (provides `sha256` but no HMAC, and its `Digest` API is suspend).

### 9. Validation is four independent oracles, and one of them checks a real signature

| Oracle | What it proves | Where |
|---|---|---|
| AWS's 42 SigV4 vectors | canonical request / string-to-sign / signature are correct | M1, `commonTest`, jvm + macosArm64 + linuxX64 |
| **Live AWS smoke test** | **the signature is accepted by AWS's real verifier** | **M0.5 spike, then M3 exit criterion** |
| Wire differential vs the real SDK (**request AND response**) | our `@SerialName`s match the SDK's bytes, both directions | M3, `jvmTest` |
| LocalStack, 100 existing tests | behaviour is unchanged | M5a (on adapter) then M5b (on hand-written client) |

**The live-AWS oracle is the one the draft plan was missing.** LocalStack cannot verify a signature for a fabricated secret it does not hold, and per LocalStack's docs IAM enforcement is disabled by default. The wire-differential harness short-circuits before the network. Without a credentialed run against real AWS, "100 LocalStack tests green" is fully compatible with a signer that fails every production request.

The harness uses smithy-kotlin's **public `Interceptor` API** (`readBeforeTransmit`, which hands you the fully-signed `HttpRequest`) rather than implementing `HttpClientEngine`, whose recommended base `HttpClientEngineBase` is `@InternalApi`.

### 10. `native-lambda-conventions` is promoted out of `buildSrc`

*Rationale*: `buildSrc` plugins are invisible to downstream projects, which defeats the stated goal at `ref-2.3.x/NATIVE-LAMBDA-PLAN.md:5` ("move that infrastructure into awskt so any project can use it"). `buildSrc/build.gradle.kts` declares only `kotlin-dsl` — no `java-gradle-plugin`, no `maven-publish`, no `gradlePlugin {}` block. Promote it to an included build at `gradle-plugin/` with plugin id `com.steamstreet.awskt.native-lambda`, added via `includeBuild("gradle-plugin")` in `settings.gradle.kts` `pluginManagement`. ~1 day, budgeted in M7.

### 11. `EventBridgeSubmitter` does NOT move to `commonMain`

*Rationale*: `events/build.gradle.kts:11-14` declares `js(IR) { useCommonJs(); browser() }`. Moving `EventBridgeSubmitter` to commonMain would force `aws-eventbridge` onto the JS compilation, and it has no `js` variant — a hard "no matching variant" resolution failure. The file also uses `runBlocking` (`EventBridgeSubmitter.kt:15`), which does not exist on JS. Instead, create an intermediate **`jvmNativeMain`** source set that excludes JS. `:standards`, `:env` and `:logging` are promoted from `events`' `jvmMain` into that same intermediate set.

*Rejected*: adding `js(IR)` to `aws-eventbridge`. Doing SigV4 in a browser means shipping AWS credentials to a browser; `events`' JS consumers use `EventSchema`/`ApplicationEventPoster`, never the submitter.

### 12. Source layout: `dynamokt-exposed` sources stay in `src/jvmMain`

*Rationale*: `dynamokt-exposed/src/main/kotlin/.../Column.kt:114,131` uses `enumClass.java.enumConstants` inside public classes `EnumerationColumn<T>` / `EnumerationByNameColumn<T>`. Placing those in `commonMain` would break even with a jvm()-only target set, because commonMain compiles against the *common* stdlib. Keeping them in `jvmMain` makes the problem disappear entirely and avoids two unnecessary public API breaks. If native targets are ever wanted for this module, convert then.

### 13. S3 is a *bindings* client, not a *protocol* client

GetObject, PutObject, HeadObject and DeleteObject are implemented as pure HTTP header/URI binding operations over a raw byte payload. The only XML anywhere in the slice is a ~60-line **scanner** over the error document. **No XML encoder is ever written, and no XML library is added.**

*Rationale*: The member-binding census in §2 is decisive — 0 body-bound members across all eight in-scope shapes. `PutObjectOutput` has no body at all: 22 members, every one a header. The error document is flat, namespace-free at the point of interest, and `noErrorWrapping: true`; AWS's own equivalent reader is ~50 lines for three tags. Adding an XML dependency here would be the first KMP XML dependency in the repo, for zero operations that need one.

The boundary is the **two-clause test** in §2, restated so it is a model lookup rather than an argument: in scope iff (a) zero body-bound members AND (b) any `@httpPayload` target is a blob, not a structure. Clause (b) is load-bearing: without it, `CompleteMultipartUpload` (0 body-bound members, structure payload) would be wrongly admitted.

Put this comment at the top of `XmlError.kt`: *"This is a scanner for a flat three-tag error document, not a parser. If you need to read a structure, you are outside this module's scope — see §2."* Price the crossing honestly so it is a decision, not drift: ListObjectsV2 + a ~200-line pull reader ≈ 3 days; DeleteObjects +1 (XML *writing* plus a mandatory CRC32, since it **is** one of the 25 `requestChecksumRequired` operations); multipart ≈ 4 days, merging with the streaming workstream into a combined ~6-day effort. See Q10.

*Rejected*: a general restXml codec in `aws-core`; an `xmlutil` / kotlinx-serialization-xml dependency.

### 14. Query-string signing lives in `aws-signing`; the S3-facing presign API lives in `aws-s3`

`aws-signing` exports a pure function from (method, host, encoded path, query, headers, credentials, expiry, instant) to a signed query-parameter list. `aws-s3` exports the two-line convenience that knows what a bucket is.

*Rationale*: The split is forced by two facts, not by taste.

- **Query signing belongs in the signer.** It is pure string/byte work with no S3 knowledge and no I/O. The entire header-vs-query divergence in AWS's own reference implementation is six `param(...)` calls gated on `signViaQueryParams`, one session-token placement rule, and `X-Amz-Signature` appended afterwards (`smithy-kotlin/runtime/auth/aws-signing-default/common/src/.../Canonicalizer.kt:110-155`, `RequestMutator.kt:49-50`). Everything else — `canonicalPath`, `canonicalQueryParams`, `deriveSigningKey`, header canonicalization — is shared verbatim. This preserves `aws-signing`'s "KotlinCrypto only, no Ktor, no awskt modules" constraint (§1) intact.
- **The S3-facing API cannot live in the signer.** `presignGetObject` needs **endpoint and bucket addressing** to produce `https://{bucket}.s3.{region}.amazonaws.com/{encodedKey}` and to derive the `host` the signature covers — that resolver depends on region resolution and `AWS_ENDPOINT_URL_S3`, which are `aws-core`/`aws-s3` code. And it needs **credential resolution, which is `suspend`**: `AwsCredentialsProvider` is declared in `aws-core` (M2), not `aws-signing`, which only holds the resolved `AwsCredentials` value type. `SigV4.sign()` stays non-suspend and takes already-resolved credentials; `S3.presign()` is `suspend` because it resolves them.

*Rejected*: shipping the whole presigner in `aws-signing` (drags `aws-core` in and breaks §1's constraint); putting query signing in `aws-s3` (it has no S3 content, and it is validated by 40 fixtures that exist before any S3 code does).

### 15. There is no `SdkBackedS3`

`S3` is an interface with **exactly one implementation**. Say this explicitly, or someone will build a symmetric adapter nobody needs.

*Rationale*: Decision 3 built `SdkBackedDynamoDb` for two reasons, and **both fail here**. (a) It splits an irreversible 13-day type-swap merge into landable halves — there is no S3 type swap, because there is no S3 code to migrate. (b) It protects 100 existing green tests — there are no S3 tests to protect. The only S3 code in the repo is the 10-line unused `S3Local` stub.

`S3` remains an *interface* for two narrower reasons: test fakes, and the Risk 22 escape hatch if AWS ships native klibs.

*Rejected*: a symmetric `SdkBackedS3`; porting `S3Local` as-is.

### 16. No checksums in v1 — but a completeness check is mandatory

Send no `x-amz-checksum-*` and no `x-amz-sdk-checksum-algorithm` on PutObject; send no `x-amz-checksum-mode` on GetObject. Record as an ADR with the evidence attached.

*Rationale*: Verified against the model, not prose — 25 S3 operations carry `requestChecksumRequired` and neither target operation is among them (§2). S3 attaches a server-side CRC64NVME regardless, TLS protects the wire, and the 2024/25 "checksums are now default" change is SDK *client* behaviour, not a service contract. Cost of this decision: zero days. Cost of reversing it: ~0.5 day.

**But "no checksum" must not mean "no integrity check".** TLS provides per-record integrity, not *stream completeness*: a connection that dies mid-body yields a well-formed, silently short `ByteArray`, and whether the engine raises a premature-close error is engine-dependent — untested on linuxArm64, the one target where tests cannot execute, and on the very Curl code path this plan is bumping Ktor to fix. So M3.5b asserts `body.size == contentLength` (and, on a 206, that the length matches the `Content-Range` span) and throws a typed `S3IncompleteDownloadException` classified **Transient/retryable**. One line; it is the entire integrity story for a checksum-free client. See Risk 30.

*Rejected*: implementing CRC32C (≈650 lines with its slicing table) or hand-writing CRC64NVME (no KMP implementation exists anywhere) for a requirement that does not exist; shipping no completeness check at all.

### 17. Emit `x-id`, exactly as the SDK does

`GetObject`, `PutObject` and `DeleteObject` send the query literal `?x-id=GetObject` / `?x-id=PutObject` / `?x-id=DeleteObject`. `HeadObject` sends none. Presigned URLs carry it too.

*Rationale*: This is a signature-affecting decision that reads like a cosmetic one, which is why it is a numbered decision rather than a task footnote. Verified in the model: `GetObject` `@http(uri: "/{Bucket}/{Key+}?x-id=GetObject")`, `PutObject` `?x-id=PutObject`, `DeleteObject` `?x-id=DeleteObject`, `HeadObject` `/{Bucket}/{Key+}` with **no** `x-id`. Static query literals in a Smithy `@http` trait are serialized into every generated request, so the SDK's presigner emits `x-id` **inside the canonical query, where it is covered by `X-Amz-Signature`**.

The draft-review design said to omit it. Omitting it and *also* demanding a byte-identical presign differential including `X-Amz-Signature` is mutually unsatisfiable: every differential case fails on day one of M3.5a, and the path of least resistance under schedule pressure is to weaken the assertion to "everything except the signature" — which destroys the only offline deterministic oracle in the milestone. Emitting it costs one query pair, S3 ignores it, and it keeps the best test in the S3 slice meaningful.

The invariant to enforce either way: **whatever appears in the outbound query must appear in the canonical query, byte for byte.** M3.5a asserts this mechanically.

*Rejected*: omitting `x-id` (breaks the oracle); omitting it and re-deriving the SDK's signature over a stripped query (a structural comparison that no longer proves the signature is right).

---

## 4. Architecture Overview

```
awskt/
├── gradle-plugin/                        # NEW — included build, publishable
│   └── src/main/kotlin/
│       └── com.steamstreet.awskt.native-lambda.gradle.kts
├── buildSrc/src/main/kotlin/
│   ├── steamstreet-common.jvm-library-conventions.gradle.kts          (unchanged)
│   ├── steamstreet-common.multiplatform-library-conventions.gradle.kts (unchanged)
│   └── steamstreet-common.container-test-conventions.gradle.kts        # NEW
├── aws/
│   ├── aws-signing/                      # NEW  jvm, linuxX64, linuxArm64, macosArm64
│   │   ├── src/commonMain/kotlin/com/steamstreet/aws/signing/
│   │   │   ├── SigV4.kt                  # sign(), canonicalRequest, stringToSign
│   │   │   ├── QuerySigning.kt           # SignatureLocation.QUERY_STRING (presign)
│   │   │   ├── UriEncode.kt              # sigV4UriEncode, normalizePath (conditional)
│   │   │   ├── Canonical.kt              # header/query canonicalization
│   │   │   ├── SigningKey.kt             # deriveSigningKey + date-scoped cache
│   │   │   ├── AwsCredentials.kt         # toString() redacts secret + token
│   │   │   └── Sigv4Time.kt              # civil-from-days, no kotlinx-datetime
│   │   ├── src/commonTest/kotlin/        # SigV4VectorTest (header + query) + unit tests
│   │   └── src/commonTest/vectors/       # vendored 42-case corpus (Apache-2.0)
│   │                                     #   + hand-authored S3-mode encoding corpus
│   ├── aws-core/                         # NEW  jvm, linuxX64, linuxArm64, macosArm64
│   │   └── src/commonMain/kotlin/com/steamstreet/aws/core/
│   │       ├── AwsServiceClient.kt       # sign → send → parse → classify → retry
│   │       │                             #   PROTOCOL-PARAMETERIZED (awsJson | restXml)
│   │       ├── credentials/              # Static, Environment, Chain, Cached
│   │       ├── RegionResolver.kt  EndpointResolver.kt
│   │       ├── RetryPolicy.kt  TokenBucket.kt  ClockSkew.kt
│   │       ├── AwsServiceException.kt    # code, status, requestId, extendedRequestId
│   │       ├── error/AwsErrorParser.kt   # interface + AwsJsonErrorParser
│   │       │                             #   + RestXmlErrorParser
│   │       └── HttpClient.kt             # expect fun awsHttpClient(); followRedirects=false
│   ├── aws-dynamodb/                     # NEW  jvm, linuxX64, linuxArm64, macosArm64
│   │   ├── src/commonMain/kotlin/com/steamstreet/aws/dynamodb/
│   │   │   ├── DynamoDb.kt               # interface, 12 suspend methods
│   │   │   ├── DefaultDynamoDb.kt
│   │   │   ├── model/                    # @Serializable request/response DTOs
│   │   │   ├── Exceptions.kt
│   │   │   ├── Paginators.kt             # queryPaged, scanPaged, items()
│   │   │   └── BatchHelpers.kt           # batchGetAll, batchWriteAll
│   │   └── src/jvmTest/kotlin/           # wire-differential harness (req + resp)
│   ├── aws-dynamodb-sdk-adapter/         # NEW  jvm only
│   │   └── src/main/kotlin/.../SdkBackedDynamoDb.kt
│   ├── aws-eventbridge/                  # NEW  jvm, linuxX64, linuxArm64, macosArm64
│   └── aws-s3/                           # NEW  jvm, linuxX64, linuxArm64, macosArm64
│       ├── src/commonMain/kotlin/com/steamstreet/aws/s3/
│       │   ├── S3.kt                     # interface: get/put/head/delete/presign
│       │   ├── DefaultS3.kt              # the ONLY implementation (Decision 15)
│       │   ├── S3Endpoint.kt             # virtual-host vs path-style addressing
│       │   ├── Presign.kt                # PresignRequest, PresignedUrl
│       │   ├── XmlError.kt               # ~60-line SCANNER, no XML library
│       │   └── Exceptions.kt             # 9 typed + S3Exception fallback
│       └── src/jvmTest/kotlin/           # presign differential vs the SDK presigner
│                                         #   + request differential + restXml corpus
├── dynamo/            # src/main → src/commonMain; OWNS AttributeValue
├── dynamokt/          # src/main → src/commonMain; src/test → jvmTest + commonTest
├── dynamokt-exposed/  # src/main → src/jvmMain (deliberately NOT commonMain)
├── events/            # gains jvmNativeMain intermediate source set
├── lambda/lambda-native/   # cherry-picked from 2.3.x, then spec-fixed
└── test/              # stays JVM + official SDK, EXCEPT DynamoStreamRunner bridge
```

---

## 5. Milestone Table

| ID | Title | Days | Depends on | Landable? |
|---|---|---:|---|---|
| M0 | Branch, approval gate, CI, build scaffolding, **Ktor ≥ 3.5.0 gate** | 5 | — | yes |
| M0.5 | **Vertical spike — real signature on a real Lambda** | 3.5 | M0 | throwaway |
| M1 | SigV4 signer — **header AND query signing**, 74 green vector assertions on 3 targets | 7.5 | M0.5 | yes |
| M2 | `aws-core`: credentials, endpoints, retry, errors, transport | 8.5 | M1 | yes |
| M3 | `aws-dynamodb`: 12 ops, differential harness, SDK adapter, live smoke | 10.5 | M2 | yes |
| **M3.5a** | **`aws-s3` module, S3 endpoint/key encoding, presigned URLs** | **4.5** | M1, M2 | **yes** |
| **M3.5b** | **`aws-s3`: GetObject, PutObject, HeadObject, DeleteObject** | **8** | M3.5a, M2 | **yes** |
| M4 | Foundation native targets (`standards`, `env`, `logging`) | 3 | M0 (parallel with M1–M3) | yes |
| M5a | Type swap — all modules compile & pass on `SdkBackedDynamoDb` | 9.5 | M3 | **yes** |
| M5b | Implementation flip — 100 tests on the hand-written client | 3 | M5a | **yes** |
| M6 | EventBridge client + `events` module | 5 | M4, M5b | yes |
| M7 | Native targets, Lambda runtime, packaging | 10.5 | M6 | yes |
| **M8** | **`aws-secretsmanager` + `aws-kms` data planes** | **2** | M6 | **yes** |
| **M9** | **`aws-sqs` + `aws-sns` + `aws-scheduler` data planes** | **3.5** | M6 | **yes** |
| **M10** | **`aws-bedrock-runtime`: Converse + ConverseStream (response streaming)** | **4** | M9 | **yes** |
| | **Planned (1 FTE)** | **78.5** | | |
| | **With 20% contingency** | **~94** | | |
| | *Critical path with a 2nd developer* | *63 (~76)* | | |

**M8, M9 and M10 are deliberately outside the totals.** All were added on 2026-08-14, after M7
landed, and folding 9.5 days into a "78.5 planned" figure that was quoted in a staffing decision
would rewrite history to make the estimate look better than it was. The v1 plan was 78.5 days for
seven milestones; M8, M9 and M10 are scope additions on top of a delivered plan, and are counted
separately for that reason.

### Parallelization, stated honestly

M4 and M3.5 both depend only on work preceding the DynamoDB migration, and neither is needed by M5a/M5b. **At 1 FTE everything is serial and the total is the sum.**

With a second developer: dev 2 takes M4 (3 d) during the M0.5/M1 window, then M3.5a + M3.5b (12.5 d) once M2 lands, against dev 1's M3 + M5a + M5b = 23 d. The S3 slice fits inside that window with ~10 days of slack, so the critical path becomes M0 → M0.5 → M1 → M2 → M3 → M5a → M5b → M6 → M7 = **63 days**, and only the +7 days of distributed edits (M0, M0.5, M1, M2, M3, M5a, M7) stay on it.

Three caveats that must not be dropped when this is quoted:

1. **This is not free coordination.** M3 and M3.5 both build against `AwsServiceClient.callRaw`, which M2 generalizes (M2 edit 1). Two developers editing `aws-core`'s transport and retry loop for the same ten days is a real merge cost priced at zero above. **Mitigation, and it is a required M2 exit criterion**: freeze `AwsServiceClient`'s signature at the end of M2 with its own `.api` dump, so M3 and M3.5 both code against a fixed seam rather than co-evolving it.
2. **Dev 2 idles.** M4 is only 3 days against a 24.5-day serial prefix (M0 + M0.5 + M1 + M2). Expect ~9 idle days before M3.5a can start, unless dev 2 is doing unrelated work.
3. **S3 is not "needed by nothing except M7".** It reaches M5a (the `S3Local` decision) and M7 (the deployed smoke) by this plan's own task list.

**This is nonetheless a real change to the staffing argument and belongs in the decision.** Before S3 was in scope a second developer bought only M4's 3 days — barely worth the coordination cost. Now they buy 15.5. That is the difference between "nice to have" and "the plan is meaningfully shorter with two people."

### Where M3.5 sits at 1 FTE, and why

Run M3.5 **after M3 and before M5a**. Three reasons, stated so the ordering is not re-litigated:

- (a) M3 shakes out `AwsServiceClient` against a real service first, so S3 inherits a proven transport rather than co-designing it.
- (b) M5a is the irreversible merge and should not be entered with two unlanded workstreams behind it.
- (c) It creates a genuinely useful landing point: `aws-signing` + `aws-core` + `aws-dynamodb` + `aws-s3` are all NEW artifacts with **zero API break**, so they can ship while the §9/Q1 approval is still being negotiated. (The one S3-related *removal* — deleting `S3Local` from the published `awskt-test` artifact, Q1 row (k) — is deliberately held back into M5a and is **not** part of this additive set.)

*Rejected*: running M3.5 before M3. It would validate the two-protocol seam earlier, but the M0.5 addition buys most of that de-risking for half a day, and it delays the headline DynamoDB deliverable.

---

## 6. Per-Milestone Task Breakdown

### M0 — Branch, approval gate, CI, build scaffolding, Ktor gate (5 days)

No client code is written in this milestone.

**Tasks**:
- [ ] Cut the branch **`3.0.x`** from `2.2.x` @ `20ece7b`. The name must be exactly `3.0.x` — see the Version note in the header; `nebula.release` derives the release line from the branch, and this repo's existing lines are `2.0`, `2.1.x`, `2.2.x`, `2.3.x`. Verified: `claude/native-aws-kotlin-sdk-491425` @ `e3aa4b2` is an *ancestor* of 2.2.x, 212 commits behind, 0 ahead, and its `settings.gradle.kts` lacks `dynamo`, `dynamokt-exposed`, `cognito`, `serialization`, `lambda-kinesis` and `lambda-default` entirely.
- [x] ~~Confirm `nebula.release`'s configured branch pattern accepts `3.0.x`.~~ **DONE 2026-08-09.** `./gradlew properties` on the cut branch returns `version: 3.0.0-dev.0.uncommitted+20ece7b`. The pattern accepts it and the root build configures cleanly at `20ece7b`.
- [ ] Re-verify the premise: check whether any current `aws.sdk.kotlin` release publishes `linuxArm64` klibs. If it does, stop and un-park 2.3.x instead. Ten minutes; repeat at every milestone boundary.
- [ ] ~~**Obtain written approval for the public API break**~~ — **DONE 2026-08-09** (see §9 Q1). Approved for items (a)–(i). Item (k) stays blocked on Q7; item (j) stays deferred to M7. Record the approval in an ADR file as the durable artifact.
- [ ] Add `org.jetbrains.kotlinx:binary-compatibility-validator` and commit `.api` dumps for `dynamo`, `dynamokt`, `dynamokt-exposed` **before** M5a, so the migration PR's diff shows exactly what the public surface change is. This is the artifact the approval gate needs and it does not exist today.

> **STATUS: DONE (2026-08-10), via the Kotlin plugin's built-in ABI validation rather than BCV.**
> **35 dump files across 26 modules**, generated by `updateLegacyAbi` and verified by
> `checkLegacyAbi`, which the Kotlin plugin wires into `check` — so `./gradlew build` now fails on
> an unreviewed public-API change. The whole repo is covered, not just the three modules this task
> named; the baseline is worth more the wider it is, and it cost nothing extra to apply in the two
> convention plugins.
>
> **Substituted `kotlin { abiValidation { } }` for `org.jetbrains.kotlinx:binary-compatibility-validator`.**
> Two reasons, the second deciding:
> 1. It ships inside the Kotlin plugin already applied, so it cannot drift out of step with the
>    compiler — a live concern on a repo that just moved 2.2.21 → 2.3.21 to satisfy the Ktor gate.
> 2. **It dumps klibs.** `aws-core.klib.api` opens with `// Targets: [linuxArm64, linuxX64,
>    macosArm64]` — so the target this entire project exists for is covered. A JVM-only dump would
>    have frozen the surface on the one platform whose ABI was never in question.
>
> `keepUnsupportedTargets = true` is set deliberately: without it a dump taken on macOS drops the
> Linux targets and a dump taken on CI drops the Apple ones, and the two hosts fight over the
> checked-in file forever.
>
> **M2's exit criterion is met**: `AwsServiceClient.callRaw` is frozen in `aws-core.klib.api:112`
> with its generalized `(method, path, query, headers, body, …)` signature, so M3.5 can be developed
> against a fixed seam. M3's `AwsServiceException.rawErrorBody` addition is recorded in both dumps.
>
> **AMENDED 2026-08-12 — the freeze was subsequently and deliberately broken.** M3.5b added a
> trailing `inspectBeforeBody` parameter; see M3.5b's STATUS for why it was unavoidable and for the
> ratification recorded there. This criterion is left as written rather than rewritten, because "frozen, then
> broken once with a recorded reason" is the accurate history and a silently-edited criterion would
> hide it.
>
> **AMENDED 2026-08-13 — broken a second time, by the fix to the first break's fallout.** A trailing
> `validateBody` parameter joined it. The ratification above turns on `aws-core` having no published
> version and no external consumer, and that is still true, so the same reasoning covers this one —
> but "broken once" is no longer the accurate history, and the count is the point. See M3.5b's
> STATUS.
>
> **The guard was mutation-tested rather than assumed.** Adding one public function to `aws-core`
> makes `checkLegacyAbi` fail with a readable diff naming it, in *both* the `.api` and the
> `.klib.api`. A frozen-API artifact that never fires would be worse than none, since it reads as
> coverage.
>
> Note the dumps live in each module's `api/` directory and are not gitignored — they are source,
> and reviewing their diff is the point.
- [ ] Pre-task (30 min, separate commit): apply strict `explicitApi()` to `dynamo`/`dynamokt`/`dynamokt-exposed` on the *current* `jvm-library-conventions` build and fix whatever it surfaces. `buildSrc/src/main/kotlin/steamstreet-common.jvm-library-conventions.gradle.kts:11` is `explicitApiWarning()`; the MPP convention leaves it to each module. Decoupling this from the multiplatform conversion removes an unrelated failure source.
- [ ] **HARD GATE — bump `ktor = "3.3.3"` → `>= 3.5.0`** at `gradle/libs.versions.toml:4`. Do not start native transport work until it lands. Rationale, and **this is NOT an S3 cost**: KTOR-9527 ("Curl: Freeze when receiving large responses", fixed 3.5.0) is a *freeze*, not an error — `CurlHttpResponseBody.onBodyChunkReceived` bridges libcurl's write callback through `runBlocking`, and `ByteChannel.flush()` suspends once the unflushed buffer reaches **1 MB**, blocking the curl thread where no timeout can rescue it. **DynamoDB's Query and Scan page limit is exactly 1 MB**, so a full-page native `Query` response sits precisely on the threshold. KTOR-9483 ("Curl: backpressure implementation is never used", fixed 3.5.0) compounds it. This is pre-existing debt in the plan that the S3 analysis merely exposed; a native GetObject or full-page Query over the limit is a **hang until Lambda timeout**, which no unit test and no LocalStack run can reproduce.
  - **Blast radius, verified against `ref-2.2.x`**: exactly three build files reference `libs.ktor` — `cognito/build.gradle.kts`, `lambda/lambda-api-gateway-ktor/build.gradle.kts`, `logging/build.gradle.kts`. **`events` references no ktor at all**, so the js(IR) concern in early drafts of this section was wrong. The genuinely risky module is **`logging`**, which declares `iosArm64()`, `iosSimulatorArm64()`, `js { browser() }` and `wasmJs { browser() }` (`logging/build.gradle.kts:12-19`) alongside `compileOnly(libs.ktor.client.core)` in commonMain and `api(libs.ktor.client.core)` in its native set — a Ktor minor bump across wasmJs and Apple targets is where a 1-day estimate breaks, and per §6.1 it can only be verified on the macOS host, not on ubuntu CI.
  - **Caveat to record, not to hide**: KTOR-9527's affected-versions field lists 3.4.3, not 3.3.3. That 3.3.3 is affected is an *inference from the described mechanism*, not a stated fact. Confirm on the issue tracker during M0; if 3.3.3 turns out unaffected the bump is still wanted for KTOR-9483/9545/9546, but its severity drops from gate to hygiene.
  - **Verification**: `./gradlew build` green across the whole repo **on macOS** (the only host that can build `logging`'s Apple targets), including `:logging:compileKotlinWasmJs`, `:logging:compileKotlinJs`, and both ktor-server modules.

> **STATUS: THE KTOR GATE IS CLEARED (2026-08-10) — `ktor = "3.5.2"`, the latest release.**
> `./gradlew build` is green across the entire repo on macOS: **476 tests, 0 failures**, spanning
> jvm, macosArm64, iosSimulatorArm64, js/browser and wasmJs, plus the Docker-backed `dynamokt` (25)
> and `dynamokt-exposed` (72) suites. Linux native test binaries link for `aws-signing`, `aws-core`
> and `aws-dynamodb` on both `linuxArm64` and `linuxX64`.
>
> **THE GATE WAS NOT A VERSION BUMP. IT FORCED A REPO-WIDE KOTLIN UPGRADE, AND THIS SECTION
> UNDER-PRICED IT BY A WHOLE WORKSTREAM.** `buildSrc/build.gradle.kts` pinned Kotlin **2.2.21**;
> from **Ktor 3.4.0 onward** the published Kotlin/Native klibs carry `abi_version=2.3.0`
> (`compiler_version=2.3.0` for 3.4.x, `2.3.21` for 3.5.x), and a 2.2.x compiler cannot read them.
> Verified by unzipping the klib manifests for 3.3.3, 3.4.0, 3.4.3, 3.5.0, 3.5.1 and 3.5.2: 3.3.3 is
> `abi_version=2.2.0` and every version from 3.4.0 up is `2.3.0`. **There is no Ktor >= 3.5.0 that
> works on Kotlin 2.2.x**, so the gate and the Kotlin upgrade are one decision. Kotlin is now
> **2.3.21**, which is what Ktor 3.5.2 itself was compiled with.
>
> **The failure mode is a lie, and it cost real time — record it so the next person does not repeat
> the diagnosis.** An incompatible klib is reported as
> `KLIB resolver: Could not find "<absolute path>" in [...]` for a file that is sitting at exactly
> that path, fully downloaded. It reads as a corrupt cache or a download race; it is neither. If you
> see it after a Ktor or Kotlin change, unzip the klib and read `default/manifest` — `abi_version`
> is the answer.
>
> Two smaller consequences, both mechanical:
> 1. **Both yarn lockfiles had to be regenerated** — `kotlinStoreYarnLock` and then
>    `kotlinWasmStoreYarnLock` fail in sequence until `kotlinUpgradeYarnLock` and
>    `kotlinWasmUpgradeYarnLock` are run. `kotlin-js-store/yarn.lock` and
>    `kotlin-js-store/wasm/yarn.lock` are both part of the change.
> 2. **Two new deprecation warnings**, in `lambda/lambda-api-gateway-ktor/.../ApiGatewayKtorCall.kt:53,56`
>    — the `RequestConnectionPoint.host` / `.port` overrides. Warnings only; left alone because the
>    replacement members carry different semantics and that is not this milestone's call.
>
> This section's own risk assessment was right about *which* module was dangerous and wrong about
> *why*. It named `logging` (iOS + wasmJs + js) as "where a 1-day estimate breaks" — and `logging`
> was indeed the first thing to fail, but on the Kotlin ABI, not on anything Ktor-API-shaped. Once
> Kotlin moved, no source change was needed anywhere in the repo.
>
> Also cleared from this section since: `binary-compatibility-validator` and the `.api` dumps (done
> 2026-08-10, via the Kotlin plugin's built-in ABI validation — see the M0 task above).
- [ ] Version catalog additions: `kotlincrypto-hmac-sha2 = { module = "org.kotlincrypto.macs:hmac-sha2", version = "0.8.0" }`, `ktor-client-curl = { group = "io.ktor", name = "ktor-client-curl", version.ref = "ktor" }`, `ktor-client-mock = { group = "io.ktor", name = "ktor-client-mock", version.ref = "ktor" }`.
- [ ] **Ten-minute check before M3.5a's harness design is committed**: confirm that `aws.sdk.kotlin:s3` publishes a presigner extension (`S3Client.presignGetObject` / `presignPutObject`) resolvable as a `jvmTest` dependency on Maven Central. It is the best oracle in M3.5a. If it does not resolve, the 37 query-mode vector assertions and the live unauthenticated-fetch oracle both still stand and the milestone survives — it just loses its offline deterministic byte comparison and M3.5a grows by ~0.5 day. See Risk 33.
- [ ] Create `buildSrc/src/main/kotlin/steamstreet-common.container-test-conventions.gradle.kts`, hoisting the OrbStack docker-socket block currently triplicated verbatim at `dynamokt/build.gradle.kts:38-47`, `dynamokt-exposed/build.gradle.kts:31-40`, `test/build.gradle.kts:54-63`, applied via `tasks.withType<Test>().configureEach`. **Do this BEFORE any 2.3.x cherry-pick** — 2.3.x deleted all three copies.
- [ ] Decide the fate of `dynamokt/build.gradle.kts:35-36` (`systemProperty("java.library.path", dynamo_libs)`) — nothing in-repo uses DynamoDBLocal. Recommend deleting it and the ~12 MB `dynamokt/dynamo_libs/` directory in a separate commit.
- [ ] Add `.github/workflows/ci.yml` with an explicit **host-to-task matrix** (see §6.1). Note `pr.yml:3-5` fires only on `[opened, reopened]`, so pushes to an open PR are never built today, and `build.yml:4-5` triggers only on dead branches "2.0"/"2.1".
- [ ] `include(":aws:aws-signing")`, `include(":aws:aws-core")` in `settings.gradle.kts`. Safe: `ref-2.2.x/lambda/` has no `build.gradle.kts` and the root `tasks.named("final")` uses `tasks.matching { it.name == "publishToSonatype" }`, so an empty container project contributes nothing.

**Verification**: `./gradlew build` green **on macOS** on the new branch at Ktor ≥ 3.5.0, with the container-test plugin applied and the three triplicated OrbStack blocks removed, including `:logging:compileKotlinWasmJs` and `:logging:compileKotlinJs`; `./gradlew updateLegacyAbi` produces committed `.api`/`.klib.api` files (the task is `updateLegacyAbi`, not `apiDump` — see the ABI-validation STATUS note above); the new CI workflow passes on a throwaway PR; written API-break approval recorded in an ADR file.

#### 6.1 Host-to-test-task matrix (referenced throughout)

| Host | Tasks | Notes |
|---|---|---|
| macOS ARM dev machine | `jvmTest`, `test`, `macosArm64Test`, all `link*` incl. Apple targets | The only host that can build the existing `iosArm64` targets, therefore the only host that can publish |
| `ubuntu-latest` CI | `build`, `jvmTest`, `test`, `linuxX64Test`, Docker/LocalStack integration tests | Cannot build final binaries for Apple targets |
| `linuxArm64` | `linkDebugTestLinuxArm64` / `linkReleaseExecutableLinuxArm64` **only** | Tier 2, running tests unsupported. Covered solely by the deployed-Lambda smoke invocation in M0.5 and M7 |

CI command is `./gradlew build linuxX64Test` (not `jvmTest test` — those task names change as modules convert, and `build` already implies `check`).

---

### M0.5 — Vertical spike (3.5 days) — THE KILL POINT

A throwaway `linuxArm64` executable that hand-signs one `GetItem`, links against Ktor Curl, runs on a real `provided.al2023` / arm64 Lambda with the libcrypt layer, and hits a real DynamoDB table.

**Rationale**: SigV4 is the *best-specified, best-oracled, lowest-uncertainty* component in the project — 42 fixtures are already on disk. The genuinely uncertain things are Curl + TLS + libcrypt on linuxArm64, engine byte fidelity, and whether a hand-computed signature is accepted by AWS's real verifier. This spike exercises all of them in one shot. If it fails, you have spent 3 days, not 20.

**Tasks**:
- [ ] Minimal SigV4 implementation, correctness not required beyond this one request shape.
- [ ] `HttpClient(Curl)` with `caInfo`; build the libcrypt.so.1 layer from the `amazonlinux:2` arm64 image; deploy with `LD_LIBRARY_PATH=/opt/lib:/lib64:/usr/lib64`.
- [ ] Deploy to a real Lambda, invoke, `GetItem` from a real DynamoDB table with real execution-role credentials (`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN` from the environment — note the session token is on the hot path for 100% of Lambda requests).
- [x] **Record measured cold-start and binary size — DONE 2026-08-12, on the deployed function.** 2.3.x's claimed ~35–60 ms init was optimistic: measured `Init Duration` is **54–90 ms, median ~65 ms**, and it is **flat across 128/512/1024 MB** — process start is binary load, not CPU, so memory does not buy it down. Binary is **4.62 MiB zipped / 11.93 MiB unzipped** against the claimed ~4.1 MB, though this one also carries S3, presigning and the signer. Full numbers in M7's STATUS.
- [ ] **KILL CRITERION — from the same `linuxArm64` binary, perform one S3 `GetObject`** of a small fixed object, using the same hand-rolled signer in **S3 mode** (`doubleUriEncode=false`, `normalizeUriPath=false`, `x-amz-content-sha256` = the real empty-body SHA-256, virtual-host authority). +0.25 day. This proves a second host and a second endpoint shape resolve through the same Curl/TLS/libcrypt stack, which is exactly what the spike exists to test.
- [ ] **DIAGNOSTIC, NOT A KILL CRITERION — generate one presigned GET URL** for that object and fetch it with a plain HTTPS GET from inside the spike. +0.25 day. Record the result; **do not fail the spike on it.**

  *Why demoted*: the spike's signer is explicitly "correctness not required beyond this one request shape", and its whole value is a single unambiguous signal about Curl + TLS + libcrypt. A 403 from a half-built query signer says nothing about any of those, and would send 3.5 days into debugging a signer M1 is going to write properly anyway. It is worth doing because it is cheap and because a **200 proves the query signer end to end with no SDK, no credentials on the verifying side and no response parsing** — the cheapest oracle in the project. It is not worth killing the project over.

**Verification**: A real Lambda invocation returns **the item AND the object**. The presigned-URL fetch result is recorded either way.

**Kill criteria, and only these two**: (i) the `linuxArm64` binary does not link or does not run on `provided.al2023`; (ii) `SignatureDoesNotMatch` on the DynamoDB `GetItem` **or** on the S3 `GetObject`. Delete the spike afterwards — its value is the answer, not the code.

---

### M1 — SigV4 signer: header AND query signing, 74 green vector assertions on 3 targets (7.5 days)

**File**: `aws/aws-signing/build.gradle.kts` — `steamstreet-common.multiplatform-library-conventions`, `explicitApi()`, targets `jvm()`, `linuxX64()`, `linuxArm64()`, `macosArm64()`. Single dependency: `libs.kotlincrypto.hmac.sha2`. **No Ktor, no project dependencies.**

**Query-string (presign) signing lands entirely in this milestone, not in M3.5a.** It is signer-internal, it is validated by 40 already-vendored AWS fixtures that exist before any S3 code does, and it is public API in an independently publishable artifact. M3.5a therefore contains **no** signer work — only the S3-facing convenience over a finished signer.

#### The vector arithmetic, stated once and carried everywhere

Verified by directory listing of the vendored corpus: **42 case directories**, of which **40 contain `header-canonical-request.txt`** and **40 contain `query-canonical-request.txt`**. The two that contain neither are `get-vanilla-query-order-key` and `get-vanilla-query-order-value`, which hold only `context.json` and `request.txt` — this is why smithy-kotlin lists them under `// no signed request to test against`.

So the ceiling is **40 header-capable + 40 query-capable = 80 case-assertions**, not 82 and not "all 42". Documented skips:

| Skip | Header | Query | Reason |
|---|:-:|:-:|---|
| `get-header-value-multiline` | ✗ | ✗ | inbound obs-fold parsing only, never produced on the sending side |
| `post-x-www-form-urlencoded` | ✗ | ✗ | corpus signs `content-length`; we deliberately exclude it (see the exclusion-set task) |
| `post-x-www-form-urlencoded-parameters` | ✗ | ✗ | same |

**37 header + 37 query = 74 green vector assertions.** Any success criterion that says "all 42" or "82" is unachievable and will be quietly downgraded on first contact — which is how a hard gate loses its force. Use 74.

**Tasks**:
- [ ] Vendor the 42-case v4 corpus into `aws/aws-signing/src/commonTest/vectors/` from `awslabs/aws-c-auth` `tests/aws-signing-test-suite/v4` (Apache-2.0, AWS-owned; already on disk in the scratchpad, full file set verified). Preserve the Apache NOTICE and add a provenance README pinning the source commit — awskt publishes under MIT, so attribution must stay.
- [ ] Gradle task `generateSigV4Vectors` emitting `SigV4TestVectors.kt` into commonTest's generated-source dir (~50 lines). It must emit **both** families: `headerCanonicalRequest` / `headerStringToSign` / `headerSignature` / `headerSignedRequest` **and** `queryCanonicalRequest` / `queryStringToSign` / `querySignature` / `querySignedRequest`. It must also read `expiration_in_seconds`, `sign_body`, `normalize`, `double_uri_encode` and the credential `token` out of `context.json` — it already opens that file, and all 42 contexts carry `expiration_in_seconds: 3600`. This is four more string literals per case for roughly double the assertion count.
- [ ] `commonMain`: `AwsCredentials`, `SigningRequest`, `SigV4Config`, `SignedRequest(headers, query, signedHeaderNames, canonicalRequest, stringToSign, signature)`, `object SigV4 { fun sign(...) }`. Exposing `canonicalRequest` and `stringToSign` is not debug cruft — it is what makes a fixture failure diff to exactly one normalization rule.
- [ ] **`SigV4Config` gains six members, and they must land HERE, not in M3.5a.** `aws-signing` is pitched as an independently publishable artifact (§Verdict), so this is public API in a published module and retrofitting it costs ~1 day plus an API revision instead of ~0.3 day now.
  - `location: SignatureLocation` — `HEADERS` (default) | `QUERY_STRING`.
  - `expiresInSeconds: Long?` — required iff `QUERY_STRING`; **hard-capped at 604 800** (7 days) with a clear client-side error quoting S3's own text, because the SigV4 signing key is date-scoped and valid for at most seven days.
  - `payloadHash: PayloadHash` — sealed: `Compute` (hex SHA-256 of the body) | `EmptyBody` (`e3b0c442…b855`) | `Unsigned` (the literal `UNSIGNED-PAYLOAD`; S3 only, HTTPS only, **mandatory for presign** because at presign time the body does not exist) | `Precomputed(hex)`.
  - `signedBodyHeader: SignedBodyHeader` — `NONE` | `X_AMZ_CONTENT_SHA256`.
  - `doubleUriEncode: Boolean = true` — **false for S3 only.**
  - `normalizeUriPath: Boolean = true` — **false for S3 only.** S3 keys may legitimately contain `.`, `..` and `//` segments; AWS's guidance is explicit that URI paths are not normalized for S3 because a bucket may hold an object literally named `my-object//example//photo.user`, and `aws-sdk-php`'s `S3SignatureV4` sets `should_normalize_uri_path => false` and `use_double_uri_encode => false`.

  **Left undone, an S3 key containing `.`, `..` or `//` passes 100% of CI and fails 100% of production** — identical in shape to Risk 3. This is an M1 correctness cost that happens to be discovered by S3, not an S3 cost. See Risk 25.
- [ ] **`AwsCredentials` gains `expiresAtEpochMillis: Long?`** (epoch millis, deliberately not kotlinx-datetime — same reasoning as the `Sigv4Time` task below). It is needed by the presign expiry clamp, and it is public API of a published artifact, so it cannot wait for M2's `CachedCredentialsProvider`. **Read Risk 24 before designing anything on top of it: in Lambda this field is always `null`, and the API must not pretend otherwise.**
- [ ] **QUERY_STRING mode**, stated as rules because each one is a distinct fixture failure:
  - Inject **into the canonical query, before signing**: `X-Amz-Algorithm`, `X-Amz-Credential`, `X-Amz-Date`, `X-Amz-Expires`, `X-Amz-SignedHeaders`, and `X-Amz-Security-Token` when a session token is present. Verified against the vendored fixture `get-vanilla-with-session-token/query-canonical-request.txt`, which signs the token **inside** the query and declares `X-Amz-SignedHeaders=host` alone. AWS's prose on this is deliberately vague ("some services require… other services require only…"); the fixture is the arbiter. This matters more than anything else in the mode: Lambda execution-role credentials always carry a session token, so 100% of production presigns take this path and 0% of a `StaticCredentialsProvider` LocalStack test does.
  - `X-Amz-SignedHeaders`' semicolons **are** percent-encoded in the canonical query — `post-x-www-form-urlencoded/query-canonical-request.txt` reads `X-Amz-SignedHeaders=content-length%3Bcontent-type%3Bhost`.
  - `X-Amz-Signature` is appended **after** signing and is **never** canonicalized.
  - Emit **no** `Authorization` header and **no** `x-amz-content-sha256` header (`Canonicalizer.kt:94` gates the hash header on `!signViaQueryParams`).
  - **Synthesize the `host` header ourselves.** smithy-kotlin does *not* — `Canonicalizer.kt:125` gates it on `!signViaQueryParams` and relies on the caller's request already carrying it. That omission is a known real-world bug class producing an empty `X-Amz-SignedHeaders`.
  - The final URL need **not** be in canonical sort order; sorting is a canonicalization rule, not a URL-construction rule. `get-vanilla/query-signed-request.txt` emits the parameters unsorted.
- [ ] **`host` is the HTTP authority, including a non-default port.** `host` when the port is the scheme default (80/http, 443/443), `host:port` otherwise. The signer must not synthesize it — `AwsServiceClient` derives it from the resolved endpoint URL and passes it in, and the same string is set as the outbound `Host` header. **This bug would pass 100% of CI and fail 100% of production**: every existing test runs against LocalStack on an ephemeral port (`DynamoKtTests.kt:24` uses `localstack.getEndpointOverride(...)`), and the AWS fixture corpus contains only default-port cases.
- [ ] An empty path canonicalizes to `/`.
- [ ] Internal, individually unit-tested (each maps to a distinct fixture-failure class):
  - `sigV4UriEncode(s, encodeSlash)` — **UPPERCASE** hex, unreserved set `A-Za-z0-9-._~`, space → `%20`, operating on `s.encodeToByteArray()`. Do NOT use `io.ktor.http.encodeURLPath`; AWS explicitly warns against platform encoders. **The path encoder is applied twice when `doubleUriEncode = true` and once when it is false.**
  - `normalizePath(path)` — RFC 3986 §5.2.4 remove_dot_segments. Encode segments FIRST, then normalize. **CONDITIONAL on `SigV4Config.normalizeUriPath`.** Unit test: the key `a/../b` must produce **two different canonical URIs** under the two settings.
  - `canonicalHeaderValue(v)` — trim **AND collapse internal whitespace runs**. A naive `.trim()` passes `get-vanilla` and fails `get-header-value-trim`.
  - `canonicalQuery(pairs)` — encode first, THEN sort by encoded key, then by encoded value.
  - `deriveSigningKey(secret, yyyymmdd, region, service)` — four chained HMACs seeded with literal ASCII `"AWS4"` + secret.
- [ ] Format `X-Amz-Date` and the `yyyyMMdd` credential scope from **ONE** captured `Instant` using ~25 lines of civil-from-days arithmetic. Do not depend on kotlinx-datetime just to format a timestamp — its field accessors churned across 0.6→0.7 and this is the auth path. Signing at 23:59:59.999 must not straddle midnight.
- [ ] Signing-key cache keyed on `(secretIdentity, region, service, yyyyMMdd)` — a long-lived Lambda container crossing midnight or receiving rotated credentials must re-derive.
- [ ] **Header exclusion set — corrected against AWS's actual list.** smithy-kotlin's `skipHeaders` (`aws-signing-default/common/src/.../Canonicalizer.kt:60-81`, read directly) is: `expect`, `sec-websocket-key`, `sec-websocket-protocol`, `sec-websocket-version`, `user-agent`, `x-amzn-trace-id`, `connection`, `keep-alive`, `proxy-authenticate`, `proxy-authorization`, `te`, `trailers`, `transfer-encoding`, `upgrade`. The draft's list had two errors: it wrote `trailer` where AWS writes **`trailers`**, and it omitted the three `sec-websocket-*` entries. Fix both. `content-type` and `x-amz-target` ARE signed. `user-agent` in particular must be excluded — Ktor adds one automatically and signing it is latent breakage on any Ktor version bump.
  - **`content-length` is a DELIBERATE, COMMENTED DIVERGENCE.** AWS's list does *not* contain it, and the corpus proves AWS signs it in **both** modes: `post-x-www-form-urlencoded/header-canonical-request.txt` signs `content-length:13`, and its `query-canonical-request.txt` emits `X-Amz-SignedHeaders=content-length%3Bcontent-type%3Bhost`. **The reason to write in the source comment is self-consistency, not presign.** Ktor sets `Content-Length` *after* we sign, so signing it is latent breakage on any engine or version change — precisely the argument already made for `user-agent`. (For presign it is simply a no-op, since there is no body at signing time; do not record that as the justification, because a wrong reason invites a future "simplification".)
  - Consequence: `post-x-www-form-urlencoded` and `post-x-www-form-urlencoded-parameters` become documented skips in **both** header and query mode — **four** documented skips, not two. That is already reflected in the 74-assertion arithmetic above.
- [ ] `AwsCredentials.toString()` emits only the access key id — never the secret or session token. Same for `SigV4Config`. Unit test asserts the literal secret string never appears in `toString()` or any thrown exception's `message`/`stackTraceToString()`. This matters: `logging/src/commonMain/.../HttpLogPublisher.kt` PUTs log payloads to an arbitrary URL.
- [ ] Hardcode the signing-name override table now, before it can be forgotten: `dynamodb-streams` signs as `dynamodb` against host `streams.dynamodb.*`; `appconfigdata` signs as `appconfig` against host `appconfigdata.*`. Neither is in v1 scope but both produce `SignatureDoesNotMatch` that looks like a signer bug.
- [ ] `SigV4VectorTest` in `commonTest` asserting three levels per case — `canonicalRequest`, `stringToSign`, `signature` — **in BOTH header and query mode**. The draft said "skip the query-signing (presigned) assertions"; **that instruction is deleted.** It was discarding a free oracle: 40 of the 42 vendored case directories already contain `query-canonical-request.txt`, `query-string-to-sign.txt`, `query-signature.txt` and `query-signed-request.txt`, and the generator already opens each case directory for the header files. This is the only oracle in the entire project that validates presigned URLs to the byte **before any S3 code exists**. Document the four content-length skips and the `get-header-value-multiline` skip inline.
- [ ] **NEW — hand-author the S3-mode encoding corpus (~0.5 day).** Cover keys containing a space, `+`, `/`, `//`, `.`, `..`, `%`, `:`, `?`, `#` and non-ASCII, asserted in **both** header and query mode under `doubleUriEncode=false, normalizeUriPath=false`.

  **Sizing this honestly, because an overstated risk gets discounted**: the AWS corpus is *not* silent on these flags. `normalize=false` is covered by **seven** cases (`get-relative-unnormalized`, `get-relative-relative-unnormalized`, `get-slash-unnormalized`, `get-slashes-unnormalized`, `get-slash-dot-slash-unnormalized`, `get-slash-pointless-dot-unnormalized`, `get-space-unnormalized`) and `double_uri_encode=false` by exactly **one** (`get-percent-single-encoded`, whose already-single-encoded path `/foo/bar/baz%3Cqux%3Aquux` appears verbatim in *both* its header and query canonical requests). What is genuinely untested is **the two flags together, under S3-shaped keys**. So this is a supplement to eight existing fixtures, not a from-scratch build — half a day, not a day. See Risk 25.
- [ ] **Presign safety scaffolding** (the types are here, the S3 convenience is in M3.5a): reject `expiresInSeconds > 604_800` at construction with S3's literal error text; a unit test asserting the secret, the session token **and the signature** appear in no `toString()`, no exception `message` and no `stackTraceToString()` — the same test shape as the `AwsCredentials.toString()` task above, extended to the query-signing output.

> **STATUS: M1 IMPLEMENTED AND GREEN (2026-08-09).** `aws/aws-signing` exists on branch `3.0.x`.
> **45 tests, 0 failures** — 43 offline (identical on `jvm` and `macosArm64`), of which the corpus
> harness carries the **74 vector assertions (37 header + 37 query)**, plus 2 live-AWS tests on jvm.
> `linuxX64` and `linuxArm64` both compile and link.
>
> **THE LIVE-AWS ORACLE IS CLOSED.** Against real DynamoDB in `ai-vegasful-test` (us-west-2), AWS
> accepted a signature this library produced — `HTTP 200`, real `TableNames` returned — and rejected
> the same request with one hex digit of the signature flipped (`HTTP 400`,
> `InvalidSignatureException`). The negative control is what makes the positive result mean
> anything; without it, "no signature error" also passes when the request never reaches AWS.
> This retires the plan's central worry that the whole suite could be green while every production
> request fails. Re-run with:
> `eval "$(aws configure export-credentials --profile ai-vegasful-test --format env)" && ./gradlew :aws:aws-signing:jvmTest`
> (the test self-skips with no credentials, so CI stays green).
>
> **A finding that outranks the rest, and that M2 must not repeat.** The live test failed on first
> run with `InvalidSignatureException` and the signer was *not* at fault: the JDK's legacy
> `HttpURLConnection` rewrites headers it considers its own, silently editing a request after it was
> signed. Replaying the byte-identical signed request through `curl` returned `HTTP 200`, which is
> what isolated it. **Any HTTP client that mutates headers after signing breaks SigV4**, and the
> failure is indistinguishable from a signer bug. This is the empirical vindication of excluding
> `content-length` and `user-agent` from the signature (Decision-level, see M1's exclusion-set task),
> and it is a hard constraint on M2's Ktor transport: signing must be the **last** mutation before
> the bytes go out. The live test now uses `java.net.http.HttpClient` and retains an env-guarded
> `AWSKT_DUMP_CURL` escape hatch that dumps a replayable curl config — that hatch is what turned a
> blind alley into a ten-minute diagnosis.
>
> Three further findings worth carrying forward:
> 1. `org.kotlincrypto.macs:hmac-sha2` does **not** re-export the SHA-256 digest, so
>    `org.kotlincrypto.hash:sha2` is a second explicit dependency. Decision 8's "single dependency"
>    is wrong; it is two, both Apache-2.0 and pure Kotlin.
> 2. In **header** mode the access key id does not affect the signature — it rides in the unsigned
>    `Authorization` header. It *does* in query mode, where `X-Amz-Credential` is canonicalized.
>    Both are now asserted so the asymmetry is recorded rather than rediscovered.
> 3. `UNSIGNED-PAYLOAD` must **not** be forced for presigning at the signer level: the corpus
>    presign vectors sign a real (empty) body hash, and forcing it fails all 37. It is an S3-layer
>    policy belonging to M3.5a.
>
> Not yet done in M1's dependencies: ABI validation (an M0 task — **done 2026-08-10**), and
> `linuxX64Test` *execution*, which needs the ubuntu CI runner M0 sets up.

**Verification**:
- ✅ On the macOS ARM dev machine: `./gradlew :aws:aws-signing:jvmTest :aws:aws-signing:macosArm64Test` green.
- ⏳ On ubuntu CI: `./gradlew :aws:aws-signing:linuxX64Test` green. *(links; execution blocked on M0's CI runner — a Linux binary cannot run on the macOS host)*
- ✅ `linkDebugTestLinuxArm64` compiles but is never executed (Tier 2).
- **74 green vector assertions — 37 header + 37 query — on all three targets.** Not 42, not 82. See the arithmetic table above.
- Must pass specifically in **header** mode: `get-vanilla-with-session-token`, `get-header-value-trim`, `get-vanilla-query-order-encoded`, `get-utf8`, `get-space-normalized`, `post-sts-header-before`, `post-sts-header-after`.
- Must pass specifically in **query** mode: `get-vanilla`, `get-vanilla-with-session-token` (the token is signed *inside* the canonical query, `X-Amz-SignedHeaders=host` alone), `get-utf8`, `get-vanilla-query-order-encoded`.
- **`get-percent-single-encoded` and the seven `*-unnormalized` cases pass in BOTH header and query mode.**
- New unit test: the key `a/../b` produces two **different** canonical URIs under `normalizeUriPath = true` vs `false`.
- New unit test: endpoint `http://localhost:4566` produces canonical header `host:localhost:4566`; `https://dynamodb.us-east-1.amazonaws.com` produces `host:dynamodb.us-east-1.amazonaws.com`.
- `./gradlew updateLegacyAbi` re-run and committed; the `.api` / `.klib.api` diff is reviewed as public API of a published artifact. ✅ *(done — `aws-signing.klib.api` covers all three native targets)*

---

### M2 — `aws-core`: credentials, endpoints, retry, errors, transport (8.5 days)

> **STATUS: M2 IMPLEMENTED AND GREEN (2026-08-09).** `aws/aws-core` exists on branch `3.0.x`.
> **64 tests, 0 failures, identical on `jvm` and `macosArm64`**; `linuxX64` and `linuxArm64` compile
> and link. Combined with M1 the branch now carries **110 tests**.
>
> **A Kotlin/Native binary made a real, signed DynamoDB call against real AWS.**
> `[macosArm64Test] [live] signed transport round-tripped DynamoDB: HTTP 200`, plus a negative
> control in which AWS rejected a deliberately-wrong secret (`InvalidSignatureException`). That
> single line retires most of M0.5's remaining uncertainty on the *macOS* native target — Curl, TLS,
> engine byte fidelity and the default CA configuration all work without a hardcoded bundle path.
> **It does not yet cover `linuxArm64`**, where the libcrypt layer and the AL2023 CA path are still
> unproven; that stays M7's risk.
>
> **The live negative control found a real bug in M1's signer, and it is the most valuable finding
> of the milestone.** `signingKey()` cached on `(accessKeyId, date, region, service)` and **not on
> the secret**, so a second call presenting a different secret under the same access key id was
> handed the first call's key — and signed successfully with a secret the caller never supplied.
> All 74 vectors and 43 offline tests missed it, because none of them signs twice with different
> secrets under one id. Fixed by including a 16-hex-char SHA-256 digest of the secret in the cache
> key (a digest, never the secret — cache keys reach diagnostics), with a regression test in
> `SignerBehaviourTest`. **Amend the M1 task list accordingly: "keyed on (secretIdentity, region,
> service, yyyyMMdd)" is ambiguous, and reading `secretIdentity` as the access key id is wrong.**
>
> Two smaller corrections to this section's assumptions, both found by compiling:
> 1. **Ktor 3 has no `URLBuilder.encodedPath` setter.** The path is set via `encodedPathSegments`,
>    whose list is joined verbatim — which is the "never re-encode" property this section requires.
>    The defensive assertion compares the rejoined segments.
> 2. **Ktor reports an unspecified port as `0`, not the scheme default**, and routes `Content-Type`
>    onto the body rather than the header map. Both were caught by tests here rather than in
>    production: the first would have signed `host:0` for every default-port endpoint, and the
>    second would have let Ktor invent a `Content-Type` we never signed — the same header-mutation
>    failure class recorded in M1.
>
> **Amended by M3 (2026-08-10): `AwsServiceException` gains `rawErrorBody: ByteArray?`,** populated
> by the transport. Some AWS errors carry structured payload past a code and a message — DynamoDB's
> `ConditionalCheckFailedException` returns the item that failed the condition, its
> `TransactionCanceledException` a positional reason list — and reducing the response to
> `ErrorDetails` at the transport destroyed all of it. Carrying the bytes keeps error-*schema*
> knowledge in the service module, which is the same split `AwsErrorParser` already makes. This also
> supersedes `transactionCancellationReasons`, which returned bare `List<String>`; the DynamoDB
> module now parses the full `CancellationReason` including its `Item`.
>
> Still outstanding from this section: `linuxX64Test` *execution* (needs a Linux host). The `.api`
> dump is done — see the M0 task; `callRaw`'s signature is frozen in `aws-core.klib.api`
> (with the one later exception ratified 2026-08-12 — see M3.5b's STATUS).

**Tasks**:
- [ ] `aws/aws-core/build.gradle.kts` — MPP conventions, `explicitApi()`, jvm/linuxX64/linuxArm64/macosArm64. commonMain: aws-signing, ktor-client-core, kotlinx-serialization-json, kotlinx-coroutines-core. jvmMain: ktor-client-cio. nativeMain: ktor-client-curl. commonTest: `kotlin("test")`, ktor-client-mock. **No `:standards`, `:env`, `:logging`.**
- [ ] Credentials: `fun interface AwsCredentialsProvider { suspend fun resolve(): AwsCredentials }`, `StaticCredentialsProvider`, `EnvironmentCredentialsProvider`, `CredentialsProviderChain`, `CachedCredentialsProvider` (Mutex, `expireAfter = 15.minutes`, `refreshBuffer = 10.seconds`, `effectiveExpiry = min(creds.expiration, now + expireAfter)`).
- [ ] **Credentials are resolved at the top of EACH attempt**, before signing — with `maxAttempts = 4` and a 20 s backoff cap a single call can span ~60 s, longer than the refresh buffer. The expiry comparison uses the **clock-skew-corrected** time, or a skewed clock makes credentials look permanently expired.
- [ ] `expect fun platformGetEnv(name: String): String?` — JVM `System.getenv`; native `platform.posix.getenv` + `toKString` (copy the 20-line pattern from `ref-2.3.x/env/src/nativeMain/kotlin/com/steamstreet/env/env.kt`).
- [ ] Region: explicit > `AWS_REGION` > `AWS_DEFAULT_REGION` > (JVM only: `aws.region` sysprop) > throw naming all of them. Endpoint: explicit `endpoint` > `AWS_ENDPOINT_URL_<SERVICEID>` > `AWS_ENDPOINT_URL` > `https://{prefix}.{region}.amazonaws.com`. **Explicit-config-wins is non-negotiable** — four existing test sites set `endpointUrl` in code.
- [ ] `RetryPolicy`: full jitter `random(0,1) * min(20_000, base * 2^retry)`; base 25 ms transient for DynamoDB, 1000 ms for throttling; max attempts 4 for DynamoDB, 3 elsewhere. Port the classification table verbatim from aws-sdk-kotlin's `AwsRetryPolicy.kt` — 14 throttling codes (`ProvisionedThroughputExceededException`, `RequestLimitExceeded`, `ThrottlingException`, `TransactionInProgressException`, …), 3 transient codes, statuses 500/502/503/504, and **CODE BEATS STATUS**.
- [ ] **The ported table is very nearly S3-complete, which is a cost reduction — but "add nothing" is too strong and must not be written that way.** Verified against `aws-sdk-kotlin/aws-runtime/aws-http/common/src/.../retries/AwsRetryPolicy.kt:54-82`: `knownErrorTypes` is a 17-entry map already containing `SlowDown` → Throttling, `RequestTimeout` and `RequestTimeoutException` → Transient, `PriorRequestNotComplete` and `BandwidthLimitExceeded` → Throttling; `knownStatusCodes` already contains 500/502/503/504. The CODE-BEATS-STATUS rule correctly resolves S3's 503 `SlowDown` to **Throttling** (1000 ms base) rather than Transient (25 ms). **Two additions are still required:**
  - `ConditionalRequestConflict` → Transient with a short backoff base. It is **absent** from both maps (there is no 409 entry at all), yet v1 ships `PutObjectRequest.ifNoneMatch`, and AWS documents that a conflicting concurrent operation during a conditional write returns `409 ConditionalRequestConflict` and should be retried. Without this the conditional-write path arrives without its documented retry.
  - `PermanentRedirect` (301) added to the **never-retry deny-list**.
  - Keep the two questions separate and separately tested: (1) a **409 `ConditionalRequestConflict` service response IS retried**; (2) a socket reset or read timeout on a PUT carrying `ifNoneMatch`/`ifMatch` is **NOT** retried, because the retry observes a 412 for a write that actually succeeded.
- [ ] Read `x-amz-retry-after` as **MILLISECONDS**, clamped to `[computed, computed + 5000]`. Not the RFC `Retry-After` seconds header.
- [ ] **Never-retry deny-list, consulted BEFORE the code table**: `TransactionCanceledException`, `IdempotentParameterMismatchException`, `ValidationException`, `ConditionalCheckFailedException`, `ResourceNotFoundException`. This means a later edit to the retryable-code table cannot make an atomic write replayable.
- [ ] **Transport-exception classification** (absent from the draft; this is the decision that determines whether `increment()` double-applies):
  - *Provably-not-sent* — DNS resolution failure, connect refused/timeout, TLS handshake failure → retryable for every operation.
  - *Ambiguous* — socket read timeout, connection reset after write, incomplete response → retryable **only** for idempotent operations: GetItem, Query, Scan, BatchGetItem, TransactGetItems, DescribeTable, BatchWriteItem, TransactWriteItems *with a stable ClientRequestToken*, and **PutItem/DeleteItem only in their plain form**. **NOT UpdateItem.** `MutableItem.kt:181-188` emits an unconditional numeric `ADD` and `:191-210` emits `list_append`; `dynamokt-exposed/.../TableOperations.kt:289` emits `ADD` too. All three double-apply on a retry after the bytes reached DynamoDB.
  - **AMENDED 2026-08-13 — "PutItem and DeleteItem are idempotent" is a statement about an operation, and safety is a property of the *request*.** Both types expose `ConditionExpression` and `ReturnValues`, and either one breaks the replay: a conditional create that landed fails its own replay's condition and reports `ConditionalCheckFailedException` for a write that succeeded, and `ReturnValues=ALL_OLD` returns the prior item on the first attempt and the just-written item on the replay. So the safety value must be **computed from the request** — `writeSafety(conditionExpression, returnValues)`, NOT_IDEMPOTENT when a condition is present or `ReturnValues` reads prior state (`ALL_OLD`, `UPDATED_OLD`), IDEMPOTENT otherwise. **CreateTable and DeleteTable are unconditionally NOT_IDEMPOTENT** on the identical ambiguity: the replay reports `ResourceInUseException` / `ResourceNotFoundException` for control-plane work the first attempt completed. Read Risk 7 for the full failure narrative; `AmbiguousWriteSafetyTest` is the coverage, verified to fail against the operation-name version.
  - **S3 operations, on the same rule**: `GetObject`, `HeadObject` and `DeleteObject` are idempotent, so ambiguous mid-flight failures are retryable. `PutObject` is a full overwrite with no read-modify-write, so it joins the retryable-ambiguous list on the same footing as `PutItem` — **EXCEPT when `ifNoneMatch` or `ifMatch` is set**, which must be excluded. This is safe only because the v1 body is a re-readable `ByteArray`; put a source comment saying a future streaming overload must **not** inherit it.
  - `DynamoDbConfig.retryAmbiguousWrites: Boolean = false` escape hatch. Record as an explicit behavioural difference from the AWS SDK in the M5b PR.
- [ ] Token bucket circuit breaker: capacity 500, no time-based refill, 14 tokens per transient retry, 5 per throttling retry, refund on a retry that succeeds, +1 on clean first-try success, no retry at zero. ~20 lines.
- [ ] **Aggregate retry deadline**: `DynamoDbConfig.maxTotalRetryDuration: Duration = 25.seconds`. If `now + computedDelay > deadline`, stop and throw the last error rather than sleeping. Four attempts at a 20 s cap plus a clamped `x-amz-retry-after` can consume ~60 s inside a function with a 30 s timeout, converting a retryable throttle into an unreported Lambda timeout. Always throw the *last* error with prior attempts attached via `addSuppressed`.
- [ ] **Error parsing becomes a STRATEGY, not a concrete class.** `interface AwsErrorParser { fun parse(status: Int, headers: Map<String,String>, body: ByteArray?): ErrorDetails }` with `ErrorDetails(code, message, requestId)`, plus two implementations: `AwsJsonErrorParser` (preserving **every** rule below verbatim — they are hard-won and must not be diluted) and `RestXmlErrorParser`.

  The abstraction survives a second protocol intact because everything downstream — the retryable-code table, the never-retry deny-list, the clock-skew triggers — keys on a plain `String` code. `RestXmlErrorParser` is a ~60-line scanner over `<Error>`: read the first `<Code>` and `<Message>` text nodes, unescape the five XML entities, tolerate unknown children (the Intelligent-Tiering `InvalidObjectState` variant adds `<StorageClass>` and `<AccessTier>`), degrade to `code = null` on a malformed or empty body rather than throwing out of the error path. **No XML library.**
- [ ] **NEW (~0.25 d) — `AwsServiceException` in `aws-core`**, carrying `code`, `message`, `statusCode`, `requestId` and `extendedRequestId`, populated by the transport from `x-amz-request-id` / `x-amzn-requestid` and `x-amz-id-2` for **all** services. The plan currently captures no request identifiers anywhere. AWS Support will not act on an S3 report without both, and DynamoDB benefits equally. DynamoDB's and S3's typed exceptions both extend it — see the M3 edit.
- [ ] `AwsJsonErrorParser`: read the code from (1) header `X-Amzn-Errortype`, (2) top-level body `code`, (3) top-level body `__type` — **DEPTH-1 ONLY, case-sensitive**. DynamoDB nests `__type` inside `ErrorDetails[]` from a different namespace, and `TransactionCanceledException` bodies carry `CancellationReasons[].Code` whose values include `ThrottlingError` and `ProvisionedThroughputExceeded`. A recursive or case-insensitive search classifies a permanently-failed atomic transaction as retryable. `CancellationReasons` is parsed separately and never feeds the classifier. Sanitize as `code.substringAfter("#").substringBefore(":")`.
- [ ] Error message from any of `message` / `Message` / `errorMessage` (DynamoDB uses capital M) or the `x-amzn-error-message` header.
- [ ] Clock-skew correction (~40 lines): on `RequestTimeTooSkewed` / `RequestExpired` / `RequestInTheFuture`, or on `SignatureDoesNotMatch` / `InvalidSignatureException` / `AuthFailure` where |response `Date` header − local time| ≥ 4 minutes, store the offset and retry once with corrected signing time.
- [ ] **`AwsServiceClient.callRaw` must be GENERALIZED in M2, not retrofitted after M3.** The draft's `callRaw(operation, body)` is awsJson-shaped: it presumes POST + `X-Amz-Target` + a JSON body and cannot express GET-with-path-and-query or a raw octet-stream body. Replace it with `callRaw(method, path, query, headers, body)` plus a pluggable protocol object supplying the target header and content type. Specifying this here is what stops S3 forcing retroactive surgery on the central class *after* M3 has already validated against it.
  - Sign the exact bytes the engine will send, from a resolved URL string with headers passed explicitly. Body as a pre-materialized `ByteArray` so the engine cannot chunk it. Send `accept-encoding: identity`. Add `amz-sdk-invocation-id` (stable across attempts) and `amz-sdk-request: attempt=n; max=N`.
  - **The path is passed already-encoded and is never re-encoded by the transport.** The byte-identical string used to build the outbound URL must be the string used to build the canonical request. Add a defensive assertion: after building the Ktor request, re-read `url.encodedPath` and assert it equals what was signed. That assertion catches every future Ktor upgrade that changes encoding behaviour — the same class of breakage the `user-agent` exclusion already worries about, and the single failure mode behind Risk 5 and Risk 25.
  - **Freeze this signature at the end of M2 with its own committed `.api` dump**, so M3 and M3.5 can be developed in parallel against a fixed seam. See §5's parallelization caveats.
- [ ] `expect fun awsHttpClient(caInfo: String?): HttpClient` — jvm actual CIO; native actual Curl. **`caInfo` defaults to `null`** (libcurl then uses the system trust store); the AL2023 path `/etc/pki/tls/certs/ca-bundle.crt` is set only by the lambda-native bootstrap. Hardcoding the Linux path breaks macosArm64 at runtime in a way MockEngine tests never catch. The config must also accept an injected `HttpClient` so the Lambda runtime's already-configured Curl client is reused rather than duplicated.
- [ ] **`followRedirects = false` on the shared `awsHttpClient()`. This is an M2 task for every service, not an M3.5b task for S3.** Ktor follows `Location` by default. SigV4 signs `host`, so an auto-followed 301/307 **replays an `Authorization` header computed over the previous authority to a different host** — the caller sees an opaque `SignatureDoesNotMatch` instead of the typed, actionable exception, and the credential scope plus signature are forwarded off-origin. S3 documents two live cases: a **307 Temporary Redirect** for buckets in pre-2019-03-20 Regions reached via the legacy global endpoint, and a **301 Permanent Redirect** for path-style requests to the wrong regional endpoint. This plan's own path-style fallback (dotted buckets, endpoint override) is precisely the configuration that produces them, and `S3.PermanentRedirectException` is unconstructible without this flag.
  - Map 301/307 to typed exceptions that read `x-amz-bucket-region` and the `<Endpoint>` / `<Region>` elements of the error body, so the message names the correct region.
  - Security test: no `Authorization` header and no `X-Amz-*` query parameter is ever sent to a host other than the one signed.
- [ ] Source comment at the retry loop explaining why it is not Ktor's `HttpRequestRetry`.

**Verification**: `./gradlew :aws:aws-core:jvmTest :aws:aws-core:macosArm64Test` green; CI additionally runs `linuxX64Test`. Hermetic MockEngine tests must assert:
- a 400 carrying `ProvisionedThroughputExceededException` IS retried; a 400 carrying `ValidationException` is NOT;
- a `TransactionCanceledException` body containing `"Code": "ThrottlingError"` in a reason produces **zero** retries;
- the token bucket blocks a retry after depletion;
- `x-amz-retry-after: 3000` produces a ~3 s delay, not 3000 s;
- a nested `__type` inside `ErrorDetails` does not shadow the top-level one;
- a capital-M `Message` is read;
- an UpdateItem is NOT retried on a mid-flight IO failure while a Query IS;
- `EnvironmentCredentialsProvider` + `CachedCredentialsProvider` refresh correctly against a fake clock and fake env reader;
- the secret string never appears in any `toString()` or exception message.
- **One real HTTPS GET through `awsHttpClient()` on macosArm64**, proving the default CA configuration works off-Linux.
- **restXml / S3 additions to the MockEngine suite**:
  - a 503 carrying `<Error><Code>SlowDown</Code></Error>` IS retried **and uses the THROTTLING backoff base (1000 ms), not the transient one (25 ms)**;
  - a 404 carrying `<Error><Code>NoSuchKey</Code></Error>` is NOT retried;
  - a 409 carrying `<Error><Code>ConditionalRequestConflict</Code></Error>` IS retried;
  - a malformed or empty XML error body degrades to `code = null` plus status-based classification instead of throwing out of the error path;
  - a `HEAD` 404 with **no body at all** still produces a typed exception;
  - `x-amz-request-id` and `x-amz-id-2` both reach the thrown `AwsServiceException`;
  - a 301 with a `Location` header is **surfaced, not followed**, and no `Authorization` header is emitted to the redirect target.
- `:aws:aws-core:updateLegacyAbi` committed, and `AwsServiceClient.callRaw`'s signature is frozen in it (see §5). ✅ *(done — `aws-core.klib.api:112`; broken once in M3.5b for `inspectBeforeBody`, ratified 2026-08-12)*

---

### M3 — `aws-dynamodb`: 12 ops, differential harness, SDK adapter, live smoke (10.5 days)

> **STATUS: M3 CODE-COMPLETE (2026-08-10); one exit criterion blocked on IAM.** `aws/aws-dynamodb`
> and `aws/aws-dynamodb-sdk-adapter` exist on branch `3.0.x`. **81 jvm / 26 macosArm64 tests in
> `aws-dynamodb`, 0 failures**, plus **24 in the adapter**; `linuxX64` and `linuxArm64` link. The
> branch now carries **215 jvm / 134 macosArm64 tests across four modules**, all green.
>
> **NEW REQUIREMENT FROM JON, and it changed the design: the library must be extensible.** A
> consumer who needs an operation this library does not ship must be able to add it as an extension
> function, without forking and without access to anything `internal`. Implemented as a new
> Decision (see below) rather than retrofitted, because it constrains the public API and the `.api`
> dumps are about to freeze.
>
> **Decision 18 — extension is a first-class, tested property, at two levels.**
> - *A whole new service*: already possible — `AwsServiceClient` and `AwsProtocol` are public and
>   neither is DynamoDB-specific. Someone can add SQS against `aws-core` alone.
> - *A new operation on an existing service*: `aws-core` gains `awsJson` (the exact `Json`
>   configuration, since `encodeDefaults = false` / `explicitNulls = false` are wire-correctness
>   requirements, not style) and `suspend fun AwsServiceClient.callJson(...)`. `DynamoDb` exposes
>   `val client: AwsServiceClient`. The twelve built-in operations have **no privileged access** —
>   each is a `callJson` on that same object.
> - *Rejected*: keeping the transport private and adding operations by request. It makes every gap
>   in coverage a blocking upstream dependency, which is precisely the position this whole project
>   exists to escape.
> - **`DynamoDbExtensibilityTest` adds a 6th operation (`DescribeLimits`) from outside the client's
>   own file, using only public API, and asserts it is signed, targeted and *retried* exactly like a
>   built-in call.** If `client` were ever made internal, that test stops compiling — a compile-time
>   guard on an API property that is otherwise easy to erode silently.
>
> **Done:** `AttributeValue` (all 10 variants, `asX()`/`asXOrNull()` matching the SDK, `N` as an
> exact decimal String) and its DynamoDB-JSON codec, with exit criterion (b) green including `B`,
> `Bs`, `Null` and `Ns` — the four a repo-wide grep showed nothing previously tested; DTOs for 10
> operations; `DynamoDb` / `DynamoDbConfig` / `DefaultDynamoDb`; the typed exception hierarchy
> re-parented onto `AwsServiceException`; the empty-collection invariant with `orNullIfEmpty()`;
> per-operation retry safety (`UpdateItem` NOT_IDEMPOTENT, reads IDEMPOTENT), asserted — later
> corrected to per-**request** safety, since a conditional or `ALL_OLD` PutItem/DeleteItem is not
> replayable either; see Risk 7 and `writeSafety`; exit
> criterion (c) — `TransactWriteItems` reuses one 36-char `ClientRequestToken` across retries;
> paginators terminating on an **empty** `LastEvaluatedKey`; `batchGetAll` / `batchWriteAll` fixing
> the two live data-loss bugs.
>
> **THE REQUEST-SIDE DIFFERENTIAL HARNESS IS GREEN — 12 tests, exit criterion (a), request half.**
> Every one of the ten implemented operations serializes to JSON structurally identical to the real
> `aws.sdk.kotlin` client's, including a `PutItem` carrying **all ten `AttributeValue` variants** and
> an `UpdateItem` whose omitted expression maps match the SDK's omissions exactly. Built on
> `HttpInterceptor.readBeforeTransmit`, which captures the fully-serialized request and throws a
> sentinel before any network I/O — public, version-stable API, unlike implementing
> `HttpClientEngine` over an `@InternalApi` base. `aws.sdk.kotlin` is a **jvmTest-only** dependency
> and never reaches `commonMain`.
>
> A harness gotcha worth recording: the SDK's builder-DSL forms (`client.getItem { }`) are
> **extension functions, not members**. Without importing them the compiler silently binds to the
> `getItem(request)` overload and the lambda becomes a `Function0` — a confusing cascade of
> "unresolved reference" errors pointing at the wrong line.
>
> **CONTROL PLANE DONE.** `CreateTable` (with its 12-type closure), `DescribeTable` and
> `DeleteTable` are implemented and differential-tested — including a `CreateTable` carrying GSIs,
> key schemas, an `INCLUDE` projection and a stream specification, which is the most structurally
> complex request in the set. **14 differential tests, all green.** `TableDescription` is narrow as
> planned, and `tableStatus` is a `String` rather than an enum so a status AWS adds later is not a
> deserialization crash.
>
> **DTOs are `data class`es (Jon's call, and it is a correctness argument, not a style one).**
> `copy()` removes a whole bug class: `queryPaged`/`scanPaged` previously rebuilt the request field
> by field, so any field added to `QueryRequest` later would have been silently dropped on every
> page after the first. Same treatment applied to `transactWriteItems`' token injection and
> `batchGetAll`'s per-chunk request. Two regression tests pin the properties — both transact items
> survive token injection, and 150 keys chunked to 100+50 carry `ConsistentRead` and the projection
> on **both** chunks, not just the first. Cost, stated in the model's KDoc: adding a constructor
> parameter is binary-incompatible via the generated `copy$default` — already true of the
> constructor, so it changes nothing about versioning.
>
> **THE RESPONSE-SIDE DIFFERENTIAL HARNESS IS GREEN — 18 tests, exit criterion (a) closed.**
> `ResponseDifferentialTest` feeds one canned AWS body to both deserializers and compares the
> resulting models structurally. The corpus covers everything this section asked for: a GetItem
> carrying all ten variants including `{"S":""}`, `{"M":{}}`, `{"L":[]}`, a 38-digit `N`, a two-blob
> `BS` and an explicit `{"NULL":true}`; four-deep nested M/L; a miss; Query with, without and with an
> **empty** `LastEvaluatedKey`; Scan; PutItem/UpdateItem/DeleteItem `Attributes`; BatchGetItem with
> `UnprocessedKeys`; BatchWriteItem with `UnprocessedItems`; TransactGetItems with a missing slot;
> DescribeTable and CreateTable across the full index/stream closure; and both error bodies.
>
> **The SDK side is driven by a JDK `HttpServer` on an ephemeral loopback port with the client's
> `endpointUrl` aimed at it** — not an interceptor and not an `HttpClientEngine` over an
> `@InternalApi` base. That runs the SDK's *real* deserialization path end to end, headers included,
> and needs no internal API at all. Both sides are rendered to `JsonElement` by **separately
> hand-written** functions rather than by `AttributeValueSerializer.toJson`: checking our decoder
> with our own encoder would let a symmetric mistake cancel itself out.
>
> **The response harness immediately paid for itself — it found two fields that were silently
> dropped, which is exactly the failure class the request harness cannot see.** A wrong response
> mapping does not error; it yields `null`.
> 1. **`ConditionalCheckFailedException.item` was never populated** — `mapErrors` passed a literal
>    `null`. DynamoDB returns the item that lost the race inside the *error body*, and it exists
>    nowhere else, so a caller had no way to recover it.
> 2. **`TransactionCanceledException.cancellationReasons` was declared `List<String>` and always
>    empty**, while `Model.kt`'s `CancellationReason` (code/message/item) sat unused. It is now
>    `List<CancellationReason>` and positional — one entry per `TransactItems` entry, `"None"`
>    included — because filtering the successes out misaligns every index after the first failure.
>
> Fixing both needed the raw error bytes, which the transport had already reduced to a code and a
> message. **`AwsServiceException` therefore gains `rawErrorBody: ByteArray?`** (populated in
> `AwsServiceClient.toException`). That keeps error-*schema* knowledge in the service module where
> it belongs rather than teaching protocol-agnostic `aws-core` about DynamoDB's shapes — the same
> split `AwsErrorParser` already uses. `aws-core`'s now-unused `transactionCancellationReasons`
> helper is superseded by the richer DynamoDB-side parse.
>
> **A third gap, found by trying to write the live test:** `ReturnValuesOnConditionCheckFailure` was
> declared but **no request DTO carried it**, which made the two fixes above unreachable in
> production — DynamoDB only returns the losing item when asked. The field is now on
> `PutItemRequest`, `UpdateItemRequest`, `DeleteItemRequest`, `TransactPut`, `TransactUpdate`,
> `TransactDelete` and `ConditionCheck`, with two new differential tests pinning it against the SDK.
>
> **`SdkBackedDynamoDb` IS BUILT — `aws/aws-dynamodb-sdk-adapter`, jvm-only, 15 green tests.**
> Decision 3's landability argument is now real: M5a can swap the type against behaviour that is
> still byte-for-byte the AWS SDK's, and M5b's flip is a one-line revert. It maps all thirteen
> operations plus the whole enum and `AttributeValue` closure, and re-throws the SDK's exceptions as
> **this library's**, so consumer `catch` blocks are written once and do not change again at the flip.
>
> Three findings from building it:
> 1. **Decision 18 collides with Decision 3 and the plan did not notice.** `DynamoDb.client` is part
>    of the interface, but an SDK client contains no `AwsServiceClient` to hand back. Resolved by
>    building one lazily from the delegate's *own* resolved region, credentials and endpoint — with a
>    bridge from the SDK's `CredentialsProvider` to ours, which as a bonus lets the seam inherit
>    profile/SSO/`credential_process` sources `aws-core` deliberately does not carry. An extension
>    function therefore survives the swap; nothing is constructed unless `client` is read.
> 2. **smithy-kotlin's `ServiceException.message` appends `", Request ID: …"`.** Using it verbatim
>    would make the identical error read differently depending on which implementation produced it —
>    the one observable difference this class exists to eliminate. The adapter reads
>    `sdkErrorMetadata.errorMessage` instead and carries the id in its own field.
> 3. **smithy-kotlin reads the request id from `x-amz-request-id`** (`X_AMZN_REQUEST_ID_HEADER` in
>    `awsprotocol/ResponseUtils.kt`), *not* the `x-amzn-RequestId` DynamoDB actually sends. Our
>    transport reads either. Noted because a fixture that emits one spelling only proves half the path.
>
> **Live DynamoDB smoke test (exit criterion (d)): WRITTEN AND RUNNING, BUT NOT YET CLOSED — blocked
> on IAM, not on code.** `LiveDynamoDbTest` covers create → put → get → query → transactWrite/Get →
> a failed condition returning the losing item → `batchWriteAll`/`batchGetAll` chunking 30 items,
> and deletes its scratch table in a `finally`. It uses `runBlocking`, not `runTest`, because
> `runTest`'s virtual clock skips `delay` and would turn the wait-for-ACTIVE loop into an
> unthrottled poll against a real AWS API.
>
> **Verified 2026-08-10: in account `443844975891`, neither `ai-vegasful-test` (`AgentReadOnly`) nor
> `ai-vegasful-test-deploy` (`AgentDeploy`) is granted `dynamodb:CreateTable`.** Both signed
> perfectly and were refused by IAM. To close (d), either grant CreateTable/DeleteTable on
> `arn:…:table/awskt-live-smoke-*`, or set `AWSKT_LIVE_TABLE=<name>` to a scratch table with a
> `String` `pk` hash key and `String` `sk` range key — the tests then create and delete nothing.
> Without either they self-skip loudly rather than fail, because a permission refusal is not a
> client defect.
>
> **What the failed run nonetheless proved, and it is not nothing.** An `AccessDeniedException`
> naming our own IAM principal is only reachable *after* AWS has verified the signature — a bad
> signature comes back `InvalidSignatureException` and never reaches an authorization decision. So
> `awsAuthenticatesUsEvenWhenItRefusesTheAction` asserts exactly that, and passes live today: real
> AWS authenticated a request `aws-dynamodb` signed, and the typed error and request id came back
> through the full parse path. The signature oracle is closed for this module; only the *write*
> path remains unexercised against real AWS.
>
> **LOCALSTACK / TESTCONTAINERS SUITES ADDED (Jon's call, 2026-08-10) — 16 + 9 tests, green.**
> This oracle was scheduled for M5a, on the *existing* 100 tests. Pulling it forward to M3 was
> right, and the reason is visible in the plan's own validation table: until now nothing could say
> the API **works**, only that it was **shaped right**. A `MockEngine` answers whatever it was told
> to answer and a byte-differential is satisfied by two clients being wrong identically. LocalStack
> has an opinion — it rejects `"ExpressionAttributeValues":{}`, enforces the 25-item
> `BatchWriteItem` cap, evaluates condition expressions and really applies `ADD`.
>
> - `LocalStackDynamoDbTest` (16, in `aws-dynamodb/src/jvmTest`) — control plane, all ten variants
>   round-tripped through a validating service, `SET`/`ADD`/`REMOVE`, the empty-collection invariant
>   *accepted by the service* rather than merely absent from our JSON, real multi-page pagination at
>   `Limit=1`, GSI query, filter + projection + descending sort, 60-item batch chunking, a four-kind
>   transaction, a cancelled transaction's positional reasons, and an extension operation
>   (`ListTables`) added from outside the library and run against a real service.
> - `LocalStackParityTest` (9, in the adapter) — the same operations through **both**
>   implementations, results compared. This is the test that makes M5a landable: the split rests
>   entirely on the claim that a consumer cannot tell `SdkBackedDynamoDb` from `DefaultDynamoDb`,
>   and nothing else in the project tested it. Non-vacuity confirmed by mutation: mapping the SDK's
>   `Null` to `Bool` in `Conversions.kt` fails it immediately.
>
> **It found two things on its first run, which is the argument for having pulled it forward.**
> 1. **DynamoDB's `SS`/`NS`/`BS` are genuinely unordered.** `NS: ["1","-2.5"]` came back as
>    `["-2.5","1"]`, and the assertion failed on ordering alone. **RESOLVED — Jon, 2026-08-10: the
>    three set types compare as sets.** See Decision 2's amendment below.
> 2. **LocalStack 3.0 silently ignores `ReturnValuesOnConditionCheckFailure`** — the feature landed
>    in DynamoDB in December 2023 and the image predates it, so the conditional-write test fails in a
>    way that looks exactly like a client bug. The new suites pin **4.0**. The three existing suites
>    (`dynamokt`, `dynamokt-exposed`, `test`) still use 3.0; M5a should bump them.
>
> **`buildSrc/steamstreet-common.container-test-conventions.gradle.kts` created** — an M0 task,
> pulled forward because this would have been the fourth verbatim copy of the OrbStack docker-socket
> block. Applied to the two new modules only; switching `dynamokt`, `dynamokt-exposed` and `test`
> over touches three working integration suites and stays M0's job. Note it uses
> `tasks.withType<Test>().configureEach`, not `tasks.test` — a KMP module has `jvmTest`, not `test`.
>
> **What LocalStack does NOT do, restated because a green run invites the opposite reading:** it
> never verifies a signature (`DummyKey`/`DummySecret`, IAM enforcement off), so it cannot close
> exit criterion (d). It does exercise one thing real AWS cannot — LocalStack listens on an
> **ephemeral port**, so every request signs and sends `host:port` rather than a bare host, which is
> Risk 3, a bug that passes every default-port fixture and fails every real request.
>
> **Still outstanding from this section:** exit criterion (d)'s write path (IAM, above), and
> `linuxX64Test` *execution* (needs a Linux host; the binary links here). The `.api` dumps are done
> — `aws-dynamodb.klib.api` covers `[linuxArm64, linuxX64, macosArm64]`.

**Build the harness FIRST (days 1–2), not last.** Every DTO is then validated as it is written.

**Tasks**:
- [ ] Create `aws/aws-dynamodb` (jvm/linuxX64/linuxArm64/macosArm64) and `aws/aws-dynamodb-sdk-adapter` (jvm only); add both to `settings.gradle.kts`.
- [ ] **Wire-differential harness, request side**, in `aws/aws-dynamodb/src/jvmTest`: a smithy-kotlin `Interceptor` implementing `readBeforeTransmit(context)` installed on the real `aws.sdk.kotlin` `DynamoDbClient`, capturing the fully-signed `HttpRequest` and throwing a sentinel to short-circuit. Assert structural JSON equality of the body via `Json.parseToJsonElement`, **and** equality of the request line + header-name set. Public API, version-stable across SDK bumps.
- [ ] **Wire-differential harness, response side** (this half was missing from the draft): hand-author ~15 canned AWS response bodies, feed each to both the real SDK deserializer and ours, assert structural equality of the resulting model. Corpus must include: GetItem with all 10 `AttributeValue` variants including `{"S":""}`, `{"M":{}}`, `{"L":[]}`, deeply nested M/L, a 38-digit `N`, a `BS` with two blobs, an explicit `{"NULL":true}`; Query with, without, and with an **empty** `LastEvaluatedKey`; BatchGetItem with `UnprocessedKeys`; `ConditionalCheckFailedException` carrying `Item`; `TransactionCanceledException` with mixed `None`/`ConditionalCheckFailed` reasons.
- [ ] `@Serializable` DTOs for 12 operations with `@SerialName` PascalCase wire names; `Json { encodeDefaults = false; explicitNulls = false; ignoreUnknownKeys = true }`. Operations: GetItem, PutItem, UpdateItem, DeleteItem, Query, Scan, BatchGetItem, BatchWriteItem, TransactWriteItems, TransactGetItems, DescribeTable, **CreateTable**. Supporting types: `KeysAndAttributes`, `WriteRequest`, `DeleteRequest`, `PutRequest`, `TransactWriteItem` (Put/Update/Delete/ConditionCheck), `TransactGetItem`, `Get`, `ItemResponse`.
- [ ] **CreateTable closure** (~10 small types, ~150 lines): `AttributeDefinition`, `KeySchemaElement`, `KeyType`, `ScalarAttributeType`, `BillingMode`, `ProvisionedThroughput`, `GlobalSecondaryIndex`, `LocalSecondaryIndex`, `Projection`, `ProjectionType`, `StreamSpecification`, `StreamViewType`.
- [ ] **Empty-collection invariant** (the draft's "zero serialization code" claim is false here): every optional collection/map field on a request DTO is typed nullable with a `null` default, and construction sites normalize `emptyMap()`/`emptyList()` to `null` via a `Map<K,V>?.orNullIfEmpty()` helper. With `encodeDefaults = false`, an assigned `emptyMap()` emits `"ExpressionAttributeValues":{}`, which DynamoDB rejects. The existing code guards this by hand at `MutableItem.kt:453-458`, `dynamokt/Query.kt:253-259`, `dynamokt-exposed/Query.kt:556-561`. Harness cases: Query with no filter and no projection; UpdateItem with no expression names/values; BatchGetItem `KeysAndAttributes` with no projection.
- [ ] Enums as `@Serializable` with PascalCase members and `@SerialName` SCREAMING_SNAKE wire forms — `ReturnValue` (None/AllOld/UpdatedOld/AllNew/UpdatedNew), `ReturnValuesOnConditionCheckFailure`, `AttributeAction` (Put/Delete/Add). PascalCase member names preserve every existing call site.
- [ ] Project-owned `TableDescription`: `tableName`, `tableStatus`, `itemCount`, `tableSizeBytes`, `keySchema`, `attributeDefinitions`, `globalSecondaryIndexes` (narrow), `streamSpecification`, `latestStreamArn`. Verified: `describeTable()` has **zero** callers anywhere in the repo — the only occurrences are its own declaration at `DynamoKtSession.kt:25-26`.
- [ ] `interface DynamoDb : AutoCloseable`; `class DynamoDbConfig`; `fun DynamoDb(config: DynamoDbConfig.() -> Unit): DynamoDb`; `internal class DefaultDynamoDb` wiring `AwsServiceClient` with signingName/endpointPrefix `dynamodb`, `AwsJsonProtocol("DynamoDB_20120810", "1.0")`, maxAttempts 4.
- [ ] **`SdkBackedDynamoDb`** in `aws/aws-dynamodb-sdk-adapter` (~250 lines): implements `DynamoDb` by delegating to `aws.sdk.kotlin.services.dynamodb.DynamoDbClient`, converting between our DTOs and the SDK's builders. This is what makes M5a landable.
- [ ] Typed exceptions: `ConditionalCheckFailedException` (carrying `item`), `TransactionCanceledException` (with `cancellationReasons`; `code` is **non-nullable** — DynamoDB returns the literal string `"None"` for a successful item — and `message` is nullable), `IdempotentParameterMismatchException` (explicitly non-retryable), `ProvisionedThroughputExceededException`, `ResourceNotFoundException`, `ValidationException`; fallback `DynamoDbException(code, message)`.
- [ ] **(+0.25 d) Re-parent the whole hierarchy onto `aws-core`'s `AwsServiceException`** (introduced in M2) so every DynamoDB exception carries `code`, `statusCode`, `requestId` and `extendedRequestId`, and populate `requestId` from `x-amzn-requestid` on the DynamoDB response path. **Do this in M3, not afterwards** — `aws-dynamodb` is a published artifact and re-parenting a public exception hierarchy later is an API revision. This is the same argument that puts the S3 signer flags in M1 rather than retrofitting them, applied to the base class M2 introduces.
- [ ] Paginators: `DynamoDb.queryPaged(request): Flow<QueryResponse>`, `scanPaged`, `Flow<QueryResponse>.items()`. Termination must be `lastEvaluatedKey?.takeIf { it.isNotEmpty() }` — DynamoDB can return an empty map and a `!= null` check loops forever. Emit BEFORE terminating so the last page is delivered.
- [ ] `suspend fun DynamoDb.batchGetAll(...)` looping `UnprocessedKeys` with exponential backoff. Fixes a live data-loss bug: `DynamoKtSession.kt:109` reads only `.responses?.get(table)` and silently returns incomplete results under throttling or the 16 MB cap. BatchGetItem is not `@paginated` in the Smithy model, so there is no SDK paginator to inherit.
- [ ] `suspend fun DynamoDb.batchWriteAll(...)` chunking to **25** and looping `UnprocessedItems`. Mirror-image bug: `DynamoKtSession.kt:163-188` `queryDelete` collects the entire query result with **no chunking** into a single `batchWriteItem` and discards the response — today it throws `ValidationException` on >25 items and silently under-deletes on a throttled batch.
- [ ] **`ClientRequestToken` is materialized in `DefaultDynamoDb.transactWriteItems` BEFORE entering the retry loop** and reused verbatim for every attempt. `Uuid.random().toString()` (exactly 36 chars — do not prefix it; DynamoDB's max is 36). Without this, retrying `TransactionInProgressException` — which the retry policy explicitly enables — actively makes things worse. Document that application-level retries (`standards/src/jvmMain/.../Retry.kt`) generate a fresh token and are therefore not idempotent.
- [ ] **Live AWS smoke test**, gated on an env var, skippable locally but **mandatory in the M3 exit criteria**: against a scratch on-demand table in real AWS, run create/put/get/query/transactWrite with real credentials.

**Verification**:
- (a) `./gradlew :aws:aws-dynamodb:jvmTest` — request AND response differential harness passes for all 12 operations.
- (b) All 10 `AttributeValue` variants round-trip with exact equality (hard exit criterion, not a LocalStack smoke test — a repo-wide grep confirms **zero** existing tests touch `AttributeValue.B`, `.Bs`, `.Null` or `.Ns`).
- (c) MockEngine test: attempt 2 of a `TransactWriteItems` carries the identical `ClientRequestToken` as attempt 1.
- (d) **Live AWS smoke test green.** A `SignatureDoesNotMatch` here fails the milestone.
- (e) `./gradlew :aws:aws-dynamodb:macosArm64Test` compiles and runs the non-container tests natively.

---

### M3.5a — `aws-s3` module, S3 endpoint/key encoding, presigned URLs (4.5 days)

**Goal**: a presigned GET and PUT URL that is byte-identical to the AWS SDK's *including* `X-Amz-Signature`, and that an unauthenticated HTTPS client actually resolves against real S3. No HTTP transport, no error parsing and no retry loop is involved. This milestone deliberately isolates the two highest-uncertainty pieces of the S3 slice — **S3-mode key encoding and endpoint/authority derivation** — behind the cheapest oracle in the project: a URL you can `curl`.

The ordering rationale is Decision 3's, applied to a new problem. If S3-mode signing is wrong you find out on day 2 from a byte-diff against the SDK, not on day 8 from an opaque 403. M3.5a is also independently valuable: a presigner is a shippable capability on its own and needs no transport.

**There is no signer work in this milestone.** Query-string signing is entirely M1's (see the note at the top of M1). M3.5a consumes a finished signer.

**Tasks**:
- [x] Create `aws/aws-s3` with `steamstreet-common.multiplatform-library-conventions`, `explicitApi()`, targets `jvm/linuxX64/linuxArm64/macosArm64`. Dependencies: **`aws-core` only**. NOT `:dynamo`, NOT `:standards`/`:env`/`:logging`. Add `include(":aws:aws-s3")` to `settings.gradle.kts`. Commit the `.api` dump.
- [x] **PRECONDITION on M1**: verify the 37 query-mode vector assertions are green on all three targets before starting, and that `get-percent-single-encoded` and the seven `*-unnormalized` cases pass in query mode as well as header mode. If they are not, stop — the rest of this milestone is built on them.
- [x] `S3Endpoint.kt` — `resolve(bucket, region, endpointOverride, forcePathStyle) -> ResolvedS3Endpoint(scheme, authority, basePath)`. Virtual-hosted default `{bucket}.s3.{region}.amazonaws.com` with `basePath = ""`; path-style `s3.{region}.amazonaws.com` with `basePath = "/{bucket}"`. `us-east-1` emits `s3.us-east-1.amazonaws.com`, **never** the legacy global endpoint (Regions launched after 2019-03-20 return HTTP 400 from it). Honor `AWS_ENDPOINT_URL_S3` with the M2 precedence rules.
- [x] **Force path-style** when ANY of: `forcePathStyle = true`; an explicit endpoint override is set (LocalStack and MinIO are path-style); or the bucket name is not DNS-compatible over https. The dotted-bucket case is an **automatic, documented fallback**, not an error — AWS's wildcard certificate matches only buckets without dots, and path-style deprecation was delayed indefinitely, so falling back is strictly better than an opaque TLS failure.
- [x] **Bucket names: an ADDRESSING-STYLE DECISION, not a validation gate.** The modern rule (3–63 chars, lowercase `[a-z0-9.-]`, no leading/trailing `-`/`.`, not an IPv4 literal) determines *how* we address the bucket, not *whether* we will call it. `us-east-1` buckets created under the legacy rules may contain uppercase letters and underscores and be up to 255 characters; they still exist and are still reachable path-style, and AWS's own worked path-style example uses the dotted bucket `example.com`. So: DNS-compatible ⇒ virtual-hosted; dots, uppercase, underscores, length > 63 or an IP literal ⇒ **path-style**. Reject with a typed error only names that cannot be expressed in a URI authority or path at all. Unit test: an uppercase legacy name resolves to path-style, it does **not** throw. "Fail fast rather than take an opaque 400" does not justify refusing to try; a 400 from S3 is strictly more informative than a client that will not call.
- [x] **S3 key encoding**: `sigV4UriEncode(key, encodeSlash = false)`, applied **once**, with **no** path normalization. The **same byte-identical string** builds the outbound URL and the canonical request. Set it on the Ktor request as an already-encoded path so the engine cannot re-encode it (Risk 5), and assert the invariant mechanically per the M2 `callRaw` task.
- [x] **`x-id` per Decision 17**: emit `x-id=GetObject` / `x-id=PutObject` / `x-id=DeleteObject`; **none** for `HeadObject`. It is in the outbound query, therefore it is in the canonical query, therefore it is covered by `X-Amz-Signature`. This is why the differential below can be byte-identical.
- [x] `presign(PresignRequest) -> PresignedUrl` with `PayloadHash.Unsigned`, `SignedBodyHeader.NONE`, `expiresIn` **required** (no default, no zero-arg overload) and hard-capped at 604 800 s.
  - `PresignMethod` is **GET | PUT | HEAD | DELETE**, an explicit required parameter, never inferred. A URL signed for one method is rejected for the other, and inferring it from context is how a read URL becomes a write URL.
  - `PresignRequest` carries **signed response-header overrides** (`response-content-type`, `response-content-disposition`, `response-cache-control`, `response-content-encoding`, `response-content-language`, `response-expires`) as first-class fields. These are `@httpQuery` members of `GetObjectRequest` in the model, so they are canonicalized; **a caller cannot append them to the returned URL afterwards** — that yields `SignatureDoesNotMatch`. Without them the most common presigned-GET use case (a browser download with a controlled filename and content type) is impossible in v1 and cannot be added by the caller. Decide this before `aws-s3`'s `.api` is frozen.
  - No free-form header bag applied after the fact. Everything the fetcher will send is declared at presign time via `PresignRequest.signedHeaders` and appears in `X-Amz-SignedHeaders`; `PresignedUrl.signedHeaderNames` is public so the caller can be told exactly what to send. An unsigned `x-amz-*` header at fetch time is a hard 403 `AccessDenied` / `HeadersNotSigned`, and there is one non-obvious non-`x-amz-` trap: **when `Range` is signed, S3 requires `If-Range` to be signed too if present.**
  - `contentType` is a first-class parameter on `presignPutObject`, not an afterthought.
  - **Presign applies the learned clock-skew offset from shared client state, not the raw clock.** M2's skew correction is driven by *observing* a response; presign performs no round trip and so gets no signal, and AWS names clock drift as the first cause of presigned-URL `SignatureDoesNotMatch`. At minimum, read the offset; never read the raw clock directly.
- [x] **Expiry: model the unknown case as unrepresentable, do not publish a false number.** See Risk 24 — this is the highest-severity new item in the re-scope and it was nearly shipped as a silently-inert mitigation.
  - `PresignedUrl.expiry` is a **sealed value**: `Known(epochMillis)` when the credential's own expiry is known, `BoundedByUnknownSession(requestedEpochMillis)` otherwise. **Never a bare `Long` that reads as truth.**
  - When `AwsCredentials.expiresAtEpochMillis` is non-null, clamp: `min(now + expiresIn, credentialExpiry)`, surfaced as `Known`.
  - When a **session token is present and the expiry is unknown** — which, in Lambda, is *always* — cap `expiresIn` at **1 hour** (the STS `AssumeRole` default session length) by default, and require an explicit `S3Config.allowPresignBeyondUnknownSessionExpiry = true` to exceed it. The hazard is then opted into at the call site instead of being discovered when a link dies mid-life.
  - Read `AWS_CREDENTIAL_EXPIRATION` when present (the ECS / `credential_process` convention) as a **bonus, not the fix** — Lambda does not set it.
- [x] **`PresignedUrl.toString()` redacts by plain string work, not by regex.** Split on `&`, blank the value of any pair whose key is `X-Amz-Signature` **or `X-Amz-Security-Token`**, rejoin. Do **not** use a lookbehind (`(?<=X-Amz-Signature=)…`): Kotlin/Native ships a separate regex implementation from the JVM's, lookbehind is the least portable construct across KMP backends, and a silent non-match is indistinguishable from success — the security property would fail open on the only target that matters while passing every JVM test. Note the presigned URL carries the **session token in full**, which the draft redaction pattern did not touch even though the `AwsCredentials.toString()` rule already forbids it. **Run the redaction test on `macosArm64Test` and `linuxX64Test`, not only `jvmTest`.**
- [x] **PRESIGN DIFFERENTIAL HARNESS (`jvmTest`, `testImplementation aws.sdk.kotlin:s3`) — BUILD THIS FIRST**, exactly as M3 builds its harness first.
  - **The M3 `Interceptor` mechanism structurally cannot be reused**: presigning never transmits. `presignRequest` sets `unsignedRequestBuilder.body = HttpBody.Empty` and **returns** an `HttpRequest` (`smithy-kotlin/.../awssigning/Presigner.kt:30,54-65`), so `readBeforeTransmit` never fires. The replacement is *simpler*: on JVM the SDK presigner hands back the signed request as an ordinary value.
  - **The clock trick matters.** The SDK presigner takes no injectable signing clock, so parse `X-Amz-Date` back out of **its** returned URL and feed that exact instant into ours. Without it the comparison is either flaky at second boundaries or has to exclude `X-Amz-Signature`, which is the only field worth comparing.
  - Matrix: GET and PUT (and HEAD); with and without a session token; keys containing a space, `+`, `//`, `..`, `%`, `:` and non-ASCII; **one case with `response-content-disposition` containing a space and a non-ASCII filename** (that combination exercises the S3-mode query encoder, the thinnest-covered code in the milestone); expiries of 1 s, 900 s and 604 800 s.
- [x] Security tests: `expiresIn > 7.days` throws with S3's own error text; `PresignedUrl.toString()` redacts both signature and session token; the secret, the session token and the signature appear in no `toString()`, no exception `message` and no `stackTraceToString()`.
- [x] **Module KDoc — say the uncomfortable things plainly.** A presigned URL is a **bearer token carrying the SIGNING ROLE's permissions, not the end user's**; it is reusable until expiry; **it cannot be revoked** (the only controls are the `s3:signatureAge` bucket-policy condition and network-path conditions, both of which live in IAM, not in the code); and a Lambda-minted URL dies with the role session.
  - **`presignPutObject` is the highest-blast-radius call in the library and must not read like the sibling of `presignGetObject`.** Because `UNSIGNED-PAYLOAD` is used, the URL accepts **any body, of any content, up to S3's 5 GiB single-PUT limit, from anyone holding the link**. The URL constrains bucket, key, method, expiry and the signed headers — nothing else. Callers needing content or size constraints must enforce them with a bucket policy (`s3:content-length-range` via a POST policy) or a post-upload check, not with the URL.
  - Signing `Content-Type` trades flexibility for enforcement: once signed, the uploader must reproduce it byte-for-byte, and browser `fetch` with a `Blob` — like many HTTP clients — appends or normalises a charset and produces a 403 naming a header, not a rule. **Omitting it is the safer default unless the caller controls the uploader.**
  - Presigning performs **no network I/O** beyond credential resolution. State this as a security property, not just a performance one: there is no transport, no proxy and no log sink between the signer and the returned string, so the only way a presigned URL leaks is if the caller logs it.
- [x] **HTTPS only.** `UNSIGNED-PAYLOAD` relies on TLS for body integrity. Reject an `http://` endpoint override unless an explicit `allowInsecureEndpoint` flag is set, and even then only for a loopback host — that is the LocalStack/MinIO case and nothing else.

**Verification**:
- (a) `./gradlew :aws:aws-s3:jvmTest` — the presign differential is **byte-identical** to `aws.sdk.kotlin`'s S3 presigner across the full key / token / expiry / response-override matrix, **INCLUDING `X-Amz-Signature`**. (Decision 17 is what makes this achievable.)
- (b) `./gradlew :aws:aws-signing:jvmTest :aws:aws-signing:macosArm64Test` and, on CI, `:aws:aws-signing:linuxX64Test` — 37 header + 37 query assertions green on all three targets.
- (c) `./gradlew :aws:aws-s3:macosArm64Test` and `:aws:aws-s3:linuxX64Test` run a **non-zero** number of tests, including the redaction test (assert the count > 0 — this is exactly the vacuous-pass mistake 2.3.x made).
- (d) **HARD, CREDENTIALED**: presign a GET of a known object in a real bucket and fetch it with a bare `HttpClient.get(url)` carrying **no credentials and no signer** — HTTP 200 with the exact expected bytes. Presign a PUT, `HttpClient.put(url, bytes)` it, read the object back and compare.
- (e) **NEGATIVE**: a 1-second URL fetched after it expires returns 403 with `Request has expired`.
- (f) A 403 or a `SignatureDoesNotMatch` anywhere in (d) **FAILS the milestone**.
- (g) `./gradlew :aws:aws-s3:apiCheck` — the `.api` dump is committed and reviewed.

> **STATUS: M3.5a IS CODE-COMPLETE (2026-08-11); the two credentialed criteria (d) and (e) are
> unrun, exactly as M3's (d) is.** `aws/aws-s3` exists with **54 jvm / 39 macosArm64 tests, 0
> failures**; `linuxX64` and `linuxArm64` link. Repo-wide `./gradlew build` is green at **660 tests**.
>
> **The headline result: the presign differential is green against the real `aws.sdk.kotlin` S3
> presigner, `X-Amz-Signature` included**, across all 15 cases — keys with a space, `+`, `//`, `..`,
> `%`, `:` and non-ASCII; with and without a session token; 1 s / 900 s / 7-day expiries; and the
> `response-content-disposition` case carrying a space *and* a non-ASCII filename. GET, PUT and
> DELETE.
>
> **The precondition was checked before any code was written**, as this section demands.
> `querySigningMatchesAwsVectors` is green on `jvm` and `macosArm64` and carries an internal
> `assertEquals(37, asserted)` guard, so it cannot pass vacuously. `get-percent-single-encoded` and
> all seven `*-unnormalized` cases carry full query-mode expectation files and are outside the
> three-case `skipped` set. (`linuxX64Test` cannot execute on a macOS host; that stays CI's.)
>
> **The differential was verified to bite, not merely to pass.** Flipping `doubleUriEncode` to
> `true` fails **8** cases; flipping `normalizeUriPath` to `true` fails **exactly one** —
> `getKeyWithDoubleSlashAndDots`, the only case containing `//` and `..`. Those are the two flags
> the whole milestone exists to validate, and the harness localizes each one precisely.
>
> **Two corrections to this section:**
> 1. **The AWS SDK ships no HeadObject presigner** (`PresignersKt` exposes GetObject, PutObject,
>    DeleteObject and UploadPart only). The matrix's HEAD case therefore cannot be a *differential*;
>    HEAD is covered structurally instead, including the assertion that it carries **no** `x-id`.
> 2. **"Byte-identical" needs one qualification.** Origin and encoded path are compared as exact
>    strings and every query pair — signature included — is compared exactly, but as a *sorted*
>    multiset. Query emission order is not a wire-correctness property: the canonical request sorts,
>    so two URLs differing only in parameter order carry the same signature and behave identically.
>    Pinning the order would assert on an SDK implementation detail, not on our correctness.
>
> **`aws-core` gained one function**: `awsEnv(name)`, a public wrapper over the existing `internal
> expect platformGetEnv`. `aws-s3` needs `AWS_ENDPOINT_URL_S3` and `AWS_CREDENTIAL_EXPIRATION`, and
> the alternative was a second `expect`/`actual` pair for `getenv` in a module whose whole point is
> to depend on `aws-core` and nothing else. Purely additive; visible in `aws-core.api`.
>
> **Everything the section asks to be uncomfortable about is in the KDoc**, on the types a caller
> actually touches: a presigned URL is a bearer token carrying the *signing role's* permissions, is
> reusable until expiry, and **cannot be revoked**; `presignPutObject` accepts any body of any
> content up to 5 GiB from anyone holding the link, because `UNSIGNED-PAYLOAD` is mandatory when the
> body does not yet exist; signing `Content-Type` trades flexibility for enforcement and is best
> omitted unless you control the uploader; and presigning performs no network I/O, which is a
> security property rather than a performance note.
>
> **Design points worth carrying forward:**
> - `PresignExpiry` is a sealed type with `Known` and `BoundedByUnknownSession`, never a bare `Long`.
>   With a session token and no discoverable expiry — the Lambda case, always — an `expiresIn` over
>   one hour is **refused** unless `allowPresignBeyondUnknownSessionExpiry` is set. The differential's
>   7-day cases had to opt in, which is the guard working rather than a workaround.
> - Redaction is plain string splitting, per this section's instruction, and blanks **both**
>   `X-Amz-Signature` and `X-Amz-Security-Token`. The test runs on `macosArm64`, not only the JVM.
> - Addressing style is a routing decision, never a validation gate: uppercase, underscored,
>   over-63-character and dotted buckets all resolve to **path-style** and none of them throw. Only
>   a blank name or one containing a character that cannot appear in a URI is refused.
> - `us-east-1` resolves to `s3.us-east-1.amazonaws.com`, never the legacy global endpoint.
>
> **UPDATE — (d) AND (e) ARE NOW GREEN AGAINST REAL S3 (2026-08-11).** Jon supplied bucket
> `kotlin-native-test-443844975891-us-west-2-an` (us-west-2) and the `vegasful-test` profile.
> `LiveS3Test` passes all four cases:
> - **(d) read half** — a presigned GET fetched by a bare `HttpClient` carrying **no credentials and
>   no signer** returns HTTP 200 with byte-identical content.
> - **(d) write half** — a presigned PUT is accepted from that same unauthenticated client, and the
>   object read back afterwards matches what was written.
> - **(e)** — a 1-second URL fetched after expiry returns **403 with `Request has expired`**.
> - **No `SignatureDoesNotMatch` anywhere, so (f) is satisfied.**
>
> **M3.5a IS THEREFORE COMPLETE.** The presigner is validated end to end: identical to the SDK's
> bytes *and* accepted by S3.

---

### M3.5b — `aws-s3`: GetObject, PutObject, HeadObject, DeleteObject (8 days)

**Goal**: a native Lambda round-trips a real object through real S3, with typed errors, correct retry classification, a real completeness check, and a documented memory ceiling instead of an OOM.

**On the estimate**: 8 days, revised up from a first-pass 6. Calibrated against this plan's own numbers — M6 is **5 days for ONE operation** (PutEvents, ~120 lines) on an existing protocol, existing endpoint scheme and existing error parser. M3.5b is four operations **plus** a new error protocol **plus** new endpoint/bucket addressing **plus** a new LocalStack service **plus** a four-operation request differential **plus** a credentialed live smoke **plus** retry/idempotency classification **plus** the memory ceiling and completeness check. It also has **zero head start from `ref-2.3.x`** — verified: grepping that branch for `s3`/`presign` matches only `settings.gradle.kts`, `test/build.gradle.kts`, an unused `libs.versions.toml` catalog entry and the same `S3Mock` stub. Unlike M4 and M7, nothing here can be cherry-picked.

**Tasks**:
- [x] `S3` interface, `S3Config`, `internal class DefaultS3` wired onto `aws-core`'s `AwsServiceClient` with signing name / endpoint prefix `s3`, `RestXmlErrorParser`, `doubleUriEncode = false`, `normalizeUriPath = false`, `signedBodyHeader = X_AMZ_CONTENT_SHA256`, `maxAttempts = 3`, `followRedirects = false` (inherited from M2).
- [x] **Request/response types are NOT data classes where they carry a `ByteArray`.** `GetObjectResponse` and `PutObjectRequest` are plain classes with hand-written `equals`/`hashCode` over `contentEquals`/`contentHashCode` — **the same rule Decision 2 applies to `AttributeValue.B`/`Bs`**, which states plainly that they "must NOT be data classes". A generated `copy()`/`componentN()`/`toString()` still exposes the array, and the plan deliberately banned the pattern. They are also **not `@Serializable`**: nothing here is serialized (§2's census), and if you find yourself adding `@SerialName` to an S3 type you have wandered into bucket/list/multipart operations.
- [x] **Every date-shaped S3 response header is carried as an unparsed `String` in v1, and no parse failure can fail a response.** State the rule once rather than deciding it per header. `Last-Modified` is IMF-fixdate pass-through; `Expires` is modelled as a timestamp but S3 returns whatever string the uploader set — frequently not a valid IMF-fixdate, which is why the AWS SDKs added a separate `ExpiresString`. v1 omits `Expires` entirely; if it is ever added it is a `String`.
- [x] `getObject`: GET, empty body, `PayloadHash.EmptyBody`. Bind the ~10 request headers actually needed (`Range`, `If-Match`, `If-None-Match`, `If-Modified-Since`, `If-Unmodified-Since`), plus `versionId` and the `response-*` overrides as query params. Harvest into `GetObjectResponse` including the `x-amz-meta-*` prefix map (prefix stripped). `ETag` quotes stripped.
- [x] **`getObject` MEMORY CEILING — this is the operation whose payload size the caller does NOT control**, and the draft put the cap only on `putObject`, where the caller already controls it. In a Lambda an unbounded download is an OOM kill: no stack trace, no typed exception, no CloudWatch error entry, only a truncated invocation.
  - Check `Content-Length` against `S3Config.maxBufferedDownloadBytes` (default 64 MB) and throw `S3PayloadTooLargeException` **before consuming the body**. S3 always returns `Content-Length` on GetObject.
  - **Separately** bound the bytes actually accumulated, so an absent or understated `Content-Length` cannot bypass the cap.
  - Enforce on **every retry attempt**, not once.
  - Name `Range` in the exception message — it is the documented escape hatch.
  - The config field is **split** (`maxBufferedUploadBytes` / `maxBufferedDownloadBytes`) so the asymmetry cannot be silently re-introduced.
- [x] **`getObject` COMPLETENESS CHECK (Decision 16).** After materializing the body, assert `body.size == contentLength` (and on a 206, that the length matches the `Content-Range` span) and throw a typed `S3IncompleteDownloadException` classified **Transient/retryable** so the existing retry loop handles it. TLS gives per-record integrity, not stream completeness; a connection dying mid-body yields a well-formed short array, and "the engine will throw" is an untested assumption on linuxArm64 at the very Ktor version this plan is bumping for Curl body defects. MockEngine cases: a short body with an honest `Content-Length`; a missing `Content-Length`.
- [x] `putObject`: PUT with a pre-materialized `ByteArray` body and an **explicit `Content-Length`**. Send `accept-encoding: identity`. Do **not** send `x-amz-sdk-checksum-algorithm` (AWS explicitly tells REST callers not to). Throw `S3PayloadTooLargeException` above `S3Config.maxBufferedUploadBytes` rather than allowing an OOM.
- [x] **`x-amz-content-sha256` is ALWAYS the real computed hex in v1. There is no size-triggered switch.** The draft proposed `unsignedPayloadThresholdBytes = 8 MB`, flipping to `UNSIGNED-PAYLOAD` above it. **Rejected**: that silently forks the request's *security class* on payload size. Against a bucket or IAM policy conditioning on `s3:x-amz-content-sha256` — AWS's documented control for exactly this — small objects succeed and large ones 403, a production failure that scales with payload size and that no fixture, MockEngine test or LocalStack run can reproduce. It is the same shape as Risks 3 and 4: passes 100% of CI, fails a subset of production. The 64 MB buffered ceiling bounds the cost of always hashing to a single SHA-256 pass over at most 64 MB, so `Always` is affordable.
  - If it is ever wanted, expose it as an explicit **per-request** choice (`PayloadSigning { Always | Never | AboveSize(bytes) }` defaulting from `S3Config`), default `Always`, document the bucket-policy interaction, include the effective mode in request telemetry so a size-dependent 403 is diagnosable from a log line, and add MockEngine assertions at threshold−1 and threshold+1 bytes. `UNSIGNED-PAYLOAD` remains **mandatory and unconditional for presign only**, where the body genuinely does not exist yet.
- [x] `headObject` and `deleteObject`. **HeadObject has no response body by protocol**, so its errors classify from status alone (404 → NotFound, 412 → PreconditionFailed) — the AWS docs state the exact exception is not retrievable for HEAD. DeleteObject returns 204.
- [x] `XmlError.kt` — the ~60-line scanner specified in M2, mapped to typed exceptions: `NoSuchKeyException` (404), `NoSuchBucketException` (404), `AccessDeniedException` (403, also `HeadersNotSigned`), `InvalidObjectStateException` (403, GLACIER/DEEP_ARCHIVE), `PreconditionFailedException` (412), `NotModifiedException` (304 — **no body**), `SlowDownException` (503 → Throttling, not Transient), `PermanentRedirectException` (301 — wrong region, **never retried**), `ConditionalRequestConflictException` (409 — **retried**), plus the `S3Exception` fallback. All extend `aws-core`'s `AwsServiceException` and always capture `x-amz-request-id` **and** `x-amz-id-2`.
- [x] Request-side differential harness for all four operations, via the same smithy-kotlin `Interceptor` pattern M3 uses. Assert the **request line** (which is where S3-mode key encoding and `x-id` live), the header-name set, and the header values.
- [ ] LocalStack integration tests: add `LocalStackContainer.Service.S3` to the container config — **no existing config enables it** (verified: `DynamoKtTests.kt:16`, `ExposedTestBase.kt:17`, `DynamoStreamTest.kt:29-30` list only DYNAMODB and DYNAMODB_STREAMS) — and set `forcePathStyle = true`, since every existing test reaches LocalStack via `getEndpointOverride` on an ephemeral localhost port. Remember Risk 2: LocalStack never verifies a signature, so these tests prove behaviour, not correctness of signing.
- [x] Module KDoc: the `ByteArray` object-size ceiling table (128 MB Lambda → ~30 MB objects; 256 → ~70; 512 → ~150; 1024 → ~350), and the explicit statement that a `ByteReadChannel` overload is a purely additive v2 addition.

**Verification**:
- (a) `./gradlew :aws:aws-s3:jvmTest` — the request differential passes for `getObject`, `putObject`, `headObject` and `deleteObject`, including the `x-id` query literal and its absence on HEAD.
- (b) `./gradlew :aws:aws-s3:macosArm64Test` runs a **non-zero** number of tests.
- (c) MockEngine: a 503 `SlowDown` IS retried and uses the **throttling** backoff base; a 404 `NoSuchKey` is NOT retried; a 409 `ConditionalRequestConflict` IS retried; a mid-flight IO failure on a `putObject` **with `ifNoneMatch` set** is NOT retried while the same failure without it IS; a malformed XML error body degrades rather than throwing out of the error path; a HEAD 404 with no body still produces a typed exception; `x-amz-request-id` and `x-amz-id-2` both reach the thrown exception; a 301 is surfaced rather than followed; a short body with an honest `Content-Length` raises `S3IncompleteDownloadException` and IS retried; a body exceeding `maxBufferedDownloadBytes` throws **before** the body is consumed.
- (d) LocalStack integration green.
- (e) **HARD, CREDENTIALED LIVE SMOKE — mandatory exit criterion.** Against a real bucket: `putObject` then `getObject` a key containing `` `a b/c..d/e+f/日本語` `` and assert the returned bytes are byte-identical; then a `Range` request for bytes 0-9 returns HTTP 206 with exactly those 10 bytes and a `Content-Range` header; then `headObject` returns the same ETag; then `deleteObject` and confirm a subsequent `getObject` throws `NoSuchKeyException`. **A `SignatureDoesNotMatch` on the awkward key fails the milestone** — that key is the whole point, because it is the input the `doubleUriEncode`/`normalizeUriPath` flags change.
- (f) `./gradlew :aws:aws-s3:apiCheck` green with the dump re-committed. M3.5b adds `S3`, `S3Config`, nine exception types and four request/response pairs; the binary-compatibility check is the artifact proving this plan's "additive only, no API break" claim, so it is an explicit exit criterion on **both** halves.

> **STATUS: M3.5b IS CODE-COMPLETE (2026-08-11); LocalStack (d) and the live smoke (e) are unrun.**
> `aws-s3` carries **74 jvm / 59 macosArm64 tests, 0 failures**; Linux targets link.
>
> **Verification (a) is green: the request differential matches the real SDK for all four
> operations**, on the key `a b/c..d/e+f/日本語` — request line and `x-amz-content-sha256` both,
> including `x-id` on GET/PUT/DELETE and its **absence** on HEAD. Sabotaging HEAD to emit `x-id`
> fails both the unit test and the differential, so the assertion is load-bearing.
>
> **⚠️ THIS MILESTONE BROKE A DELIBERATELY FROZEN SIGNATURE, and it needs sign-off.** M3's status
> records that `AwsServiceClient.callRaw`'s signature was **frozen in the ABI dump**. It has gained a
> trailing `inspectBeforeBody: ((Int, Map<String, String>) -> Unit)? = null`. Source-compatible;
> **binary-incompatible** (the JVM descriptor gains a `Function2`).
>
> It was not avoidable at the S3 layer, and the reason is worth recording because the task list as
> written cannot be satisfied without it. This section requires the download ceiling to throw
> "**before consuming the body**" — but `callRaw` calls `response.readRawBytes()` and hands back an
> `AwsHttpResponse` whose `body` is already a materialized `ByteArray`. A ceiling checked on the
> returned object therefore runs *after* the allocation it exists to prevent, which is precisely the
> OOM this section says must not happen: in Lambda that is no stack trace, no typed exception and no
> CloudWatch error entry, only a truncated invocation. The hook is invoked between the response
> headers arriving and the body being read — the only point where the check can do its job. **If the
> frozen signature matters more than the ceiling being real, the alternative is to accept a ceiling
> that only prevents returning an oversized object, and to say so in the KDoc.**
>
> > **RATIFIED 2026-08-12 — the parameter stays, as-is.** The break is real but it is a *process*
> > breach, not a compatibility incident: `aws-core` has never been released (the entire 3.0 line was
> > local-only until 2026-08-11 and its version is still `3.0.0-dev.*`), so the artifact has zero
> > published versions and zero external consumers. Every caller is in-repo — `TypedCalls.kt`,
> > `S3.getObject`, and tests. Weighed against that, the ceiling is protection against a failure that
> > is otherwise *silent*: an OOM kill inside Lambda produces no stack trace, no typed exception and
> > no CloudWatch error entry, only a truncated invocation. The alternative offered above — a ceiling
> > that merely declines to return an oversized object — would run after the allocation it exists to
> > prevent and so would not prevent anything.
> >
> > The lesson recorded, rather than the signature reverted: **freezing an ABI on an unreleased
> > artifact before its second consumer exists buys nothing and costs a ratification round.** The
> > freeze was worth having for `aws-signing`, which is pitched as independently publishable; for
> > `aws-core` at M2, with only DynamoDB written against it, it was premature.
>
> > **AMENDED 2026-08-13 — the ratification answered the API question and only the API question.**
> > Whether the parameter could exist was settled above. What nobody asked was **where in the retry
> > loop it fires**, and on that the answer was wrong in both directions at once. Found and fixed on
> > 2026-08-13; the ratification itself stands.
> >
> > `inspectBeforeBody` is invoked from inside `AwsServiceClient.send`, which sits inside `callRaw`'s
> > `try { } catch (failure: Throwable)`. So `S3PayloadTooLargeException` — a *local policy decision
> > about a response that arrived perfectly intact* — went to `classifyTransportFailure`, whose
> > documented default is `AMBIGUOUS`, and GetObject is `IDEMPOTENT`. **The refusal was retried.** A
> > client that had just decided an object was too big to buffer answered by asking S3 for the same
> > object twice more, with backoff, before surfacing the identical exception. Every existing
> > assertion passed throughout, because the exception the caller sees is the same either way: the
> > only observable is the request count, and nothing counted requests.
> >
> > The mirror-image defect sat eleven lines further down. `checkDownloadComplete` ran on the
> > response `callRaw` had already **returned** — past the retry loop, past the last point at which a
> > replay was possible. `S3IncompleteDownloadException`'s own KDoc says "Classified retryable: a
> > truncated download is exactly the kind of failure a replay fixes," and Decision 16 above says
> > "throws a typed `S3IncompleteDownloadException` classified **Transient/retryable**". It was never
> > retried once. Two guards, both on the wrong side of the same loop, each doing precisely what the
> > other should have.
> >
> > **The fix keeps the two apart by construction rather than by care.** Anything thrown from
> > `inspectBeforeBody` is wrapped in a private marker on the way out of `send` and unwrapped by a
> > `catch` placed *before* the classifier, so a policy refusal can no longer be read as a transport
> > failure. Completeness moved into a new `validateBody` hook that `callRaw` invokes on a successful
> > response from **inside** the loop, where a throw is retried as `TRANSIENT` and surfaced unchanged
> > once the attempt budget is spent. The two hooks are now a matched pair with opposite semantics,
> > and `callRaw`'s KDoc states which check belongs in which and why.
> >
> > One consequence worth flagging, because it is invisible from the S3 side: `S3IncompleteDownloadException`
> > extends `AwsServiceException`, so raising it *inside* `callRaw` newly routes it back through
> > `mapS3Errors`. Its code, `IncompleteBody`, matches no branch there, and the catch-all would have
> > rebuilt it as a bare `S3Exception` — silently dropping `expectedBytes`, `actualBytes` and the type
> > callers catch on. `mapS3Errors` now passes an already-mapped `S3Exception` through untouched.
> >
> > **Read this as a lesson about the shape of the review, not about the parameter.** The ratification
> > round asked "may this parameter exist" and stopped there. A hook's *position relative to the retry
> > loop* is part of its contract every bit as much as its type is, and neither the KDoc nor the
> > ratification said a word about it. Both failure modes were silent: the retried refusal wasted three
> > round trips and looked correct, and the unretryable truncation looked correct because the
> > exception was right. **Any future callback added to `callRaw` states its retry semantics in the
> > KDoc, and its tests assert the request count, not the exception.**
> >
> > Each of the three fixes was mutation-tested rather than assumed: reverting the marker `catch`
> > fails `oversizedDownloadIsRefusedOnceAndNeverRetried` (3 requests, not 1) and
> > `aRefusalFromInspectBeforeBodyIsNeverRetried`; moving the completeness check back outside the
> > loop fails `truncatedBodyIsRetried` (1 request, not 3); dropping the `mapS3Errors` passthrough
> > fails `truncationSurvivesErrorMappingWithItsTypeIntact`. Note that the **pre-existing**
> > `oversizedDeclaredLengthIsRefusedBeforeTheBodyIsRead` passes in every one of those states — which
> > is how the defect survived a milestone with a green suite. After the fix: `aws-core` **74 jvm /
> > 74 macosArm64**, `aws-s3` **86 jvm / 62 macosArm64**, 0 failures; Linux targets link.
>
> **`aws-core` also gained `awsEnv(name)`** in M3.5a — purely additive.
>
> **A finding on the completeness check.** Ktor's own `MockEngine` validates `Content-Length` and
> raises `IllegalStateException` before any client-level check can see the body, so the truncation
> case **cannot be staged through the mock transport** — the plan's "MockEngine cases: a short body
> with an honest Content-Length" is not achievable as written. The logic was extracted to
> `checkDownloadComplete` and is asserted directly instead. That is not a weaker test of the logic,
> but it does mean the *integration* of the check is unexercised, and it also reveals that on the
> JVM the check is defence in depth rather than the primary guard. Whether the real engine notices a
> stream that died mid-body remains engine-dependent, which is the whole reason Decision 16 exists —
> and Curl on `linuxArm64` is both the least-tested engine here and the one that cannot run tests
> locally.
>
> > **CORRECTED 2026-08-13 — two things here are wrong, and the second one hid a bug.**
> >
> > First, the validation is not `MockEngine`'s. It is `checkContentLength` in **ktor-client-core**
> > (`DefaultTransform`'s `ByteArray` branch, `jvmAndPosixMain`), so it fires on every target this
> > library builds — JVM *and* native, real engines included, not just the mock. The finding
> > generalises much further than it was written to: `checkDownloadComplete` is defence in depth
> > everywhere, not only on the JVM.
> >
> > Second — and this is the part that cost something — "the *integration* of the check is
> > unexercised" was recorded as an acceptable gap. It was not. The unexercised integration was
> > exactly where the check was **on the wrong side of the retry loop**, so a failure this plan twice
> > calls retryable was in fact permanent. Asserting the extracted function proved the logic and
> > proved nothing about the wiring, which is where the defect lived.
> >
> > The case *is* stageable after all: `checkContentLength` returns early for a **negative**
> > `Content-Length`, which is the one gap that hands a short body to our own check. Contrived on the
> > wire, and precisely the situation `checkDownloadComplete` exists for — an engine that did not
> > notice. `S3OperationsTest.truncatedBodyIsRetried` now drives the whole path through the transport
> > and asserts three requests with `[25, 50]` ms transient backoff. Revert the fix and it fails at
> > one request.
>
> **Other design points:**
> - `x-amz-content-sha256` is **always** the real computed hash. There is no size-triggered switch to
>   `UNSIGNED-PAYLOAD`; a bucket policy conditioning on `s3:x-amz-content-sha256` would otherwise
>   pass small objects and 403 large ones. `UNSIGNED-PAYLOAD` stays mandatory for presign only.
> - `putObject` with `ifNoneMatch` is classified `NOT_IDEMPOTENT`: a replay after an ambiguous
>   failure would see its own successful write and report a 412 conflict that never happened. Without
>   `ifNoneMatch` it is an unconditional overwrite and is retried. Both directions are tested.
> - `GetObjectResponse` and `PutObjectRequest` are **not** data classes and hand-write
>   `equals`/`hashCode` over `contentEquals`, per Decision 2's rule for `ByteArray` carriers. Nothing
>   is `@Serializable`.
> - Every date-shaped header is an unparsed `String`; `Expires` is omitted entirely.
> - The client keeps one `AwsServiceClient` **per bucket**, because S3 puts the bucket in the
>   authority and therefore in the signed `host`. This is a difference from every other service
>   module in the repo and is easy to miss when reading `DefaultS3` against `DefaultDynamoDb`.
>
> **(e) THE MANDATORY LIVE SMOKE IS GREEN AGAINST REAL S3 (2026-08-11).** Against
> `kotlin-native-test-443844975891-us-west-2-an` in us-west-2 via the `vegasful-test` profile,
> `LiveS3Test.liveObjectRoundTrip` does the whole sequence on the key
> `` `a b/c..d/e+f/日本語` ``: `putObject`, `getObject` byte-identical, a `Range` read returning
> exactly ten bytes with a `Content-Range`, `headObject` agreeing with `getObject` on the ETag,
> `deleteObject`, and a final `getObject` throwing `NoSuchKeyException`. User metadata round-trips
> too. **No `SignatureDoesNotMatch` on the awkward key**, which was the stated fail condition —
> the `doubleUriEncode = false` / `normalizeUriPath = false` pair is now confirmed against S3
> itself rather than only against the SDK's bytes.
>
> **The live suite is gated and the gate was verified in both directions**, because a suite that
> silently no-ops reports exactly the same green as one that passed: with `AWSKT_LIVE_BUCKET` set the
> four tests take **16.8 s** (the expiry case alone 6.6 s); without it they take **0.001 s**. Test
> counts alone would not have distinguished those.
>
> **Credentials come from a named profile, resolved by the AWS SDK inside the test JVM** and bridged
> into our `AwsCredentialsProvider`. This was necessary rather than stylistic: our
> `defaultCredentialsProvider()` is environment-variables-only — no profile file, no SSO, no IMDS —
> so there is otherwise no way to run a live test from a developer machine without exporting raw
> keys into a shell. **Worth a follow-up:** that limitation is invisible until someone tries exactly
> this, and it will bite again for the S3 and EventBridge live tests.
>
> **STILL NOT DONE:**
> - **(d) LocalStack integration.** No existing container config enables `Service.S3`, and the tests
>   would need `forcePathStyle = true`. Not started — and note the live smoke is the *stronger*
>   signal, since LocalStack never verifies a signature (Risk 2).

---

### M4 — Foundation native targets (3 days) — can run in parallel with M1–M3

**Tasks**:
- [x] `standards`: add `linuxX64()`, `linuxArm64()`, `macosArm64()`. Needs a native actual for `expect fun epochMillis()` (`standards/src/commonMain/kotlin/com/steamstreet/Time.kt:6`) and promotion of the `iosMain`-only `Time.kt` and `MutableLazyIOS.kt` to `nativeMain` — `ref-2.3.x` already did both. Promote `libs.kotlin.date.time` to `api` where kotlinx.datetime types are exposed.
- [x] `env`: add the three native targets; native `getenv`/`setenv` actual (20 lines, recoverable from `ref-2.3.x/env/src/nativeMain/kotlin/com/steamstreet/env/env.kt`).
- [x] **`logging`**: add `linuxArm64()`, `macosArm64()`. Its `nativeMain` source set already exists (`logging/build.gradle.kts:57-61` declares `api(libs.ktor.client.core)`) but `logging/src/nativeMain/kotlin/.../SuspendedLogging.native.kt` **has only ever been compiled for Apple targets**. Expect to need a Linux actual for anything relying on Apple-only APIs. This is an explicit task with its own checkbox, not an aside.
- [x] Fix the inverted expiry test in `MutableLazy` (`standards/src/jvmMain/kotlin/com/steamstreet/MutableLazyJVM.kt:38`) and add locking to the native actual, or document it as knowingly broken for finite timeouts. `aws-core` deliberately does not use it either way.

**Verification**: `./gradlew :standards:build :env:build :logging:build` green on macOS; CI runs `linuxX64Test` for all three. `standards`' and `logging`' existing js/wasmJs/iOS compilations still resolve.

> **STATUS: M4 IS COMPLETE (2026-08-11), and the exit criterion is met.**
> `./gradlew build` is green across the repo: **528 tests, 0 failures**, up from 507 by exactly the
> 21 new `MutableLazy` test executions. All three modules carry `linuxX64`, `linuxArm64` and
> `macosArm64`, and the js/wasmJs/iOS compilations that `serialization`, `events` and `lambda/*`
> depend on still resolve.
>
> **The point of the milestone landed: `dynamokt` now compiles on `macosArm64`, `linuxArm64` and
> `linuxX64`.** `No matching variant of project :standards` is gone. **M5a's portability work is
> therefore verified rather than asserted** — with a second target there is a real metadata
> compilation, and `commonMain` is now checked against the common stdlib for the first time.
>
> **That verification found one thing, and only one.** Every M5a fix held up: `kotlin.io.encoding.Base64`,
> `kotlinx.datetime` in `dates.kt`, `enumEntries<T>()` in `delegates.kt`, the `AtomicInteger` removal
> and the `runBlocking` placement all compiled without a change. The single failure was the missing
> native `ioDispatcher` actual — and the interesting part is *why* it could not simply mirror the JVM:
>
> **`Dispatchers.IO` does not exist on Kotlin/Native.** kotlinx-coroutines 1.10.2 declares it
> `internal` off the JVM (`Cannot access 'val IO': it is internal in 'kotlinx.coroutines.Dispatchers'`).
> The `expect`/`actual` `ioDispatcher` was introduced in M5a precisely so the JVM would keep `IO`
> instead of collapsing to `Dispatchers.Default` — but on Native there is nothing else to collapse to.
> `dynamokt/src/nativeMain/.../Dispatchers.native.kt` uses `Dispatchers.Default` and says so in a
> comment: a parallel scan on Native runs roughly core-count segments concurrently, which on a 2-vCPU
> Lambda is two in flight regardless of how much of that time is network wait. The alternative,
> `newFixedThreadPoolContext`, is `@DelicateCoroutinesApi`, allocates threads eagerly and has no owner
> to close it; sizing it wants a measured native Lambda. **Deferred to M7, where that measurement
> exists.** Nothing before M7 runs a parallel scan on Native.
>
> **Two corrections to this section as written:**
> 1. **"Promote `libs.kotlin.date.time` to `api` where kotlinx.datetime types are exposed" has zero
>    sites.** `kotlinx.datetime` is not referenced anywhere in `standards`, `env` or `logging`. Every
>    use is `kotlin.time.Clock` / `kotlin.time.Instant`, which are **standard library**, and the
>    convention plugin already opts in to `kotlin.time.ExperimentalTime` globally. The
>    `implementation(libs.kotlin.date.time)` in `standards`' `iosMain` was dead and was removed;
>    `standards` now has no datetime dependency at all. (`dynamokt`'s `api(libs.kotlin.date.time)` is
>    genuine and untouched.)
> 2. **`logging` needed no Linux actual.** Its `nativeMain` is one line — `actual var log =
>    Log(DefaultLogPublisher())` — and `DefaultLogPublisher` is `println` plus kotlinx-serialization.
>    Adding the three targets was a build-file edit and nothing else. The Apple-only risk this section
>    flagged was not there.
>
> **`MutableLazy` was consolidated rather than patched four times.** The inverted expiry check was not
> only in the JVM actual — the identical class was copy-pasted into `jvmMain`, `iosMain`, `jsMain` and
> `wasmJsMain`, all four with the same bug, and 2.3.x's `nativeMain` copy would have made five. The
> class and `cached()` now live in `commonMain` in one copy; the four platform files are deleted.
>
> The check reads `epochMillis() - lastRetrieved >= timeout` rather than
> `lastRetrieved + timeout > epochMillis()`. Subtracting is not a style preference: **the old form was
> correct for `Duration.INFINITE` only by integer overflow.** `INFINITE.inWholeMilliseconds` is
> `Long.MAX_VALUE`, so `lastRetrieved + it` wraps negative, and the comparison then accidentally
> reported "not expired" — which is why `mutableLazy()`, the only form anyone actually calls, always
> worked while any finite timeout re-ran its initializer on *every* read until expiry and then stopped.
> A naive `>` → `<=` fix would have inverted that accident and broken every `mutableLazy` in the repo.
>
> `standards`' `commonTest` went from 1 test to 5, and it is a real regression suite:
> `unexpiredFiniteTimeoutInitializesOnce` and `expiredTimeoutRecomputes` were **confirmed to fail
> against the old logic and pass against the fix**, and `infiniteTimeoutInitializesOnce` guards the
> overflow trap above. They run on all five platforms, `macosArm64` included.
>
> **Locking: Kotlin/Native still has none, deliberately, and it is now written down rather than
> implied.** `MutableLazyLock` is an `internal expect class` — the JVM actual is a real monitor, so the
> reflection-based initializers in `env` and `events` keep their run-exactly-once guarantee; JS/Wasm
> are single-threaded no-ops; the Native actual is a no-op with the reasoning in the file. The cost is
> that two racing first reads can both run the initializer, not memory unsafety — Kotlin/Native's
> memory model makes reference field access atomic. Every current caller passes `Duration.INFINITE`
> with an idempotent initializer. If a native caller ever needs run-exactly-once, the fix is
> `kotlinx.atomicfu.locks.SynchronizedObject`; it was not pulled in now because it would add a
> third-party dependency to the POM of the module everything else depends on.
>
> **What is NOT covered, and should not be read as green:**
> - **`:logging:macosArm64Test` is `SKIPPED / NO-SOURCE`.** `logging` has no native tests at all — only
>   `jvmTest` (6 tests). Its native targets compile and are published; nothing executes them.
> - **`env` has no tests on any platform**, native included. The posix `getenv`/`setenv` actual is
>   unexercised.
> - **`dynamokt`'s native targets run zero tests.** Its suite is `jvmTest` integration tests against
>   LocalStack via testcontainers; `commonTest` is empty (M5a moved `JsonTests` to `dynamo`). Native is
>   compile-verified only.
> - **`linuxX64Test` / `linuxArm64Test` do not run on a macOS host** — Kotlin/Native cross-compiles the
>   Linux klibs but cannot execute them. Linux execution is CI's job, as this section says.
>
> **ABI diff** (the 3.0 gate's evidence). Four dumps changed and the diff is 4 insertions /
> 7 deletions plus one new file:
> - `env`, `logging`, `standards` `.klib.api`: target-list lines only, no declaration changed.
> - `standards.api`: the one real API break — `cached()` moves from the `MutableLazyJVMKt` facade to
>   `MutableLazyKt`, because its source file moved from `jvmMain` to `commonMain`. Source-compatible,
>   binary-incompatible, which is what the major version is for. `checkKotlinAbi` caught it unprompted.
> - `dynamokt/api/dynamokt.klib.api` is **new** — `dynamokt` had no klib dump when it was JVM-only.
>
> One naming trap worth writing down, since it costs a confused minute: `checkLegacyAbi` and
> `updateLegacyAbi` are **lifecycle aggregates** that drive the per-project `checkKotlinAbi` /
> `updateKotlinAbi`. Both names work and the plan's usage elsewhere is correct — but the *failure
> message* names `checkKotlinAbi`, so `-x checkLegacyAbi` does not exclude the task that actually
> failed. Exclude `checkKotlinAbi`, or just regenerate. `keepUnsupportedTargets` works as documented:
> every dump taken on macOS lists `linuxArm64` and `linuxX64` alongside the Apple targets.
>
> **Sequencing note for review:** the `dynamokt` change is separable. Foundation modules =
> `standards`, `env`, `logging` (+ their ABI dumps); exit-criterion proof = `dynamokt/build.gradle.kts`,
> `Dispatchers.native.kt` and `dynamokt.klib.api`. It stayed in because it came to one new file, not
> because the two belong in one commit.

---

### M5a — Type swap; all modules compile and pass on `SdkBackedDynamoDb` (9.5 days)

**The whole repo compiles and all 100 DynamoDB integration tests pass — while still executing against the real AWS SDK underneath.** This isolates "did the type swap break anything?" from "does the hand-written client work?".

> **STATUS: M5a IS COMPLETE (2026-08-11) — type swap *and* layout conversion.**
> `./gradlew build` passes across the whole repo: **504 tests, 0 failures**. The 99 DynamoDB
> integration tests (25 `dynamokt` + 72 `dynamokt-exposed` + 2 `test`) all execute against
> **`SdkBackedDynamoDb`**, so behaviour underneath is still, byte for byte, the AWS SDK's.
>
> **The headline goal is met, and it is checkable rather than asserted:**
> `./gradlew :dynamokt:dependencies --configuration runtimeClasspath | grep -c aws.sdk.kotlin`
> returns **0**, and so does `dynamokt-exposed`. The AWS SDK is gone from both production graphs.
>
> **What is done (5a.1, 5a.3, 5a.4 and the swap half of 5a.2):**
> - `dynamo` is multiplatform and owns `AttributeValue`, transport-free (no Ktor, no aws-core).
> - `dynamokt`: `DynamoKt`, `DynamoKtSession`, `Transaction`, `Query`, `MutableItem`,
>   `ExpressionBuilder`, `attributes.kt` all on `DynamoDb`.
> - `dynamokt-exposed`: `Database.client` is a `DynamoDb`, `connect()` is no longer `suspend`, and
>   all ten builder-DSL call sites are value requests.
> - `test`: `DynamoStreamRunner.toModelAttributeValue()` retargeted; the stream tests wrap the SDK
>   client in the adapter.
> - `.api` dumps regenerated — **that diff is the API-break artifact the approval gate wanted**, and
>   `checkLegacyAbi` fired on the change rather than letting it through, which is the guard earning
>   its place on its first real break.
>
> **The layout conversion landed too.** `dynamokt` is multiplatform with sources in `commonMain`
> (`jvm()` only for now); `dynamokt-exposed` is multiplatform with sources in **`jvmMain`**, per
> Decision 12. All the JVM couplings this section lists are gone: `java.util.Base64` →
> `kotlin.io.encoding.Base64`; `java.time` → `kotlinx.datetime` in `dates.kt`; `KClass<T>` →
> `List<T>` with `enumEntries<T>()` factories in `delegates.kt`; `AtomicInteger` → plain `var`; and
> `Dispatchers.IO` → an `expect`/`actual` `ioDispatcher` **rather than a blanket downgrade to
> `Dispatchers.Default`**, which would have capped parallel scans at the core count on the JVM too.
>
> The `N`-precision fix landed in both directions. Inbound was worse than this section describes:
> `toPrimitiveValue()` resolved `intOrNull ?: longOrNull ?: floatOrNull ?: doubleOrNull`, and
> `floatOrNull` sits **before** `doubleOrNull`, so every non-integral number was pushed through a
> 32-bit float and truncated to ~7 significant digits *before* becoming an `AttributeValue.N`.
> Both directions now use the raw decimal literal (`JsonUnquotedLiteral` outbound).
>
> **A CORRECTION TO THIS SECTION'S PREMISE, and it matters for sequencing.** The task list says to
> convert to MPP with `jvm()` only, on the reasoning that `commonMain` then enforces the common
> stdlib and surfaces the JVM couplings. **It does not.** With a single target there is no metadata
> compilation, so `commonMain` is compiled against the JVM stdlib and `java.util.Base64`,
> `java.time` and `KClass.java.enumConstants` all compile clean. Verified: the conversion built
> successfully with every coupling still in place. The fixes above were made deliberately, not
> because the compiler demanded them, and **nothing currently verifies them**.
>
> What would verify them is a second target, and that is blocked: adding `macosArm64()` to
> `dynamokt` fails with `No matching variant of project :standards`, because `standards` declares
> only jvm/js/wasmJs/iosArm64/iosSimulatorArm64. **M4 (foundation native targets for `standards`,
> `env`, `logging`) is therefore a hard prerequisite for a native `dynamokt`**, not merely a
> parallel track — the milestone table's "can run in parallel with M1–M3" is true of M4 itself but
> hides that M5a's portability work stays unverified until it lands. Do M4 next.
>
> **RESOLVED 2026-08-11 by M4.** `dynamokt` compiles on `macosArm64`, `linuxArm64` and `linuxX64`, so
> everything above is now compiler-verified rather than asserted. The second target found exactly one
> problem — `Dispatchers.IO` is `internal` outside the JVM in kotlinx-coroutines 1.10.2 — and every
> other fix in this block held. See M4's STATUS.
>
> `JsonTests` moved to **`dynamo`'s `commonTest`** rather than `dynamokt`'s, because the code it
> covers (`toJsonItemString`/`fromJsonToItem`) moved to `dynamo` with `AttributeValue` — and
> `dynamo` has native targets today, so those four tests now execute on `macosArm64` as well as the
> JVM. Its kluent assertions were rewritten to `kotlin.test`, kluent being JVM-only.
>
> **Findings worth carrying into M5b:**
> 1. **The plan's one "genuine design uncertainty" evaporated.** `ExpressionBuilder.apply(scan:)` had
>    **zero callers repo-wide**, and its `nameMap`/`valueMap` were already `internal`, so the only
>    thing an outside caller could do with it was pass an SDK builder type. Deleted; replaced by
>    `build(): FilterExpression?`. No redesign was needed.
> 2. **Two same-package collisions that only appear once `AttributeValue` actually moves**, neither
>    anticipated: `dynamo`'s `typealias Item` against `dynamokt`'s long-standing `class Item`, and
>    duplicate `String`/`Boolean.attributeValue()` extensions. Decision 2 as written walks straight
>    into both. The alias now lives in `aws-dynamodb`; the conveniences are consolidated beside the
>    type with `Number` replacing `Int`/`Long`.
> 3. **The exception-hierarchy swap has a visible test-facing consequence** the plan did not list:
>    nine `dynamokt-exposed` tests asserted on `aws.sdk…ConditionalCheckFailedException` /
>    `TransactionCanceledException` and had to move to ours. That is the adapter working as intended
>    — one hierarchy — but it means M5b's "no test changes" expectation is already partly spent.
> 4. `implementation(project(":env"))` was **verified dead (0 usages)** and removed, taking `env` off
>    `dynamokt`'s native critical path entirely.
> 5. Three real bugs fixed in passing: `getAll` silently returned partial results (no
>    `UnprocessedKeys` loop, chunked to 80 not 100); `queryDelete` sent an unchunked batch and
>    discarded the response; and `DuplicateDynamoItemException` was requesting the losing item via
>    `ReturnValuesOnConditionCheckFailure` and then discarding it, while `asS()` on a numeric sort
>    key threw `ClassCastException` from inside the error path. The exposed paging loops broke on
>    `last == null` and would have spun forever on an empty `LastEvaluatedKey`.
>
> **M5b IS ALREADY PASSING.** Both suites were run against the hand-written `DefaultDynamoDb` with
> `-Dawskt.dynamodb.impl=native`: **97 integration tests, 0 failures** (25 `dynamokt` +
> 72 `dynamokt-exposed`), against LocalStack.
>
> That claim was verified rather than inferred, because a passing run proves nothing on its own here
> — if the switch had failed to reach the test JVM the suite would have run on the *adapter* and
> passed exactly the same way. It did fail to reach it at first: `-D` sets the property on the
> Gradle daemon, not the forked test JVM, so the flip silently did nothing until
> `systemProperty(...)` was added to both build files. A marker then confirmed **36 native-branch
> constructions and zero SDK ones**.
>
> This means M5b's remaining risk is much lower than its 3-day estimate assumes — the flip is
> `-Dawskt.dynamodb.impl=native` becoming the default. What M5b still owes is the *decision* and the
> behavioural-difference note for the PR (the retry-on-ambiguous-write divergence), not discovery.

This is one atomic merge across `dynamo`, `dynamokt`, `dynamokt-exposed` **and `test`**. Verified: `dynamokt-exposed/build.gradle.kts:6` declares `api(project(":dynamo"))`, and `test/build.gradle.kts:32` declares `api(project(":dynamokt"))`, so both break the instant `dynamo` owns `AttributeValue`.

#### 5a.1 `dynamo` — own the type

- [ ] Convert `build.gradle.kts` to MPP conventions + `explicitApi()`, targets jvm/linuxX64/linuxArm64/macosArm64; `git mv src/main/kotlin src/commonMain/kotlin`; drop `api(libs.aws.dynamodb)`.
- [ ] New `dynamo/src/commonMain/kotlin/com/steamstreet/dynamokt/AttributeValue.kt` — sealed class, variants `S/N/B/Bool/Ss/Ns/Bs/L/M/Null/SdkUnknown`, all 20 `asX()`/`asXOrNull()` accessors, names identical to the SDK's. `B` and `Bs` get hand-written `equals`/`hashCode` over `contentEquals`/`contentHashCode`.
- [ ] **Fix `AttributeValueSerializer.kt` — four real bugs, in this exact order to avoid reintroducing one**:
  1. The descriptor at `:26-34` has **8** elements (S,N,B,BOOL,L,M,SS,NS = indices 0–7). Add `element<Boolean>("NULL")` as index **8** and `element<JsonElement>("BS")` as index **9**.
  2. **Simultaneously** change the `Bs` encode at `:121-122` from `encodeSerializableElement(descriptor, 8, …)` to index **9**. Following the "add NULL and BS as 8 and 9" instruction literally without this would make the existing `Bs` write emit under the key `NULL` — a silent wire-format corruption no current test covers.
  3. `deserialize` at `:37-81` has no branch for either — add decode branches for 8 and 9.
  4. `is AttributeValue.Null -> encoder.encodeNull()` at `:130` calls the **outer** encoder from inside `encodeStructure`.
  5. Handle `CompositeDecoder.DECODE_DONE` (-1) explicitly, returning `AttributeValue.SdkUnknown` rather than `throw SerializationException("Unexpected index: -1")`. With `ignoreUnknownKeys = true`, an unrecognised variant currently crashes the whole response parse. Add decode tests for `{"XX":1}` and `{}`.
- [ ] Swap `java.util.Base64` → `kotlin.io.encoding.Base64.Default` at `AttributeValueSerializer.kt:19,43,93,125`. `Base64.Default` is RFC 4648 standard-alphabet-with-padding, byte-identical to `java.util.Base64.getEncoder()`.
- [ ] **Pagination-token compatibility guard**: `attributeValueJson` keeps `encodeDefaults = false, ignoreUnknownKeys = true` and no key-name changes. Adding NULL/BS is strictly widening. Add a regression test decoding a pagination token captured from 2.2.x before the change.
- [ ] `git mv dynamokt/src/main/kotlin/com/steamstreet/dynamokt/json.kt dynamo/src/commonMain/kotlin/com/steamstreet/dynamokt/json.kt` — same package, zero call-site change.
- [ ] **New `dynamo/src/commonTest`** covering the `AttributeValueSerializer` round-trip: `Bs`, `Null`, a 38-digit `N`, and the 2.2.x pagination token. `dynamo` has no test sources at all on 2.2.x, so without this its native port is compile-verified only.

#### 5a.2 `dynamokt` — nine real edits, not seven

- [ ] Convert `build.gradle.kts` (MPP conventions, `explicitApi()`, `jvm()` only for now); `git mv src/main/kotlin src/commonMain/kotlin`; `git mv src/test/kotlin src/jvmTest/kotlin`; rename `tasks.test` → `tasks.named<Test>("jvmTest")`.
- [ ] **Delete `implementation(project(":env"))` at `dynamokt/build.gradle.kts:12`** — verified dead: `grep -rn "steamstreet.env" dynamokt/src/` returns zero matches. This removes `env` from dynamokt's native critical path entirely.
- [ ] **Promote `libs.kotlin.date.time` from `implementation` to `api`** once `dates.kt` exposes kotlinx.datetime types in public API. 2.3.x shipped exactly this defect.
- [ ] **Split `JsonTests.kt` (5 tests) into `src/commonTest/kotlin`** with `commonTest { dependencies { implementation(kotlin("test")) } }`. Keep `BasicTests`/`UpdateTests`/`DynamoKtTests` in `jvmTest` (kluent and Testcontainers are JVM-only). Without this, `macosArm64Test` has zero test sources and passes vacuously — precisely the 2.3.x mistake.
- [ ] **Import-deletion-only files (3, revised down from 5)**: `Item.kt`, `ItemUpdater.kt`, `Serialization.kt`. `DynamoKtIndex.kt`, `Pipes.kt`, `ItemContainer.kt` need no change at all.
- [ ] **Do `ExpressionBuilder` FIRST among the real edits** — it is the only piece with genuine design uncertainty. `public fun ExpressionBuilder.apply(scan: ScanRequest.Builder)` (`ExpressionBuilder.kt:323`) exists only because the SDK had a mutable builder; replace it with a function returning a filter spec the caller applies. It has no honest one-to-one replacement and must be redesigned rather than translated.
- [ ] **`dates.kt` — RECLASSIFIED from import-deletion to real edit + API break.** `:7,8` import `java.time.LocalDateTime`/`LocalTime`; `:65-67` expose `public val AttributeValue.localDate: java.time.LocalDate?`, `.localTime: LocalTime?`, `.localDateTime: LocalDateTime?`. Note `:38` already uses `kotlinx.datetime.LocalDate` for `localDateAttribute` — two types with the same simple name in one file. Migrate all three to `kotlinx.datetime`.
- [ ] **`delegates.kt` — RECLASSIFIED.** `:249,262` use `cls.java.enumConstants` inside `public class EnumSerializer<T : Enum<T>>(private val cls: KClass<T>, private val default: T)` (`:242`) and `public class NullableEnumSerializer<T : Enum<T>>(private val cls: KClass<T>)` (`:255`). Change both public constructors from `KClass<T>` to `List<T>`, with `reified` factory functions supplying `enumEntries<T>()` — the exact fix 2.3.x applied.
- [ ] Remaining real edits: `DynamoKtSession.kt` (7 call sites incl. `createTable`; `getOrNull` at `:38-52` switches from the deprecated `attributesToGet` to a `#p{n}` projection expression; `getAll` at `:80-117` switches to `batchGetAll`; `queryDelete` at `:163-188` switches to `batchWriteAll`); `Query.kt` (`buildQuery()` at `:184-222` returns a `QueryRequest` instead of mutating a builder; `queryPaginated`→`queryPaged`, `scanPaginated`→`scanPaged`); `MutableItem.kt`; `Transaction.kt` (`java.io.Closeable`→`AutoCloseable`); `DynamoKt.kt` (builder type, `runBlocking` removed); `attributes.kt` (`AttributeValueUpdate`/`AttributeAction` become project-owned — verified pure in-memory bookkeeping; `attributeUpdates` is never sent on the wire).
- [ ] Pre-existing JVM couplings blocking commonMain regardless of the client swap: `java.util.Base64` at `Serialization.kt:5,82,91`; `AtomicInteger` at `Query.kt:13,35` and `MutableItem.kt:10,33` → plain `var` (single-threaded expression-builder counters); `Dispatchers.IO` at `DynamoKtSession.kt:206` → an `expect fun ioDispatcher()`, **NOT** a blanket downgrade to `Dispatchers.Default` (which caps parallel scans at core count on JVM too).
- [ ] **Fix `N` precision in BOTH directions.** Outbound: `Serialization.kt:79,85` — replace `toBigDecimal()` with `JsonUnquotedLiteral(attribute.asN())`. Inbound: `Serialization.kt:33-41` `toPrimitiveValue()` resolves `booleanOrNull ?: intOrNull ?: longOrNull ?: floatOrNull ?: doubleOrNull` — `floatOrNull` is tried **before** `doubleOrNull`, so any non-integral number is coerced to ~7 significant digits before becoming `AttributeValue.N`. When `!isString`, construct `AttributeValue.N(content)` directly from the raw literal. Do **NOT** adopt 2.3.x's `toDouble()`, which truncates to ~16 digits. Caveats to note: `JsonUnquotedLiteral` is `@ExperimentalSerializationApi`, **throws** if handed the string `"null"`, and `JsonUnquotedLiteral("1.0") != JsonPrimitive(1.0)` — any existing test comparing `JsonElement` instances changes behaviour. Round-trip test with `123456789012345678901234567890.12345678`.
- [ ] Widen `DuplicateDynamoItemException` construction at `MutableItem.kt:413-416` from `asS()` to `asSOrNull()` on the sort key — it currently throws for a numeric SK. Also surface the `Item` returned on conditional-check failure, which is currently requested (`:411`) and then discarded.
- [ ] Update `dynamokt/src/jvmTest/kotlin/com/steamstreet/dynamokt/DynamoKtTests.kt:22-33` to build a `DynamoDb` pointed at LocalStack with `StaticCredentialsProvider` — **wrapped in `SdkBackedDynamoDb` for this milestone**, selected by a system property so M5b is a one-line flip.

#### 5a.3 `dynamokt-exposed`

- [ ] Convert to MPP conventions with `jvm()` only; `git mv src/main/kotlin src/jvmMain/kotlin` (**not** commonMain — see Decision 12); `git mv src/test/kotlin src/jvmTest/kotlin`.
- [ ] Change the `AttributeValue` import (different package, so change not delete) in `ResultRow.kt`, `Column.kt`, `PageToken.kt`.
- [ ] Re-type 9 call sites across `Query.kt`, `TableOperations.kt`, `Transaction.kt`, `Database.kt`.
- [ ] `Database.kt`: `client: DynamoDbClient` (`:21`) → `DynamoDb`; `clientConfig: (DynamoDbClient.Config.Builder.() -> Unit)?` (`:89`) and `fun client(configure: DynamoDbClient.Config.Builder.() -> Unit)` (`:94`) → `DynamoDbConfig.() -> Unit`; `connect()` (`:51-63`) stops being suspend. Delete the stale unused `import aws.smithy.kotlin.runtime.net.url.Url` at `:4`.
- [ ] Free bug fix: the paging loops at `Query.kt:513-525` and `:578-590` break on `last == null` and would loop forever on an empty `LastEvaluatedKey` map — change to `?.takeIf { it.isNotEmpty() }`.
- [ ] Reconcile the divergent BatchGetItem chunk sizes — 80 in `dynamokt/DynamoKtSession.kt:84`, 100 in `dynamokt-exposed/Query.kt:602`. Pick 100 (DynamoDB's documented limit) and note the behaviour change.
- [ ] Update `ExposedTestBase.kt:26-34` (simpler than dynamokt's — `Database(client)` takes the client directly).

#### 5a.4 `test` module — NOT "unchanged"

- [ ] Rewrite `test/src/jvmMain/kotlin/com/steamstreet/aws/test/DynamoStreamRunner.kt` `toModelAttributeValue()` (`:179-206`, ~28 lines, 10 branches) against `com.steamstreet.dynamokt.AttributeValue`. Verified: `:4` imports the SDK type and `:126-129` feeds the results into `DynamoStreamRecords`/`DynamoStreamEventDetail` imported from `com.steamstreet.dynamokt` at `:12-14`. With `api(project(":dynamokt"))` at `test/build.gradle.kts:32`, both types are on the classpath — a same-simple-name collision on top of the type break.
- [ ] Audit `DynamoEventBridgePipe.kt` and `DynamoTestHelpers.kt` (which does `import aws.sdk.kotlin.services.dynamodb.model.*` at `:5`) for wildcard-import ambiguity.
- [ ] **(+0.5 d) Decide the fate of `S3Mock.kt` / `S3Local` — see Q7.** It is 10 lines, `public class S3Local(private val mock: S3Client = mockk<S3Client>(relaxed = true)) : S3Client by mock, MockService` (`test/src/jvmMain/kotlin/com/steamstreet/aws/test/S3Mock.kt:6-11`), with **zero usages anywhere in the repo** including `test`'s own test sources. It is nothing like its siblings `EventBridgeMock` (309 lines of real rule-matching), `LambdaMock` (280) or `DynamoStreamRunner` (218), which are genuine fakes. **Recommend deletion**: a relaxed mockk over an S3 client returns empty objects, which is worse than useless as a test double. If external consumers exist, re-shape it as a small real in-memory implementation of the new `S3` interface, which would be strictly more useful.
  - **This is the one place the S3 slice touches an existing published artifact, and it is NOT a no-op.** Deleting it removes a `public class` from the published `awskt-test` artifact **and** removes `api(libs.aws.s3)` from its POM (`test/build.gradle.kts:17`) — the identical break class already catalogued as Q1 row (h). It gets its own row: **Q1 (k)**. Keep it strictly out of the "S3 is purely additive" argument (see the amended Risk 1).
- [ ] Everything else in `test` stays on the official SDK, permanently. **This survives S3 unchanged**: a relaxed mockk over the SDK's `S3Client` and an awskt `S3` interface are different types in different packages and cannot collide the way `AttributeValue` does.

**Verification**: `./gradlew build` green across the whole repo; `./gradlew :dynamokt:jvmTest :dynamokt-exposed:jvmTest :test:jvmTest` passes all 100 DynamoDB integration tests (22 dynamokt + 78 dynamokt-exposed) plus the `test` module's stream tests — **all executing against `SdkBackedDynamoDb`**. `./gradlew apiCheck` shows exactly the approved API deltas and nothing else. New: `AttributeValue` containing `Bs` and `Null` round-trips; a 2.2.x-captured pagination token still decodes; binary attributes compare equal under `findDifferences`.

---

### M5b — Implementation flip (3 days)

- [x] Flip the default in `DynamoKt.defaultClientBuilder` and `ExposedTestBase` from `SdkBackedDynamoDb` to `DefaultDynamoDb`.
- [x] Run the full suite. Fix whatever the hand-written client gets wrong.
- [x] Keep both paths wired behind a system property so a regression can be bisected to "type swap" vs "client implementation" in one command.
- [ ] PR description must call out the deliberate behaviour changes: BatchGetItem `UnprocessedKeys` now retried; BatchWriteItem chunked to 25 and `UnprocessedItems` retried; UpdateItem no longer retried on ambiguous transport failures; `TransactWriteItems` auto-generates `ClientRequestToken`; `N` precision preserved end-to-end.

**Verification**: `./gradlew :dynamokt:jvmTest :dynamokt-exposed:jvmTest` — all 100 tests green on the hand-written client. `-Pawskt.dynamo.client=sdk` still green. **This is the project's headline success criterion.**

> **STATUS: M5b IS COMPLETE (2026-08-11). The headline success criterion is met.**
> **92 integration tests, 0 failures** on the hand-written `DefaultDynamoDb` (20 `dynamokt` +
> 72 `dynamokt-exposed`), and **92, 0 failures** again on `-Dawskt.dynamodb.impl=sdk`. Both paths
> stay wired, so a regression is still one command away from being attributed.
>
> **The flip was smaller than this section implies, and the reason matters.** `DynamoKt.defaultClientBuilder`
> was *already* building `DynamoDb { }` — the hand-written client — because M5a's type swap changed
> what that factory returns. **Production has been on `DefaultDynamoDb` since M5a.** The only thing
> still pinned to the SDK was the *test* default, in two `build.gradle.kts` files and two test bases.
>
> The test-side condition was also inverted rather than just re-defaulted: it now reads
> `!= "sdk"` instead of `== "native"`. Otherwise an IDE run, where Gradle's `systemProperty` never
> reaches the JVM, would silently keep testing the adapter — which is precisely the failure this
> milestone exists to rule out.
>
> **A passing run proves nothing here on its own, so the switch was verified directly.** With the
> native branch of `DynamoKtTests.buildClient()` replaced by `error(...)`, **18 of 20 tests failed
> on it** — the two survivors never build a client. The default genuinely selects the hand-written
> implementation.
>
> **One real bug found and fixed, exactly the "fix whatever the hand-written client gets wrong"
> task.** `DynamoDb()` leaked an `HttpClient` on every client it built:
> ```kotlin
> ownsHttpClient = config.httpClient == null,   // true when we built one
> httpClient = config.httpClient,               // ...but this is then null
> ```
> `close()` is `if (ownsHttpClient) httpClient?.close()`, so in the *only* case where ownership is
> true the reference is null and close is a no-op. The flag was right; the reference was not. Both
> now bind one `val httpClient` and hand the same reference to the transport and the close path.
> Ktor connection pools and their coroutine scopes are not free, and `dynamokt` builds a client per
> session.
>
> **Two counts in this plan are wrong.** The verification line says "all 100 tests"; M5a's STATUS
> says 25 `dynamokt` + 72. The real numbers are **20 + 72 = 92** — `dynamokt` has 6 `BasicTests` and
> 14 `UpdateTests`, each accounted for in the XML with nothing skipped. Neither number was ever 100.

---

### M6 — EventBridge client and the `events` module (5 days)

- [x] Create `aws/aws-eventbridge` (jvm/linuxX64/linuxArm64/macosArm64), awsJson1_1, target prefix `AWSEvents`. `interface EventBridgeApi { suspend fun putEvents(entries: List<PutEventsEntry>): PutEventsResponse }`, `PutEventsEntry(eventBusName, source, detailType, detail)`, `PutEventsResponse(failedEntryCount, entries)`, `PutEventsResultEntry(eventId, errorCode, errorMessage)`. ~120 lines.
- [x] **`events` restructuring — do NOT move to commonMain.** Create an intermediate `jvmNativeMain` source set (via `applyDefaultHierarchyTemplate` with a custom template, or manual `dependsOn`) that excludes `js(IR)`. Move `EventBridgeSubmitter.kt` from `jvmMain` to `jvmNativeMain`. Promote `:standards`, `:env`, `:logging` from `jvmMain` to `jvmNativeMain`. Add `linuxArm64()`, `macosArm64()`. Verify `:events:jsMain` and `:events:compileKotlinJs` still resolve.
- [x] Re-type `EventBridgeSubmitter` from `EventBridgeClient` to `EventBridgeApi`. Its per-entry handling at `:46-57` (reading `errorCode`/`errorMessage`/`eventId` on an **HTTP 200**) must be preserved exactly — PutEvents fails per-entry, and a transport that only checks HTTP status reports success on a fully-failed batch.
- [x] Rebase `test/src/jvmMain/kotlin/com/steamstreet/aws/test/EventBridgeMock.kt` from `EventBridgeClient by mockk(relaxed = true)` onto a real implementation of the 1-method `EventBridgeApi`. Its `putRule`/`putTargets`/`createEventBus`/`listRules` surface (`:131,:145,:152,:217`) moves to a local test-only `EventBridgeAdmin` interface. This is unbudgeted rework in a published module — the 5-day estimate exists because of it.
- [x] Add a PutEvents case to both halves of the M3 differential harness.

**Verification**: `./gradlew :events:build :test:jvmTest` green including the existing EventBridge rule-matching tests, plus request- and response-differential assertions for PutEvents. `:events:jsBrowserTest` (or at minimum `compileKotlinJs`) still passes.

**`compileKotlinJs` is only half the verification, and it is the half that cannot fail usefully.** It proves `jvmNativeMain` is *excluded* from `js`; nothing in the line above proves it is *included* in native. Those are independent, and a green build is consistent with the source set reaching no native compilation at all — which is exactly what happened (see STATUS). The inclusion half must be checked against the emitted artifact, not the build's exit code: `events.klib.api` must list `EventBridgeSubmitter` and `postEvent` under `// Targets: [native]`, which `checkLegacyAbi` enforces on every build.

> **STATUS: M6 IS COMPLETE (2026-08-11).**
> `aws/aws-eventbridge` exists with **17 jvm / 13 macosArm64 tests, 0 failures**, and `linuxX64` /
> `linuxArm64` link. `events` carries the three native targets, and `test` no longer depends on the
> AWS SDK's EventBridge artifact at all.
>
> **The differential is real, not decorative.** Four of the seventeen tests compare our serialized
> bytes and our parsed responses against the actual `aws.sdk.kotlin` client, using M3's technique
> unchanged — an `HttpInterceptor` capturing the request, and a one-shot loopback `HttpServer`
> feeding both deserializers identical bytes. Verified by sabotage: renaming `@SerialName("DetailType")`
> to `"detailType"` fails `putEventsRequestMatchesTheSdk`. The response half renders both sides with
> hand-written functions rather than our own encoder, so a symmetric encode/decode mistake cannot
> cancel itself out.
>
> **`jvmNativeMain` was built by hand, not with `applyDefaultHierarchyTemplate`.** The task list
> offered both; the manual `dependsOn` won because the default template has no jvm+native group and
> adding one restructures every other source set in the module as a side effect.
>
> > **CORRECTION (2026-08-12): as originally written, the native half of this did nothing.** The
> > wiring was `jvmNativeMain.dependsOn(commonMain)`, then `jvmMain` and `nativeMain` both
> > `dependsOn` it — and `nativeMain` was an orphan. The first manual `dependsOn` in a module turns
> > the default hierarchy template **off**; `jvmMain` survives that because the `jvm()` target
> > creates it, but `nativeMain` is *only* ever created by the template. So the build was
> > configuring a source set attached to no compilation, and said so on every run: "The Kotlin
> > source set nativeMain was configured but not added to any Kotlin compilation." The klib proved
> > it — `events/build/classes/kotlin/linuxX64/main/klib/` contained `ApplicationEventPoster` and
> > `EventSchema` from `commonMain` and **zero** occurrences of `EventBridgeSubmitter`. For the
> > entire time this section read "COMPLETE", a native Lambda could not post an event, which is the
> > one thing the milestone exists to deliver.
> >
> > Fixed by attaching `jvmNativeMain` to each native target's own default source set
> > (`targets.withType<KotlinNativeTarget>().configureEach { compilations.getByName("main").defaultSourceSet.dependsOn(jvmNativeMain) }`)
> > rather than to the template-supplied `nativeMain`. Target-created source sets exist whether the
> > template is on or off. Driving it off the target list rather than naming the three source sets
> > by hand means a fourth native target is wired automatically instead of silently missing the
> > classes. `events/build.gradle.kts` still emits "Default Kotlin Hierarchy Template Not Applied
> > Correctly" — that warning is expected and inherent to the manual-`dependsOn` choice above; the
> > actionable one, about `nativeMain`, is gone.
>
> `compileKotlinJs` still passes, which is the proof that `jvmNativeMain` is correctly excluded
> from `js` — it references `aws-eventbridge`, which has no `js` target, so a leak would not compile.
> It is **not** proof of the converse, and it was originally offered as though it were: exclusion
> from `js` and inclusion in native are independent, and the build was green while the second was
> false. Inclusion is now pinned by `events.klib.api`, which lists `EventBridgeSubmitter`, `poster`,
> `postEvent` and the `post` extensions under `// Targets: [native]`. Verified by sabotage:
> restoring `nativeMain { dependsOn(jvmNativeMain) }` makes `checkLegacyAbi` fail with those
> declarations as a deletion diff.
>
> **More moved to `jvmNativeMain` than this section lists, and it should.** The task names only
> `EventBridgeSubmitter.kt`, but `EventPoster.kt`, `ApplicationEvent.kt` and `EventSchemaJvm.kt` are
> all portable — they sat in `jvmMain` only because they reach `poster`, which reaches the submitter.
> Leaving them behind would have given a native Lambda a submitter it could construct and no
> `postEvent` to call it with. `EventSchemaJvm.kt` keeps its now-inaccurate filename deliberately:
> renaming it would rename the `EventSchemaJvmKt` JVM facade for no functional gain.
>
> **One thing the plan did not anticipate: `logWarning` is JVM-only.** `EventBridgeSubmitter`'s
> per-entry warning could not move to a shared source set as written. It now logs through the
> multiplatform `log.warning { }`, and `checkResponse` became `suspend` to allow it. On the JVM
> `log` publishes through `Slf4JLogPublisher`, so the output is unchanged. The per-entry *semantics*
> — read `errorCode`/`errorMessage`/`eventId` on an HTTP 200, return the ids with null for the
> failures — are preserved exactly, as required.
>
> **`EventBridgeApi.client` forces a decision on every implementor,** including mocks. `EventBridgeMock`
> throws `UnsupportedOperationException` from it: there is no signed transport behind a mock, and an
> extension operation written against one would be talking to nothing. Failing loudly beats handing
> back a client that cannot work. (`SdkBackedDynamoDb` answers the same question differently — it
> builds a real seam — because it has real credentials to build one from.)
>
> **Dropping `mockk` from the mock changed its behaviour in one way worth knowing.** As
> `EventBridgeClient by mockk(relaxed = true)`, every un-overridden operation silently returned an
> empty response, so a test calling into an unimplemented corner passed for the wrong reason. With a
> one-method interface there is nothing left to relax. Separately, `putEvents` now returns a
> generated `EventId` per entry where the old mock returned `PutEventsResultEntry {}` with none —
> under `ApplicationEventPoster`'s documented contract ("for those that are null, the event was not
> published") the old mock reported *every* event as unpublished. **Any downstream test asserting on
> those nulls will now see ids.** That is a fix, but it is a visible one.
>
> **`EventBridgeAdmin` is deliberately not in the shipping client.** `aws-eventbridge` ships one
> operation because that is the only one production code calls; rules, targets and buses exist only
> so the mock can route an event to a local Lambda. Modelling four control-plane operations in the
> published API to serve the test module would have been the tail wagging the dog. If real `PutRule`
> is ever needed, Decision 18's extension seam allows it without forking.
>
> **This section's verification line rests on tests that do not exist.** It asks for `:test:jvmTest`
> green "including the existing EventBridge rule-matching tests" — but `EventBridgeMock` appeared
> nowhere outside its own file, and `:test:jvmTest` was two `DynamoStreamTest` cases. The largest and
> riskiest change in this milestone had **no coverage at all**, and rewriting it would have shipped
> compile-verified and nothing more. `EventBridgeMockTest` now supplies **9 tests**: rule matching
> and non-matching, bus-ARN resolution, per-entry id generation and ordering, the recorded-event
> query and clear, duplicate-rule rejection, `listRules`, unknown-bus rejection, and the
> `client` refusal.
>
> **ABI:** `test.api` sheds ~40 SDK-typed methods as `EventBridgeMock` stops delegating to
> `EventBridgeClient`; `events.api` re-types the submitter's constructor; `events.klib.api` gains the
> three native targets; `aws-eventbridge/api/` is new. All expected, all reviewable.
>
> > **CORRECTION (2026-08-12):** "`events.klib.api` gains the three native targets" was true only of
> > the dump's `// Targets:` header. The body gained nothing — no `EventBridgeSubmitter`, no
> > `postEvent` — because none of it was compiled for native. The reviewable diff this bullet points
> > at was itself the evidence that the milestone had not landed, and it read as routine instead.
> > **A klib ABI dump that omits the milestone's headline class is a finding, not a formality.** The
> > regenerated dump now carries those declarations under `// Targets: [native]`.
>
> **Still not covered:** `events` itself has no tests on any platform, so `EventBridgeSubmitter`'s
> re-typing is verified only by the mock's contract and the differential, not by a test of the
> submitter. `aws-eventbridge` has no LocalStack or live smoke — M3's DynamoDB equivalents have both.

---

### M7 — Native targets, Lambda runtime, packaging (10.5 days)

- [x] ~~Add `linuxArm64()`, `linuxX64()`, `macosArm64()` to `dynamo` and `dynamokt`.~~ **Already done before M7 started** — `dynamo` from M3, `dynamokt` from M4. No target-addition work existed. **`macosArm64` is not optional** — `linuxArm64` is Tier 2 with test execution unsupported, so omitting it leaves the native port compile-verified only, exactly the gap 2.3.x left (`ref-2.3.x/dynamo/build.gradle.kts:5-8` and `ref-2.3.x/dynamokt/build.gradle.kts:5-8` declare only jvm + linuxArm64).
- [x] Promote `gradle-plugin/` to an included build with `java-gradle-plugin` + `maven-publish` and plugin id `com.steamstreet.awskt.native-lambda`; add `includeBuild("gradle-plugin")` to `settings.gradle.kts` `pluginManagement`. Port the 36-line `packageLambda` Zip task from `ref-2.3.x/buildSrc/src/main/kotlin/steamstreet-common.native-lambda-conventions.gradle.kts` (linuxArm64 + rename `*.kexe` → `bootstrap` with `rwxr-xr-x`).
- [x] Cherry-pick `ref-2.3.x/lambda/lambda-native` (`LambdaRuntime.kt`, `HttpClient.kt`, `nativeLambda.kt`); add `include(":lambda:lambda-native")`. Then fix five spec gaps 2.3.x left:
  1. No `POST /runtime/init/error` — lines 36-37 call `error()` on a missing `AWS_LAMBDA_RUNTIME_API` and crash unreported.
  2. No `Lambda-Runtime-Function-Error-Type` header on the error POST (`:85-88`).
  3. `Lambda-Runtime-Trace-Id` never read and `_X_AMZN_TRACE_ID` never exported — X-Ray is silently dead.
  4. `catch (e: Exception)` at `:79` lets a `Throwable` kill the poll loop → catch `Throwable`.
  5. `remainingTimeInMillis` is a per-invocation snapshot (`:60-62`, passed as a `val` to `NativeLambdaContext`) — make it a live function computed from the stored deadline, matching the JVM `Context`.
  Also: `LambdaRuntime` constructs `HttpClient(Curl) { expectSuccess = false }` directly instead of using its own `lambdaHttpClient()` helper — wire them together, and add a source comment that the `/invocation/next` GET must have **no** request timeout (installing Ktor's `HttpTimeout` plugin globally on that client would break idle Lambdas in a hard-to-diagnose way).
- [x] Implement `packageNativeLayer` — named at `ref-2.3.x/NATIVE-LAMBDA-PLAN.md:148-152` but never written. A Lambda Layer zip containing `lib/libcrypt.so.1` extracted from the `amazonlinux:2` arm64 image, used with `LD_LIBRARY_PATH=/opt/lib:/lib64:/usr/lib64`. KT-55643 is Open, unassigned, no fix version, affected since Kotlin 1.8 — this is long-lived infrastructure and belongs in the published artifact set, not in per-consumer copy-paste.
- [x] **Native handler entry points** — this is the largest unbudgeted item the draft missed. 2.3.x's `nativeLambda()` accepts only `suspend (String) -> String` and no `nativeMain` exists in `lambda-eventbridge` or `lambda-sqs`. The 2.3.x work that *was* done shows the shape: `lambda-eventbridge/eventbridge.kt` moved from the default package into `com.steamstreet.aws.lambda.eventbridge` with 313 lines rewritten plus an 82-line `eventbridge.jvm.kt`, and `lambda-sqs` split into `com.steamstreet.aws.sqs/sqs.kt` (+40) and `handlers.kt` (41 changed). **The package move is a public API rename requiring its own approval** (see §9).
- [x] Split CI into an ubuntu + macos matrix per §6.1.
- [x] Add a deployed-Lambda smoke invocation to CI — the only coverage `linuxArm64` can ever get.

- [x] **(+0.5 d) Extend the deployed-Lambda smoke to cover S3.** No other M7 work is needed for it — `aws-s3` declares its native targets from birth, so there is no target-addition task.

**Verification**: `./gradlew packageLambda packageNativeLayer` produces a bootstrap zip and a libcrypt layer zip; the layer plus binary deploy to `provided.al2023` / arm64; a real invocation
1. performs a DynamoDB PutItem then GetItem against a real table and returns the round-tripped item, **and**
2. performs an S3 `PutObject` then `GetObject` against a real bucket, **and**
3. returns a presigned GET URL which CI then fetches **unauthenticated** and asserts returns HTTP 200 with the expected bytes.

`./gradlew :dynamokt:macosArm64Test` runs the `commonTest` sources split out in M5a (non-vacuously — assert the test count is > 0).

*Why (2) and (3) are mandatory rather than nice-to-have*: per §6.1, `linuxArm64` is Tier 2 with test execution unsupported, so the deployed-Lambda smoke is the **only** coverage the actual deployment target can ever receive. S3 is now part of the stated requirement for that target, so omitting it would leave the headline new capability compile-verified only — precisely the 2.3.x mistake this milestone opens by calling out.

> **STATUS: M7 IS COMPLETE (2026-08-11). The deployed-Lambda smoke passes on real Graviton.**
> `./gradlew build` is green at **753 tests / 0 failures**, up from the **709 / 0** measured on a
> clean worktree at `75ca412`. The delta is exactly +44 and nothing was lost: `lambda-native`
> contributes 10 `macosArm64` tests, `dynamokt` gains 17 `commonTest` tests which run **twice**
> (17 `macosArm64` + 17 added to `jvmTest`, 20 → 37).
>
> **The mandatory verification ran against real AWS and passed.** `packageLambda` and
> `packageNativeLayer` produce a 4.8 MB bootstrap zip (one entry, `bootstrap`, mode `rwxr-xr-x`,
> confirmed `ELF 64-bit LSB executable, ARM aarch64`) and a 20 KB layer zip containing
> `lib/libcrypt.so.1` (75 536 bytes, confirmed `ELF 64-bit LSB shared object, ARM aarch64`). Both
> deploy to `provided.al2023` / arm64, and one invocation round-tripped a DynamoDB PutItem/GetItem,
> round-tripped an S3 PutObject/GetObject, and returned presigned GET URLs that CI then fetched
> **unauthenticated** for HTTP 200 and a byte comparison. Driver: `.github/scripts/native-smoke.sh`,
> gated on `AWSKT_LIVE_SMOKE=1` and verified in both directions (0 s when gated off against ~90 s
> for a real run).
>
> **The smoke deliberately exceeds the stated criteria in two places, because they were free.**
> It writes and reads an S3 key of `smoke/<id>/a b/c..d/e+f/日本語.txt` — a space, a `..` segment,
> a `+` and non-ASCII, i.e. precisely the inputs S3's `normalizeUriPath=false` +
> `doubleUriEncode=false` combination changes and which **Risk 25 records as having no AWS fixture
> coverage at all**. The unauthenticated presigned fetch of that key returning 200 is the first
> end-to-end evidence that combination is right. It also asserts a 38-digit `N`
> (`12345678901234567890.0987654321`) survives the round trip, which is Risk 13's precision bug
> observed on the deployment target rather than in a unit test.
>
> **The libcrypt layer is proven load-bearing, not assumed.** Detaching the layer and re-invoking
> gives `Runtime.ExitError … exit status 127`; re-attaching restores a passing smoke. KT-55643 is
> real on `provided.al2023` and the layer is the thing that fixes it.
>
> **All five spec gaps are fixed, and each is proven by sabotage.** Reverting a fix makes exactly
> the test that targets it fail: `catch (Throwable)` → `Exception` fails
> `reportsAThrowableThatIsNotAnException`; dropping the `Lambda-Runtime-Function-Error-Type` header
> fails three tests; dropping the `_X_AMZN_TRACE_ID` export fails `exportsTheTraceIdIntoTheEnvironment`;
> making `remainingTimeInMillis` a construction-time snapshot fails
> `remainingTimeCountsDownAsTheInvocationRuns`; pointing the init POST away from
> `/runtime/init/error` fails `reportsInitializationFailureToTheInitErrorEndpoint`. `LambdaRuntime`
> takes its client from `lambdaHttpClient()` through an `internal` constructor, which is also what
> makes the loop testable against a `MockEngine`, and the no-`HttpTimeout` rule is a comment at the
> field it applies to.
>
> `remainingTimeInMillis` stayed a `val` with a computed getter rather than becoming a `fun`. The
> task said "live function"; a property with a getter *is* recomputed per read, it matches the JVM
> `Context` shape, and it keeps `LambdaContext` idiomatic. The test asserts the value falls across a
> real delay, so the distinction that mattered is the one that is enforced.
>
> ### Four places the plan was wrong
>
> **1. The §9 item (j) package rename was already done.** M7's task list and §9 both describe
> `lambda-eventbridge` / `lambda-sqs` renaming into `com.steamstreet.aws.lambda.eventbridge` /
> `com.steamstreet.aws.sqs` as pending work needing its own approval. Both modules were **already**
> in those packages on `3.0.x` — verified in the sources and in the committed `.api` dumps. There
> was no rename to approve. Row (j) should be struck, not decided.
>
> **2. `lambda-coroutines` had to become multiplatform, and nothing in M7 says so.** 2.3.x's
> `lambda-native` imports `LambdaContext` and `lambdaContext` from `com.steamstreet.aws.lambda`,
> which on 2.3.x lives in a **multiplatform** `lambda-coroutines/src/commonMain`. On `3.0.x` that
> module is still JVM-only (`src/main/kotlin`), so the cherry-pick could not have compiled as
> written. Converting it is a prerequisite for every native handler, and it carries **a public API
> break the plan never inventoried**: `lambdaContext` is retyped from the AWS
> `com.amazonaws.services.lambda.runtime.Context` to the new common `LambdaContext`. Blast radius
> inside this repo is **zero** — a repo-wide grep finds `lambdaContext` only at its own declaration
> and its one assignment — and `awsLambdaContext` plus `JvmLambdaContext.awsContext` are the
> migration path for consumers. It belongs in the §9 inventory as a new row.
>
> **3. `dynamokt` has no `commonTest`, and M5a never created one.** M7's verification says
> `:dynamokt:macosArm64Test` runs "the `commonTest` sources split out in M5a". No such source set
> existed — `dynamokt/src` held only `commonMain`, `jvmMain`, `jvmTest` and `nativeMain`, and the
> `commonTest` block in its `build.gradle.kts` declared dependencies for a directory with no files.
> The native targets therefore ran **zero** tests while reporting success, which is exactly the
> vacuous-pass failure mode this project has been caught by twice. 17 tests were written from
> scratch (`AttributeDiffTest`, `ExpressionBuilderTest`), chosen to need no AWS, LocalStack or
> Docker so they run on all three hosts. They bite: swapping `AttributeValue.B`'s content equality
> for reference equality fails `treatsBinaryAttributesWithEqualContentAsUnchanged` — the Risk 11
> defect, which until now had no test anywhere in the repo.
>
> **4. `settings.gradle.kts` had `pluginManagement` nested inside `dependencyResolutionManagement`.**
> It resolved against the outer `Settings` receiver and worked by accident. `includeBuild` for
> plugin resolution does not tolerate it, so the block moved to the top of the file where it belongs.
>
> ### Other findings
>
> **The logging helpers are JVM-only — the same wall M6 hit.** `logWarning`, `logError`, `logInfo`,
> `logJson` and `mdcContext` all live in `logging/src/jvmMain`; only the `Log` class is common. The
> EventBridge DSL needed one log call in `commonMain`, so it goes through an `internal expect fun
> logProcessingEvent`, whose JVM actual is the original `logger.logJson(...)` verbatim. That keeps
> the logstash JSON shape existing JVM consumers parse **byte-identical**; routing the DSL onto the
> common `Log` API instead would have changed it silently.
>
> **`linuxArm64` cross-compiles from macOS.** `linkReleaseExecutableLinuxArm64` and therefore
> `packageLambda` both work on a developer machine, so producing a deployable artifact does not
> require a Linux host. Only *running* tests on the target is unavailable.
>
> **The EventBridge JVM facade class changed name.** Splitting `eventbridge.kt` into
> `commonMain/EventBridge.kt` + `jvmMain/EventBridge.jvm.kt` moves the JVM-only top-level functions
> from `EventbridgeKt` to `EventBridge_jvmKt`. Source-compatible for Kotlin callers, who resolve by
> package; a binary break for any **Java** caller of `EventbridgeKt.eventBridge(...)`. The split
> makes it unavoidable. `lambda-sqs`'s JVM `.api` is byte-identical after its conversion, so only
> EventBridge is affected.
>
> **The `gradle-plugin` included build compiles against Gradle's embedded Kotlin**, where
> `org.gradle.kotlin.dsl` receiver-style extensions win overload resolution. `tasks.register<T>(…) { }`
> and `exec { commandLine(…) }` are required; the `Action`-with-`it` forms do not compile.
>
> **`packageNativeLayer` extracts via `cat`, not `docker cp`.** `/usr/lib64/libcrypt.so.1` is a
> symlink to `libcrypt-2.26.so`, and `docker cp` preserves symlinks — a dangling link in a layer
> resolves to nothing at runtime and fails the function at cold start with a loader error that names
> the library but not the reason. The task also fails the build if an extracted file is under 1 KB,
> so a wrong path cannot ship a broken layer.
>
> ### Measured performance (2026-08-12, on the deployed function)
>
> Numbers are Lambda's own REPORT values via `--log-type Tail`, not caller wall-clock. Cold starts
> are forced by rewriting an environment variable to a nonce, which is what actually recycles the
> execution environment; any sample returning without an `Init Duration` is discarded rather than
> averaged in. Harness: `.github/scripts/native-perf.py`. Handler modes are selected by the event
> payload, so one deployed function serves all rows.
>
> | Workload | Memory | Total cold (init + handler) | Warm | Peak mem |
> |---|---|---|---|---|
> | `ping` (no AWS) | 1024 MB | 73 ms | **1.8 ms** | 41 MB |
> | `get` (one DynamoDB GetItem) | 512 MB | 210 ms | 4.8 ms | 50 MB |
> | `get` | 1024 MB | **141 ms** | **4.7 ms** | 50 MB |
> | `event` (one EventBridge PutEvents) | 1024 MB | 159 ms | 5.4 ms | 50 MB |
> | `getevent` (read then emit) | 1024 MB | 199 ms | 8.3 ms | 54 MB |
> | `full` (6 round trips, S3 + presign) | 1024 MB | 272 ms | 162 ms | 127 MB |
>
> **Three findings that change how these functions should be written and configured.**
>
> **1. Hold the service clients across invocations — it is worth more than any memory setting.**
> The `full` workload builds its clients inside the handler and closes them with `use { }`, so it
> pays a fresh TLS handshake to every service on every invocation: ~35 ms per round trip warm. The
> minimal modes hold them in `lazy` module state and cost ~4 ms per round trip. Nearly an order of
> magnitude, from connection reuse alone. This is invisible to a correctness test — `full` passes
> the smoke either way — which is precisely why it needs to be written down.
>
> **2. `Init Duration` is flat at ~65 ms across 128/512/1024 MB.** Process start is binary load, not
> computation, so buying memory does not buy a faster cold start. What memory *does* buy is the
> first invocation: `ping`'s cold handler falls 124 ms → 19 ms → 8.8 ms across the three sizes, which
> is CPU-throttled lazy initialization, not init. A measurement that looked only at `Init Duration`
> would have missed ~110 ms of real cold-start cost at 128 MB.
>
> **3. 128 MB is a trap.** Peak usage never exceeds 55 MB on any minimal workload, so 128 MB looks
> generously sized — but it triples warm latency (32.9 ms vs 4.8 ms for `get`) and quintuples cold
> handler time, because Lambda scales vCPU with memory and every one of these calls is TLS-bound.
> **512 MB is the sweet spot**; 1024 MB halves cold handler again and buys nothing warm.
>
> This also closes the sizing question §M5a deferred to "a measured native Lambda": at ≤55 MB peak
> and these CPU curves there is no case for `newFixedThreadPoolContext` over `Dispatchers.Default`.
>
> For context on why any of this matters, the JVM baseline was not re-measured — a basic Kotlin
> DynamoDB Lambda is a 3 s+ cold start, which the maintainer has years of production experience with.
> Against that, 141 ms for the same read is ~21×, and ~65 ms of it is fixed cost that does not grow
> with the workload.
>
> ### What remains
>
> - **The CI matrix is written but unexercised.** `.github/workflows/build.yml` now splits into an
>   `ubuntu` job (full `build`, including the Docker-gated suites and `checkLegacyAbi`) and a `macos`
>   job (Apple targets, `macosArm64Test`, plus the `linuxArm64` link), because ubuntu cannot build
>   Apple targets and macOS runners have no Docker daemon. Nothing has been pushed, so no run exists.
>   The `deployed-smoke` job needs an `AWSKT_SMOKE_ROLE_ARN` OIDC secret and skips cleanly without it.
> - ~~**`AwsServiceClient.callRaw`'s trailing `inspectBeforeBody` parameter is still unruled.**~~
>   **RULED 2026-08-12: it stays as-is.** See the ratification note in M3.5b's STATUS. `aws-core` has
>   no released version and no external consumer, so the binary incompatibility has no blast radius,
>   and the ceiling it enables guards a failure mode that is otherwise silent in Lambda.
> - **Nothing is committed or pushed.** The whole 3.0 line still exists only on this machine.
> - **The live AWS resources the smoke created are still in place** in account 443844975891:
>   table `awskt-native-smoke`, role `awskt-native-smoke-role`, layer `awskt-native-libcrypt:1`, and
>   function `awskt-native-smoke`. They are cheap (PAY_PER_REQUEST, no provisioned concurrency) and
>   re-used by the script, but they are real and were not there before.

---

### M8 — Secrets Manager and KMS data planes (2 days)

Added 2026-08-14, after M7 landed. See the §2 "Out of scope" entry for why these were excluded and
why that exclusion was wrong. Both services speak **AWS-JSON 1.1**, the dialect M6 already proved
with EventBridge, so this milestone adds two modules and one shared serializer and changes nothing
below them.

**Scope, stated as a test rather than a list**: an operation is in if it reads, writes or uses
secret *material*. Everything that manages the *existence* of a key or a secret is out.

- `aws-secretsmanager`: `GetSecretValue`, `BatchGetSecretValue`, `PutSecretValue`.
- `aws-kms`: `Encrypt`, `Decrypt`, `ReEncrypt`, `GenerateDataKey`,
  `GenerateDataKeyWithoutPlaintext`, `GenerateRandom`, `Sign`, `Verify`.
- Out: `CreateSecret`/`DeleteSecret`/`DescribeSecret`/`RotateSecret`, `CreateKey`/
  `ScheduleKeyDeletion`/`CreateAlias`/`CreateGrant`. Infrastructure provisions these; a Lambda
  consumes them. All reachable through the extension seam (Decision 18).
- Out: KMS `Recipient`/`CiphertextForRecipient` (Nitro Enclaves — not a target this repo builds
  for), `GenerateDataKeyPair`, `GenerateMac`/`VerifyMac`, and Secrets Manager's
  `UpdateSecretVersionStage`. The last one is the closest call: a rotation function needs it, but
  it needs `DescribeSecret` too, and shipping half a rotation flow is more misleading than shipping
  none of it.

**Tasks**

- [x] `Base64BlobSerializer` in `aws-core` (`Blobs.kt`). AWS-JSON blobs are base64 strings and both
      new modules carry them — KMS five of them, Secrets Manager one. In `aws-core` rather than
      duplicated, because two implementations of the same wire rule drift. Deliberately not shared
      with `aws-dynamodb`, whose `B`/`BS` base64 lives inside `AttributeValueSerializer`'s
      polymorphic union and cannot delegate to a primitive serializer.
- [x] `aws/aws-secretsmanager`: protocol, three operations, typed errors, `getSecretString` /
      `putSecretString` / `getSecretValues` conveniences.
- [x] `aws/aws-kms`: protocol, eight operations, typed errors, `verifySignature` /
      `encrypt` / `decrypt` conveniences.
- [x] Both declare `jvm, linuxX64, linuxArm64, macosArm64` from birth. No M7-style target-addition
      task exists for them.
- [x] JVM ABI dumps (`api/aws-kms.api`, `api/aws-secretsmanager.api`) checked in, and `aws-core.api`
      updated with the one new public object.
- [ ] **klib ABI dumps are NOT generated** — see the STATUS note. `checkLegacyAbi` is red until they
      are, on all three modules.

**Four decisions worth recording, because none of them is obvious from the diff**

1. **Nothing carrying secret material is a `data class`.** `aws-s3`'s rule ("nothing carrying a
   `ByteArray`") extends to `SecretString`, which is an ordinary `String` and is the *plaintext
   secret itself*. A generated `toString()` puts it in CloudWatch Logs from any
   `logger.info("$response")`, where it outlives the incident by the log group's retention period.
   Every secret-bearing type has a hand-written `toString()` that reports presence or a byte count,
   asserted by tests that fail if the redaction regresses. Secrets Manager reports **presence
   only**; KMS reports a byte count, because there the bytes are ciphertext or an opaque payload
   whose size is the useful diagnostic and a secret's *length* is a free hint to a brute-forcer.

2. **`PutSecretValue` is `IDEMPOTENT`, and the token is what makes it true.** The
   `ClientRequestToken` is minted **once per call, before the first attempt**, and reused verbatim
   across retries — the same construction `aws-dynamodb` uses for `TransactWriteItems`. Under a
   stable token an ambiguous replay of byte-identical content is a defined no-op returning the
   version the first attempt created. A token minted *inside* the loop would make every retry a
   fresh write and burn a secret version per attempt. There is a test that fails if that regresses.

3. **KMS's algorithm and key-spec fields are `String`, not `enum class`** — a deliberate departure
   from `aws-dynamodb`'s `ReturnValue` and `Select`. Those are closed sets this library only ever
   *sends*; KMS's come back on responses too, and AWS extends them (`SM2PKE`, `SM2DSA` postdate the
   original set). An enum on a response field turns "AWS added an algorithm" into a
   `SerializationException` for every caller, including callers not using it, fixable only by a
   release. Documented values are `const val`s on `EncryptionAlgorithm`, `SigningAlgorithm`,
   `DataKeySpec` and `MessageType`.

4. **`getSecretValues` exists because `BatchGetSecretValue` reports failures inside an HTTP 200.**
   The third instance of this trap in the library, after EventBridge's `PutEvents` and DynamoDB's
   `UnprocessedKeys`, and it is solved the same way: the helper chunks to 20, follows `NextToken`
   under a page bound, returns entries in **request order**, and raises
   `BatchGetSecretValuePartialFailureException` rather than returning a list that is silently short.
   Unlike EventBridge's version this failure is fully recoverable — reading a secret has no side
   effect — so the partition is carried for the caller to act on rather than merely to explain.

**Two `aws-core` behaviours left deliberately unchanged, and documented at the point of surprise**

- **KMS's `LimitExceededException` is retried as throttling and should not be.** It is in
  `KNOWN_ERROR_TYPES` from the AWS SDK's shared table, where it means "you are going too fast"; for
  KMS it means "you have too many keys", a standing condition no backoff clears. Special-casing it
  means either editing a table every service shares or adding a per-service override that exists
  for one code — both cost more than the seconds they save on a request that is failing anyway.
  KMS's actual rate limit is `ThrottlingException`, which the same table paces correctly.
- **KMS says `NotFoundException`, not `ResourceNotFoundException`**, so `NEVER_RETRY_CODES` does not
  match it. Harmless — KMS answers 400 and no status rule fires — but a reader comparing the two
  new modules should know the protection comes from a different place in each.

**Verification**: `./gradlew :aws:aws-kms:jvmTest :aws:aws-secretsmanager:jvmTest` — **22 + 30 = 52
tests, 0 failures**.

> **STATUS: M8's CODE IS COMPLETE (2026-08-14). TWO VERIFICATION STEPS COULD NOT BE RUN AND ARE
> OUTSTANDING — this milestone is not finished until they are.**
>
> **1. Neither module has been compiled for any native target, and the ABI dumps are half-written.**
> Not a property of the code: the authoring host could not reach `download.jetbrains.com`, which is
> blocked by egress policy, so the Kotlin/Native distribution never downloaded and every
> `compileKotlinLinux*` / `compileKotlinMacos*` task failed before it started. `updateLegacyAbi`
> fails for the same reason — the klib dump *is* a native compilation — so what is checked in is the
> **JVM `.api` dumps only**. That leaves `checkLegacyAbi` red on three modules: `aws-kms` and
> `aws-secretsmanager` have no `.klib.api` at all, and **`aws-core`'s existing one is now stale**,
> missing `Base64BlobSerializer`.
>
> The first thing to run on a host with network access, before anything else in this milestone is
> believed:
>
> ```
> ./gradlew :aws:aws-core:updateLegacyAbi :aws:aws-kms:updateLegacyAbi \
>           :aws:aws-secretsmanager:updateLegacyAbi
> ./gradlew :aws:aws-kms:build :aws:aws-secretsmanager:build
> ```
>
> The risk that native compilation actually fails is low — both modules are pure `commonMain` with
> no `expect`/`actual` and no platform API beyond `kotlin.io.encoding.Base64`, and their build files
> are `aws-eventbridge`'s with the names changed — but low is not zero, and M6's own STATUS block
> records a native source set that was configured, reported green, and compiled nothing for days.
> Do not mark this line done from a JVM-green build.
>
> **2. There is no SDK differential harness for either module, unlike M3, M3.5 and M6 — and this
> one is a deliberate omission rather than a blocked step.** Those milestones compared our wire
> bytes against `aws.sdk.kotlin`'s for a protocol we were implementing for the first time.
> Here the protocol is the one M6 shipped and the differential already proved: same `awsJson1_1`
> factory, same `AwsJsonErrorParser`, same `callJson`. What is genuinely new is the **base64 blob
> encoding**, and that is covered directly — `KmsBlobEncodingTest` asserts the exact base64 string
> on the wire and the exact bytes back — rather than transitively through an SDK comparison.
>
> What a differential *would* still catch is a wrong `@SerialName` on a field neither the tests nor
> a reviewer noticed. Adding one is cheap (`aws.sdk.kotlin:secretsmanager` is already in the version
> catalog; KMS is not) and is the obvious next increment if either module misbehaves against the
> real service. **No live or LocalStack suite has been run against either module**: this milestone
> was verified against `MockEngine` only. That is the honest limit of what "52 tests, 0 failures"
> means here.
>
> **`env`'s `SecretsManagerSecretsProvider` was not rewired**, and that is scope, not oversight. It
> lives in `env/src/jvmMain`, still uses the AWS SDK, and still works. Pointing it at
> `aws-secretsmanager` would let `env` drop its `compileOnly(libs.aws.secretsmanager)` and would
> make the provider available on native — which is the natural follow-on, and a change to a
> published module's dependency graph that deserves its own commit rather than riding along here.

---

### M9 — SQS, SNS and EventBridge Scheduler data planes (3.5 days)

Added 2026-08-14, immediately after M8. Where M8 was two modules speaking a protocol the library
already had, **M9's three services speak three different wire formats and only one of them was
already supported.** That is the whole shape of this milestone, and it is why it costs more than
M8 despite covering a comparable number of operations.

| Service | Protocol | What it cost |
|---|---|---|
| **SQS** | `awsJson1_0`, target `AmazonSQS` | Nothing new. DynamoDB's dialect exactly. |
| **Scheduler** | `restJson1` | Two typed-call helpers and a protocol factory in `aws-core`. |
| **SNS** | `awsQuery` — form-encoded in, **XML** out | A hand-written codec in the service module. |

**Protocols were verified against the AWS SDK's own artifacts, not from memory.** `sns-jvm`,
`sqs-jvm` and `scheduler-jvm` were fetched from Maven Central and their generated serializers read.
That was not ceremony — it corrected three things this plan would otherwise have got wrong:

- SQS is **no longer** an `awsQuery` service. AWS added a JSON protocol in 2023, so what would have
  been the second hand-written query codec is instead the cheapest module in the milestone.
- SNS's error codes are **not** its Smithy shape names. The query protocol renames them, and the
  renaming is not mechanical: shape `InvalidParameterValueException` arrives as wire code
  `ParameterValueInvalid`, with the words reversed. All fifteen were read out of
  `PublishOperationDeserializerKt`.
- Scheduler spells one concept three ways. `GetSchedule` and `DeleteSchedule` take `groupName`;
  `ListSchedules` takes **`ScheduleGroup`**. Memory said `GroupName` for the third.

**Scope**

- `aws-sqs`: `SendMessage`, `SendMessageBatch`, `ReceiveMessage`, `DeleteMessage`,
  `DeleteMessageBatch`, `ChangeMessageVisibility`, `ChangeMessageVisibilityBatch`, `GetQueueUrl`.
  Queue lifecycle is out.
- `aws-sns`: `Publish`, `PublishBatch`, plus mobile push endpoints — `CreatePlatformEndpoint`,
  `GetEndpointAttributes`, `SetEndpointAttributes`, `DeleteEndpoint`,
  `ListEndpointsByPlatformApplication`. Topic and subscription lifecycle is out, as is *platform
  application* management. See the mobile push addendum below.
- `aws-scheduler`: `CreateSchedule`, `GetSchedule`, `UpdateSchedule`, `DeleteSchedule`,
  `ListSchedules`. Schedule *group* management and tagging are out.

**"Data plane" had to be redefined for Scheduler, and the redefinition is the interesting part.**
KMS has operations against keys somebody else provisioned; SQS has messages moving through queues
somebody else created. Scheduler has no such split — **a schedule is the data.** Applications create
schedules at runtime as a matter of course ("remind this user in three days" is a `CreateSchedule`
in a request handler). So the line drawn is *per-schedule operations in, schedule-group management
out*, on the grounds that a group is provisioned infrastructure the way a queue or a topic is. Said
plainly here because "data plane" was the word in the request and it does not transfer to this
service unexamined.

**Tasks**

- [x] `aws-core`: `AwsProtocol.restJson1()` and `AwsProtocol.awsQuery()` factories.
- [x] `aws-core`: `callRestJson` and `callRestJsonNoBody` in `TypedCalls.kt`, with their own tests.
- [x] `aws/aws-sqs`, `aws/aws-sns`, `aws/aws-scheduler`, each declaring `jvm, linuxX64, linuxArm64,
      macosArm64` from birth.
- [x] JVM ABI dumps for all three, and `aws-core.api` updated with its four new declarations.
- [ ] **klib ABI dumps are NOT generated** — same blocked step as M8. `checkLegacyAbi` is red on all
      four modules until they are.

**What `aws-core` did and did not have to grow**

`restJson1` needed real additions, and they were small because M2's protocol seam and M3.5's
`AwsErrorParser` strategy had already done the structural work: a REST-shaped service needs a
content type, no target header, and a call helper that takes a method and a path. **The plan's own
M2 note called restJson1 "a second codec into `aws-core`" and used that as a reason to defer
AppConfigData. That was stale by M3.5** and is now demonstrably so — it is four lines and two
functions, not a codec.

`awsQuery` needed **nothing** in `aws-core` beyond a three-line factory, and that is the seam
working as designed: `AwsProtocol`'s constructor is public, and `RestXmlErrorParser` — named for the
protocol it was written for, not the only one it fits — already parses a query-protocol
`<ErrorResponse>` correctly. The AWS SDK reaches the same conclusion, calling
`parseRestXmlErrorResponse` from its own awsQuery deserializers.

**The query codec was deliberately NOT generalized into `aws-core`.** `aws-sns/Wire.kt` hand-writes
the form encoder and a ~60-line XML scanner, because the module has *two operations*. A general
query encoder — flattened lists and maps, the `flattened` trait, nested structures, namespaces — is
a large amount of machinery for one consumer. If a third query-protocol service ever arrives, that
is the moment to generalize; the file says so, so the decision is not re-litigated from scratch.

**Decisions worth reviewing**

1. **Retry safety is derived from the request in three more places.** `SendMessage`,
   `ReceiveMessage` and `Publish` are all `NOT_IDEMPOTENT` *unless* the request carries a
   de-duplication token (`MessageDeduplicationId`, `ReceiveRequestAttemptId`), in which case they
   are `IDEMPOTENT`. This is `aws-dynamodb`'s `writeSafety` construction applied three more times,
   and it is the correct reading each time: without a token a replayed publish fans out twice, and
   there is nothing to delete afterwards.
   - One case is knowingly treated as unsafe when it is not: a FIFO queue with **content-based
     deduplication** needs no explicit id, but that is a *queue attribute* and invisible from the
     client, so this does not guess. Documented at the call site.
   - `ReceiveMessage`'s reasoning is the subtle one and is written out in its KDoc: a replay is not
     dangerous, it simply does not return *the same answer*, and the messages from the lost response
     are invisible for a whole visibility timeout.
2. **`SqsConfig.httpTimeouts` deliberately defaults higher than the rest of the library** — 90
   seconds against the usual 30. SQS is the one service here where the client is *supposed* to sit
   idle on an open socket: `ReceiveMessage` long-polls for up to 20 seconds by design. Against the
   30-second default a maximum-length poll leaves 10 seconds for connection setup, TLS and the
   response, and a loaded client spends it. The failure looks like SQS being slow rather than a
   misconfiguration — the poll dies at 30s, its messages stay queued, and throughput collapses with
   no service error anywhere.
3. **The batch partial-failure helper appears for the third and fourth time**, and both new ones are
   *better* than `putEventsAll`: SQS and SNS both report `SenderFault` per entry, so the
   retryable/terminal split comes from the service rather than from a hand-maintained list of codes
   that can drift. `sendMessagesAll`, `deleteMessagesAll` and `publishAll` all chunk to 10, resubmit
   non-sender-fault entries with backoff, stop the whole call on a sender fault, and raise a typed
   partial-failure carrying both halves in request order.
   - All three additionally reject **duplicate batch ids across the whole call** before sending
     anything. SQS and SNS reject duplicates within one request, but these helpers chunk, so two
     duplicates in different chunks are two individually-valid requests and a result that cannot be
     paired back up. Only a whole-list check catches it.
   - `deleteMessagesAll`'s failure is the one that is easy to under-rate: a delete that silently
     half-succeeds is a message redelivered when its visibility timeout lapses and processed a
     second time, hours later, with nothing connecting the two events.
4. **Two exception names were changed to avoid collisions that would have been silent.** SQS's
   `UnsupportedOperation` would naturally have become `UnsupportedOperationException`, which
   **shadows `kotlin.UnsupportedOperationException` inside the package** — so a `catch` in that
   package would quietly change meaning. It is `UnsupportedQueueOperationException`. Both services'
   `KMS*` error families collapse onto one type each, named `QueueEncryptionKeyException` and
   `TopicEncryptionKeyException` rather than `KmsException`, which would have clashed with
   `aws-kms`'s type for anyone importing both. (Note SNS capitalizes `KMS` where SQS writes `Kms`;
   the services genuinely disagree and each module matches its own.)
5. **`UpdateSchedule` is a replace, not a patch**, and that is a data-loss footgun rather than an
   API quirk: an update meaning to change only the cron expression, and sending only the cron
   expression, clears the description, timezone, retry policy and dead-letter queue.
   `GetScheduleResponse.toUpdateRequest()` exists solely so the read-modify-write is one line, and
   it throws rather than building an update that would clear the target.
6. **Two Scheduler target blocks are `JsonElement` passthroughs.** `EcsParameters` and
   `SageMakerPipelineParameters` would mean modelling ECS's entire task-launch surface — VPC
   config, placement constraints and strategies, capacity providers — some forty types describing a
   *different service* that this module would then own and track. The other five parameter blocks
   are typed normally. What is given up is compile-time checking on those two.
7. **SQS's legacy query error codes are not requested.** SQS still serves them behind an
   `x-amzn-query-mode: true` header, and every SQS doc and Stack Overflow answer uses that
   vocabulary (`AWS.SimpleQueueService.NonExistentQueue`). This client sends modern shape names
   instead (`QueueDoesNotExist`) because query mode exists for SDKs that already shipped the old
   codes and would break their users — which this client has none of. Each typed exception names its
   legacy equivalent in its KDoc so the search still lands.
8. **`PublishRequest.toString()` redacts the phone number and never prints the message.** M8's
   redaction rule extended: SNS payloads are routinely customer data and a `phoneNumber` is *always*
   personal data, so a logged request object is a privacy incident rather than a debugging
   convenience.

**Verification**: `./gradlew :aws:aws-sqs:jvmTest :aws:aws-sns:jvmTest :aws:aws-scheduler:jvmTest
:aws:aws-core:jvmTest` — **24 + 27 + 20 = 71 new tests, 0 failures**, plus `aws-core` at 146
including 11 new ones covering the restJson1 and awsQuery seams.

> **STATUS: M9's CODE IS COMPLETE (2026-08-14). THE SAME TWO VERIFICATION GAPS AS M8 APPLY, PLUS
> ONE THAT IS SPECIFIC TO SNS AND MATTERS MORE HERE.**
>
> **1. No native compilation and no klib ABI dumps.** Identical cause to M8: `download.jetbrains.com`
> is blocked by egress policy on the authoring host, so the Kotlin/Native distribution never
> downloaded and `updateLegacyAbi` — whose klib half *is* a native compilation — cannot run. JVM
> `.api` dumps are checked in for all three new modules and `aws-core.api` is updated;
> `checkLegacyAbi` is red on those four until the klib dumps are generated. Run, on a networked host:
>
> ```
> ./gradlew :aws:aws-core:updateLegacyAbi :aws:aws-sqs:updateLegacyAbi \
>           :aws:aws-sns:updateLegacyAbi :aws:aws-scheduler:updateLegacyAbi
> ./gradlew :aws:aws-sqs:build :aws:aws-sns:build :aws:aws-scheduler:build
> ```
>
> **2. No live or LocalStack run.** MockEngine only, as for M8.
>
> **3. `aws-sns` is the module in this library that most needs a differential harness, and does not
> have one.** For every other module the argument against one is that the protocol was already
> proven by an earlier milestone. That argument does **not** apply here: this is the only
> hand-written form encoder and the only hand-written XML reader in the repo, and both were built
> from the wire format rather than inherited from a tested seam. The unit tests are correspondingly
> specific — they assert `%20` rather than `+` for a space, 1-based `entry.N` and `member.N`
> indices, `Successful`/`Failed` sibling scoping, self-closing empty lists, and ampersand-last
> entity unescaping — but every one of them asserts against *what this code was written to
> produce*, which is exactly the thing a differential would independently check.
>
> `aws.sdk.kotlin:sns` is not currently in the version catalog. Adding it as a `jvmTest`-only
> dependency and comparing our form body against the SDK's, using M3's technique unchanged, is the
> obvious next increment and the first thing to do if SNS misbehaves against the real service.

#### M9 addendum — SNS mobile push (iOS and Android)

Added 2026-08-14, same day, on request. Extends `aws-sns` rather than adding a module: mobile push
*is* SNS, and a separate artifact would split one service's client in two.

**In scope: platform endpoints.** A **platform application** registers *your app* with APNs or FCM,
is created once from a signing key, and holds a secret — provisioning, and out of scope for the same
reason `CreateTopic` is. A **platform endpoint** registers *one device*, is created at runtime on
every install and on every token rotation, and is where all the difficulty lives. Five operations:
`CreatePlatformEndpoint`, `GetEndpointAttributes`, `SetEndpointAttributes`, `DeleteEndpoint`,
`ListEndpointsByPlatformApplication`.

**Two traps absorbed, and they are the reason this is more than five thin wrappers.**

1. **`registerDevice` — device registration is not one call, and the error path carries data.**
   `CreatePlatformEndpoint` on a token already registered *with different attributes* does not
   return the existing endpoint; it raises `InvalidParameter` **with the ARN inside the message
   text**, and AWS's own documented procedure (the pseudo-code in the SNS developer guide) is to
   regex it out. Worse, the *success* path is not safe either: a token registered with matching
   attributes returns the existing ARN **without re-enabling it**, and SNS disables endpoints by
   itself whenever APNs or FCM rejects a token. So an app that registers once at install and never
   again silently stops receiving notifications, with nothing erroring anywhere.

   `registerDevice(platformApplicationArn, token)` does create → always-get → repair-if-needed, and
   returns an ARN guaranteed to hold that token and be enabled. Two API calls steady-state, three
   when a repair is needed; the second is **not** skippable, because nothing in the create response
   distinguishes "created new and enabled" from "returned an endpoint APNs disabled last week".
   Parsing an error message is fragile and there is no alternative — if AWS rewords it, the helper
   stops recognising the case and rethrows the original exception rather than guessing.

2. **`mobilePushMessage` — a push payload is double-encoded JSON.** The per-platform payloads are
   JSON documents carried as **strings** inside a JSON envelope:
   `{"default":"…","APNS":"{\"aps\":{…}}"}`. Writing the natural nested-object form is rejected with
   an error that does not explain the shape, and building the envelope by string concatenation —
   the other common approach — breaks the first time a notification body contains a quote or a
   newline, which for user-generated content is immediately. The builder goes through a real JSON
   encoder so the escaping is not this library's opinion. `apnsAlert()` and `fcmNotification()`
   build the two common payloads; both document that the schemas are Apple's and Google's, not
   AWS's, and that anything beyond a plain alert should be built by the caller.

**Three smaller things worth knowing, all documented at the point of use**

- **`GCM` means FCM.** Google retired GCM in 2018; SNS never renamed it, in ARNs or in envelope
  keys. There is no `FCM` value on the wire. `PushPlatform.FCM` is an alias with `"GCM"` behind it.
- **`APNS` and `APNS_SANDBOX` are different platform applications**, not a flag. A development
  build's token registered against production APNs does not fail at registration — it fails
  silently at delivery, which is the most common "push doesn't work on my debug build" cause.
- **`Enabled` is the string `"true"`/`"false"`**, not a JSON boolean.
  `EndpointAttributes.enabled` parses it; comparing the raw value to a Kotlin `Boolean` is a bug
  that reads as correct.

**Endpoint attribute maps use `entry.N.key`/`entry.N.value`** — the query protocol's *default* map
spelling — where `MessageAttributes` uses `entry.N.Name`/`.Value`. The two genuinely differ, and the
wrong spelling produces a request SNS accepts and silently ignores. Verified against the SDK:
`PublishOperationSerializer` carries a `FormUrlMapName("Name","Value")` trait and
`CreatePlatformEndpointOperationSerializer` carries none.

**One earlier decision in this plan was revised, deliberately.** `aws-sns`'s build file previously
recorded that the module had no `kotlinx-serialization-json` dependency, because SNS's protocol has
no JSON in it. That reasoning is still right *about the protocol* and was wrong as a rule for the
module: the push envelope is **application data SNS carries opaquely**, not wire framing, and
hand-rolling its escaping to preserve a dependency boundary would trade correctness for tidiness.
The rule is narrowed rather than dropped — **no `@Serializable` and no JSON on the request/response
path**, `Wire.kt` remains the only protocol codec — and the build file says so.

**Verification**: `aws-sns` is now at **51 tests, 0 failures** (24 new). The registration dance is
tested through all five of its paths: first registration, ARN recovery from the error message,
re-enabling a disabled endpoint, updating a rotated token, and leaving a healthy endpoint untouched
— plus rethrowing an unrelated `InvalidParameter`. The envelope is tested for string-not-object
values and for quote/newline round-tripping through both layers of encoding.

> **The `fcmNotification` payload shape is the one thing here that could not be settled from the SDK
> jar**, because it is a *service* behaviour rather than a wire format. It emits the legacy
> `{"notification":{…}}` shape under the `GCM` key. AWS migrated the transport behind that key to
> FCM HTTP v1 in 2024 and states that existing payloads continue to work by translation, so this
> should be correct — but it is documentation rather than something read out of an artifact, and it
> is the first thing to check if Android notifications arrive empty. The raw
> `platform(PushPlatform.GCM, json)` path is unaffected either way.

---

### M10 — Bedrock Runtime: Converse and ConverseStream (4 days)

Added 2026-08-14, on request. The first module in this library that needs something `aws-core` did
not have: **a streaming response**.

**Scope**: `Converse` and `ConverseStream`. `InvokeModel` and `InvokeModelWithResponseStream` are
out, and the reason is not effort — they take whatever JSON the chosen model's provider defined, so
a typed client for them would be a typed wrapper around an untyped blob and switching models would
mean rewriting the request. Converse is the model-independent API and therefore the only one a
portable typed client can be written against. Both remain reachable through the extension seam.

**This partially advances the "streaming deferred to v2" decision, and says so rather than
pretending otherwise.** §2 defers streaming request *and* response bodies. M10 delivers
**responses only**: `AwsServiceClient.callStreaming` hands a `ByteReadChannel` to a caller-supplied
consumer, while the request body remains a materialized `ByteArray`, so none of the chunked-signing
machinery a streaming *request* needs exists. That is enough for every AWS event-stream service and
is deliberately **not** enough for S3's `GetObject`, which additionally wants a memory ceiling, a
`Range` interaction and a truncation check. `aws-s3` is untouched.

**Tasks**

- [x] `aws-core`: `AwsServiceClient.callStreaming`, with retry semantics narrower than `callRaw`'s.
- [x] `aws-core`: `EventStream.kt` — `vnd.amazon.eventstream` frame decoding with both CRCs, and a
      hand-written IEEE CRC-32.
- [x] `aws-core`: `signAttempt` extracted from `callRaw`'s loop so both paths sign identically.
- [x] `aws/aws-bedrock-runtime`: Converse, ConverseStream, the `ContentBlock` union, tool calling,
      `accumulate()`.
- [x] JVM ABI dumps for both; klib dumps still blocked.

**Where the event-stream decoder lives, and why that is not inconsistent with M9**

In `aws-core`, whereas M9 kept SNS's query codec inside `aws-sns`. The two look like the same call
and are not. SNS's form encoder is shaped by SNS's *service* model — which structures flatten, which
map spelling each field wants — so generalizing it means generalizing SNS. Event-stream framing has
**no service content whatsoever**: the same frames carry Bedrock's `ConverseStream`, Kinesis's
`SubscribeToShard`, S3's `SelectObjectContent` and Transcribe's streaming, and the decoder cannot
tell them apart. The deciding argument is smaller still: `callStreaming` hands out a raw
`ByteReadChannel`, and every AWS service that streams frames it this way, so shipping the transport
without the decoder ships half a tool.

**Retry semantics for a stream, which are narrower than `callRaw`'s and have to be**

A stream is retryable right up until the first byte of a **successful** body is handed out, and not
afterwards. Transport failures before a response retry normally; a non-2xx has its (small, bounded)
body materialized and is classified and retried normally; a 2xx is handed to the consumer and
**nothing that happens inside the consumer is ever retried**. There is deliberately no `validateBody`
equivalent — `callRaw` can offer one because it holds the whole body before deciding, and a stream
has no such moment.

> **A test caught a real bug here, and it is worth recording rather than quietly fixing.** The first
> implementation let an exception thrown by the consumer propagate into the transport `catch`, where
> `classifyTransportFailure` answered AMBIGUOUS — as it does for anything unrecognised — and
> retried. The observable effect: Bedrock reporting `ModelStreamErrorException` half-way through a
> generation caused the **entire call to be replayed**, so the caller saw a partial answer, then a
> second different partial answer, and was billed for both. Fixed with a `ConsumerFailure` marker,
> the same technique `callRaw` already used for `InspectionRefusal` — the precedent existed and the
> first implementation simply did not apply it. The test that caught it
> (`eventsBeforeAMidStreamFailureAreStillDelivered`) asserts the event count, which is why it caught
> it at all: an assertion on the exception type alone would have passed.

**Decisions worth reviewing**

1. **`ContentBlock` is a sealed hierarchy *with* an `Unknown` arm.** A closed sealed union would
   have the failure mode `aws-kms`'s KDoc argues against for enums — AWS adds variants, and
   `reasoningContent`, `citationsContent` and `cachePoint` all postdate the API's launch. `Unknown`
   carries an unrecognised variant as raw JSON, so the exhaustive `when` survives *and* forward
   compatibility does. The AWS SDK reaches the same conclusion, generating its own `SdkUnknown`.
2. **Round-tripping is a correctness requirement, not a nicety.** Converse is stateless, so a
   multi-turn conversation resends the assistant's previous turns — and for reasoning models the
   reasoning block must be echoed back byte-for-byte, signature included, or the turn is rejected.
   That is why `ContentBlock.Reasoning` keeps its raw `JsonElement` rather than decomposing into
   fields, and why `Unknown` exists at all. Tested by asserting that encode→decode→encode is
   byte-identical for both.
3. **`accumulate()` accumulates per `contentBlockIndex`, not over concatenated text.** A reply can
   have several blocks open at once — reasoning alongside text, or two tool calls — and deltas carry
   the index they belong to. A naive fold produces the right answer for plain text and splices two
   tool calls into unparseable JSON for the interesting case. Tool input compounds it: Bedrock
   streams it as **partial JSON text**, so a single delta is usually not valid JSON on its own.
   There is a test for the interleaved-two-tools case specifically.
4. **`BedrockRuntimeConfig.httpTimeouts` defaults far higher than the library's** — 120s socket,
   600s request, against 30s/30s. A long generation legitimately takes minutes, and during a stream
   the socket sits idle between tokens whenever the model pauses. The socket timeout bounds the gap
   *between* bytes rather than the whole response, which is why it is set generously rather than
   disabled: a genuinely hung connection should still fail.
5. **`retryConfig` is documented as a thing to tune *down*.** Every retry is a second full
   generation, billed in full; the library's four-attempt default is right for a `GetItem` and is a
   4× bill ceiling here.
6. **The signing name is `bedrock`, the endpoint prefix is `bedrock-runtime`.** They differ, and
   signing against the wrong one is a `SignatureDoesNotMatch` that says nothing about which.
7. **Model ids are percent-encoded whole**, and here that is not ceremonial as it was in
   `aws-scheduler`: an inference-profile ARN contains `/` and occupies one path segment, so left raw
   it would split the path and address an operation that does not exist.

**Verification**: `./gradlew :aws:aws-bedrock-runtime:jvmTest :aws:aws-core:jvmTest` — **33 new
Bedrock tests and 26 new `aws-core` tests, 0 failures**; `aws-core` now at 172. The event-stream
decoder is tested against frames built independently from the specification, and the CRC-32 is
checked against its **published check value** (`123456789` → `0xCBF43926`) rather than against
itself — without that, a wrong polynomial would produce frames the decoder happily accepts and AWS
rejects, and every test would still pass.

> **STATUS: M10's CODE IS COMPLETE (2026-08-14). The same two gaps as M8 and M9 apply, plus one
> specific to streaming.**
>
> **1. No native compilation, JVM ABI dumps only.** Identical cause: `download.jetbrains.com` is
> blocked by egress policy. `checkLegacyAbi` is red on `aws-core` and `aws-bedrock-runtime` until
> the klib dumps are generated on a networked host.
>
> **2. No live or LocalStack run.** MockEngine only — which for this module means **no real model
> has ever been invoked through this client**. Everything below the wire format is unexercised.
>
> **3. `callStreaming` has never run against a real chunked HTTP response.** `MockEngine` serves the
> whole body at once, so the tests prove the *decoder* handles a stream of frames and do **not**
> prove the transport handles a body that arrives slowly, in arbitrary chunk boundaries, across many
> seconds. Frame boundaries falling mid-read is exactly the case a mock cannot produce. That is the
> single highest-value thing to test next, and a LocalStack or live `ConverseStream` against a real
> model is the way to do it.
>
> Also unverified, and cheap to add later: no SDK differential for Converse. The protocol is
> restJson1, which M9 established, but the `ContentBlock` union encoding is new and hand-written, and
> a differential against `aws.sdk.kotlin:bedrockruntime` would check it independently of the tests
> that were written alongside it.

---

### M11 — Live smoke coverage for M8–M10 (1 day)

Added 2026-08-14. M8, M9 and M10 each shipped with the same recorded gap — "no live or LocalStack
run, MockEngine only" — and this closes the *ability* to run one. It does not close the gap itself:
see the status note.

**Two suites, covering different things.**

| | `Live*Test` | `lambda-native-smoke` `services` mode |
|---|---|---|
| Runs on | JVM (CIO) and macosArm64 (Curl) | a deployed Lambda, **linuxArm64** |
| Proves | the wire format against real AWS | the same, *on the architecture that ships* |

The second is not redundant with the first. `linuxArm64` is a **Tier 2 Kotlin/Native target with
test execution unsupported**, so nothing else in this repository executes one line of code on
Graviton — the same argument that justified the smoke function at M7, now extended to six more
clients.

**Tasks**

- [x] `Live*Test` in `aws-kms`, `aws-secretsmanager`, `aws-sqs`, `aws-sns`, `aws-scheduler` and
      `aws-bedrock-runtime`, each in `commonTest` and each self-skipping without credentials —
      following `aws-core`'s existing `LiveTransportTest` idiom exactly.
- [x] Six new probes in the native smoke function's `services` mode, each independently gated.
- [x] `docs/live-smoke.md` — what to set, what to run, what a fixture needs.

**Two properties that are load-bearing rather than tidy**

1. **Everything cleans up after itself.** SQS messages are received and deleted; Scheduler schedules
   are deleted in a `finally`. A live suite that leaks a schedule per run eventually trips the
   per-group account quota — exactly what `ServiceQuotaExceededException`'s KDoc warns about — and
   would break the account it runs in rather than merely failing.
2. **Secrets Manager is read-only on purpose.** `PutSecretValue` creates a retained version on every
   call, so exercising it live would accumulate versions against a quota. Its idempotency-token
   behaviour is asserted hermetically instead, where the assertion is about *our request* rather
   than about the service's storage — which is the half worth testing anyway.

**No probe is added to the smoke function's required environment.** An existing deployment has
`AWS_REGION`, `SMOKE_TABLE_NAME` and `SMOKE_BUCKET_NAME` and must keep working untouched; an unset
probe reports `skipped`, and `allOk` is false only when a probe *ran and failed*. The same payload
is therefore meaningful in an account where only some fixtures exist.

> **STATUS: the suites exist and are compile-verified. NOT ONE OF THEM HAS BEEN RUN AGAINST AWS.**
>
> The authoring host has no usable AWS credentials — the ones in its environment are placeholders
> that AWS rejects with `UnrecognizedClientException` — no Docker daemon, so LocalStack is not an
> option either, and no Kotlin/Native toolchain, so the smoke function cannot even be built here.
> **Every live suite in this commit has been executed exactly once: in skip mode.** That is the
> honest state, and the gap M8–M10 recorded is still open — what changed is that closing it is now
> one `./gradlew` invocation by someone with credentials rather than a piece of work.
>
> **The native smoke additions were compile-verified by a throwaway JVM module**, not by the native
> compiler. The 155 lines of probe code were extracted verbatim into a temporary `jvm()` module
> depending on the same six clients, compiled, and the module deleted. That checks every API call,
> named argument and import; it does **not** check native compilation of the module as a whole —
> the `native-lambda` plugin, target-specific linking, or anything platform-conditional. The probe
> code contains nothing platform-conditional, which is why the substitution is worth something, but
> it is a substitution.
>
> Run order, when someone has an account:
> 1. `./gradlew :aws:aws-sns:jvmTest` with `SMOKE_TOPIC_ARN` — the hand-written form encoder and XML
>    reader have no differential and no independent confirmation at all.
> 2. `./gradlew :aws:aws-bedrock-runtime:jvmTest` with `SMOKE_BEDROCK_MODEL_ID` —
>    `converseStreamReceivesFramesAcrossChunkBoundaries` is the only test that puts `callStreaming`
>    in front of a real chunked response.
> 3. The rest, then the deployed `services` probe on Graviton.

---

## 7. Corrected file triage for `dynamokt` (M5a)

The draft plan claimed "5 files migrate by deleting a single import line". Verified against source, the real split is:

| File | Draft claim | Reality |
|---|---|---|
| `DynamoKtIndex.kt`, `Pipes.kt`, `ItemContainer.kt` | no change | ✅ no change |
| `Item.kt`, `ItemUpdater.kt`, `Serialization.kt` | import deletion | ✅ import deletion (Serialization.kt also needs the Base64 + N-precision fixes) |
| `json.kt` | import deletion | ✅ moved to `:dynamo` |
| **`dates.kt`** | import deletion | ❌ **real edit + public API break** — `:7,8,65-67` java.time in public API |
| **`delegates.kt`** | import deletion | ❌ **real edit + public API break** — `:249,262` `cls.java.enumConstants`, public `KClass<T>` constructors |
| `DynamoKtSession.kt`, `Query.kt`, `MutableItem.kt`, `Transaction.kt`, `DynamoKt.kt`, `attributes.kt`, `ExpressionBuilder.kt` | real edit | ✅ real edit |

**9 real edits, not 7.** Plus `test/src/jvmMain/.../DynamoStreamRunner.kt`, which the draft classified as untouched.

---

## 8. Risk Register

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| 1 | ~~**API break not approved, or approved late.**~~ **RETIRED 2026-08-09** — approved for items (a)–(i), shipping as 3.0.x. Residual risk is now only that a *downstream* consumer is surprised, which the migration note and `.api` dumps address. ~22 signatures change across three published artifacts. | ~~CRITICAL → HIGH~~ → **LOW** | Approval obtained up front. `binary-compatibility-validator` `.api` dumps give reviewers the exact diff. **If withheld, ship M1 / M2 / M3.5 and hold M5a / M5b** — `aws-signing`, `aws-core` and `aws-s3` are all NEW artifacts with zero rows in the Q1 inventory, so they can go to production while approval is negotiated. Approval delay becomes a re-ordering rather than a project killer, which is arguably the most valuable side effect of admitting S3. Two caveats: the `S3Local` deletion is **not** part of the additive set (it is Q1 row (k) and must be held with M5a), and you still must not fall back to the mirror design, which takes the `CredentialsProvider` break anyway. |
| 2 | **The signer is never validated against a real AWS verifier.** LocalStack accepts `DummyKey`/`DummySecret` with IAM enforcement off; the differential harness short-circuits before the network; the vector corpus tests `sign()` in isolation. | CRITICAL | M0.5 spike puts a real signature in front of AWS on day 6, for **both** DynamoDB and S3. Live AWS smoke tests are **hard exit criteria** for M3, M3.5a and M3.5b. Harness asserts the full outbound header set, not just the body. The presign oracle is the strongest and cheapest in the project: verifying a presigned URL needs no AWS client, no credentials on the verifying side and no signer — just `HttpClient.get(url)`. |
| 3 | **`host` omits a non-default port.** Passes 100% of CI (all tests use ephemeral LocalStack ports; the AWS corpus has only default-port cases) and fails 100% of production. | HIGH | Specified as authority-with-port in M1 with a dedicated unit test. |
| 4 | **SigV4 fails opaquely.** `SignatureDoesNotMatch` names no rule. | HIGH | Three-level assertions across 74 case-assertions (37 header + 37 query); corpus code-generated into commonTest so the *native* signer is tested, unlike smithy-kotlin's. See Risk 25 for the one configuration the corpus does **not** cover. |
| 5 | **The signer does not see the bytes the engine sends.** Ktor re-encoding the path or adding `user-agent` after signing fails every request and points at the signer. | HIGH | Build from a resolved URL string, pass headers explicitly, pre-materialize the body as `ByteArray`, keep `user-agent`/`content-length` in the exclusion set, and assert captured outbound bytes in the harness. **Additionally**, `callRaw` re-reads `url.encodedPath` after building the request and asserts it equals the string that was signed — S3 object keys make this invariant load-bearing rather than defensive. |
| 6 | **Retry misclassification is a silent reliability regression.** DynamoDB throttling is HTTP 400. | HIGH | Verbatim classification table; never-retry deny-list ahead of it; hermetic MockEngine tests both ways; source comment at the loop. |
| 7 | **A write replayed after an ambiguous failure double-applies — or lies about a write that succeeded.** **AMENDED 2026-08-13; the original row was right about the mechanism and wrong about the blast radius.** It named only `UpdateItem` (`increment()` emits `ADD`, `addToList()` emits `list_append`) and concluded that `PutItem` and `DeleteItem` were safe because "a full overwrite replays to the same end state". That is true of the request shape it was written against and false of two others the same types expose. **(a) A condition expression.** A create guarded by `attribute_not_exists(pk)` lands on AWS, the response is lost mid-flight, and the replay evaluates the condition against the item the *first attempt* wrote — the caller gets `ConditionalCheckFailedException` for a write that succeeded. Note this is not a false alarm the caller can shrug off: for an idempotency guard or an optimistic-concurrency check, "somebody else got there first" is exactly the signal it exists to produce, so the caller takes the lost-race branch. **(b) `ReturnValues` reading prior state.** `ALL_OLD` returns the prior item on the first attempt and what the first attempt just wrote on the replay: same end state, different answer, and the answer is the entire reason the caller asked. Both are **silent** — indistinguishable from a genuine conflict. **(c) The same shape on the control plane**: a replayed `CreateTable` returns `ResourceInUseException` (reads as "that name is taken", not "you already own it"), a replayed `DeleteTable` returns `ResourceNotFoundException`. `aws-s3` got this right for `PutObject`/`ifNoneMatch` from the start; `aws-dynamodb` shipped the operation-name shortcut and has three ways to trip it instead of one. | HIGH | Safety is **derived from the request, never from the operation name** — `aws-dynamodb`'s internal `writeSafety(conditionExpression, returnValues)`, applied to `putItem` and `deleteItem`. `UpdateItem` stays unconditionally NOT_IDEMPOTENT and is deliberately *not* routed through it. `createTable`/`deleteTable` are unconditionally NOT_IDEMPOTENT; `describeTable` is a read and stays replayable. `ALL_NEW`/`UPDATED_NEW` describe the post-state and stay replayable; the `when` over `ReturnValue` is exhaustive with no `else`, so a new enum entry fails to compile rather than defaulting to replayable. Plus the pre-existing two-tier transport-exception classification and the `retryAmbiguousWrites` escape hatch. Twelve MockEngine tests in `AmbiguousWriteSafetyTest`, each verified to fail against the hardcoded version — including one end-to-end assertion that a conditional create never surfaces the `ConditionalCheckFailedException` its own retry caused, and five guards that the unconditional paths are still retried. |
| 8 | **A misspelled `@SerialName`.** The one failure mode hand-written DTOs have that codegen does not. | HIGH | Differential harness is task 2 of M3, both request and response sides, plus 100 LocalStack tests. |
| 9 | **M5a overruns.** 8,377 LOC in the blast zone, `ExpressionBuilder.apply` has no honest minimal equivalent. | HIGH | `SdkBackedDynamoDb` makes M5a landable and revertible independently of M5b. Do ExpressionBuilder first. Budget M5a+M5b as a 12.5-day unit. |
| 10 | **`AttributeValueSerializer` descriptor reindex corrupts the wire format.** Adding NULL/BS naively makes the existing `Bs` write emit under key `NULL`. | MEDIUM | Explicit ordered instruction in M5a §5a.1; round-trip test for `Bs` + `Null` written *before* touching the descriptor. |
| 11 | **`AttributeValue.B`/`Bs` equality.** A naive `data class` over `ByteArray` breaks `findDifferences` (`attributes.kt:53`) — and a repo-wide grep confirms **zero** existing tests touch B/BS/NULL/NS. | MEDIUM | Hand-written `equals`/`hashCode`; targeted test; response-differential corpus covers all 10 variants. |
| 12 | **Pagination-token incompatibility.** `AttributeValueSerializer` IS the external token format (`Query.kt:285` → `json.kt:13-24`). | MEDIUM | Freeze key names and Json config; NULL/BS is strictly widening; regression test decodes a 2.2.x-captured token. |
| 13 | **Someone "fixes" `N` precision the wrong way.** 2.3.x's patch series changed `toBigDecimal()` → `toDouble()`. That bug is sitting in the prior art and will be cherry-picked if nobody looks. Inbound is also broken today via `floatOrNull`. | MEDIUM | `JsonUnquotedLiteral` outbound, raw literal inbound, 38-digit round-trip test, explicit callout in the M5a PR. |
| 14 | **Retried transactions apply twice.** | MEDIUM | Token materialized outside the retry loop; MockEngine test asserts attempt 2 carries the same token. |
| 15 | **Credentials leak into logs.** `HttpLogPublisher` PUTs payloads to an arbitrary URL. | MEDIUM | Redacting `toString()`; canonical-request tracing behind an opt-in flag; unit test asserts the secret never appears. |
| 16 | **KMP artifact-coordinate change.** `awskt-dynamo`/`awskt-dynamokt`/`awskt-dynamokt-exposed` go from one jar to root-metadata + `-jvm`. Affects consumers who never touch `AttributeValue`. | MEDIUM | Listed in the approval gate. Check whether any consumer resolves via plain Maven; if so, publish a jvm-only relocation shim. |
| 17 | **Kotlin/Native runtime blockers are external and unfixed.** KTOR-7262 (CIO has no TLS on Native) Open; KT-55643 (libcrypt.so.1) Open, unassigned, no fix version. | MEDIUM — bounded | Curl with injectable `caInfo` is mandatory; commonMain never constructs an `HttpClient`. Publish the libcrypt layer as a versioned awskt artifact. |
| 18 | **The deployment target cannot be tested.** linuxArm64 is Tier 2, test execution unsupported; Testcontainers is JVM-only. | MEDIUM — structural | macosArm64 (dev) + linuxX64 (CI) run native tests; linuxArm64 is compile-only; deployed-Lambda smoke is its only coverage. Explicit host-to-task matrix in §6.1. |
| 19 | **CI breaks on the first native target.** `ubuntu-latest` cannot build Apple targets, and `tasks.named("final")` fans `publishToSonatype` across every subproject. | MEDIUM | CI lands in M0; runner matrix splits in M7. Publishing stays manual from macOS — the only host that can build the existing `iosArm64` targets. |
| 20 | **Maintenance cost, never priced in the draft.** The differential harness pins behaviour against a moving `aws.sdk.kotlin` (1.5.123 → 1.8.26 in months) and will false-fail whenever the SDK adds a default field or `amz-sdk-*` header. The 14-code throttling table is a snapshot with no drift detection. The libcrypt layer must be rebuilt on every Kotlin toolchain bump. **The presign differential additionally pins against the SDK's S3 presigner, which versions with the `s3` artifact independently of `dynamodb`.** | MEDIUM | Pin **both** SDK versions used by the harnesses explicitly and bump them deliberately, not via a catalog-wide update. Treat harness failures on an SDK bump as expected work, ~half a day per bump per service. |
| 21 | **Scope creep from S3 get/put/presign into bucket, list, multipart, copy, checksums and S3 Express** — i.e. the operations that WOULD require a restXml serializer. Also SQS, and the full credential chain. **The draft's evidence for this risk is now inverted and has been rewritten**: "S3 = three lines of mockk" was true and led to the wrong conclusion. | MEDIUM | The §2 boundary is a **mechanical two-clause test**, not a judgement call: zero body-bound members AND a blob `@httpPayload` target. Re-check the model, not memory. Decision 13 prices the crossings honestly (ListObjectsV2 ≈ 3 d + a pull reader; DeleteObjects +1 d and it forces CRC32; multipart ≈ 4 d, merging with streaming into ~6 d) so a crossing is a decision, not drift. SQS (two mock-only ops) and the credential chain keep their original evidence. |
| 22 | **AWS ships official Kotlin/Native klibs mid-project.** The staging namespace 2.3.x used DID publish real linuxArm64/linuxX64/macosArm64 klibs, so AWS has working multiplatform codegen internally. | LOW probability, HIGH impact | Re-check at every milestone boundary (ten minutes). `DynamoDb` is an interface and `SdkBackedDynamoDb` already exists, so a late arrival is a swap, not a write-off. |
| 23 | **Model drift.** | LOW | Our DTOs carry only the ~40 fields this codebase uses, and DynamoDB's data-plane API has been stable for a decade. The harness catches drift when the SDK dependency is bumped. |
| **24** | **A presigned URL minted inside a Lambda expires with the execution-role session, not at `X-Amz-Expires` — and the obvious mitigation is DEAD CODE in exactly that environment.** AWS: "the presigned URL expires when the role session expires, even if you specify a longer expiration time"; with `AssumeRole` the default session is one hour. M2's note establishes the session token is on the hot path for 100% of Lambda requests, so 100% of URLs a native Lambda mints are session-bound. **The trap**: `min(now + expiresIn, credentials.expiresAtEpochMillis)` degenerates to the requested value, because nothing in v1's credential set can populate that field — v1 ships `Static`, `Environment`, `Chain` and `Cached` providers, container/ECS credentials are out of scope, and AWS's own `EnvironmentCredentialsProvider.resolve()` constructs credentials with **no `expiration` argument** (verified at `aws-sdk-kotlin/aws-runtime/aws-config/common/src/.../EnvironmentCredentialsProvider.kt:41-48`) while `Credentials(...)` does accept `expiration: Instant?`. Lambda publishes no expiry variable. A confidently-labelled field that cannot be populated is **worse than no mitigation**, because callers stop checking. | **HIGH — the highest-severity NEW risk in the re-scope** | Make the unknown case **unrepresentable**: `PresignedUrl.expiry` is `Known(epochMillis)` vs `BoundedByUnknownSession(requestedEpochMillis)`, never a bare `Long`. When a session token is present and expiry is unknown (i.e. always, in Lambda) cap `expiresIn` at 1 hour by default and require `S3Config.allowPresignBeyondUnknownSessionExpiry = true` to exceed it, so the hazard is opted into at the call site. Read `AWS_CREDENTIAL_EXPIRATION` when present as a bonus, not the fix. Hard-cap at 604 800 s with S3's own error text. **State plainly that NO TEST CATCHES THIS**: unit tests fetch seconds after signing, LocalStack does not enforce it, and the differential harness *agrees with the bug* because the real SDK behaves identically. If multi-day URLs are genuinely required that is an architecture decision (long-lived IAM user credentials in Secrets Manager, or a separately assumed longer-duration role), not something the library can deliver — see **Q9**. |
| **25** | **S3's signer flag COMBINATION has no AWS fixture coverage.** S3 alone signs with `normalizeUriPath=false` and `doubleUriEncode=false`. Object keys containing `/`, `//`, `.`, `..`, `+`, `:`, `%`, spaces and UTF-8 are precisely the inputs those two flags change, and the failure is `SignatureDoesNotMatch`, which names no rule. Same failure class as Risk 4, with thinner coverage. | HIGH | **Sizing this honestly so it is not discounted**: the corpus is *not* silent. Seven cases run `normalize=false` and one (`get-percent-single-encoded`, path `/foo/bar/baz%3Cqux%3Aquux`, appearing verbatim in both its header and query canonical requests) runs `double_uri_encode=false`. Untested is the two flags **together**, under S3-shaped keys. Three mitigations: the hand-authored M1 encoding corpus (0.5 d, a supplement to eight existing fixtures); the M3.5a presign differential against the real SDK across the awkward-key matrix; and the M3.5b live-AWS put/get of a key containing `` `a b/c..d/e+f/日本語` ``. |
| **26** | **M1 ships with unconditional path normalization and its `.api` is frozen before S3 starts.** `aws-signing` is pitched as an independently publishable artifact, so this is public API in a published module. | HIGH — cheap now, expensive later | `doubleUriEncode` and `normalizeUriPath` land in `SigV4Config` in **M1**, not M3.5. ~0.3 d as part of M1; ~1 d plus an API revision if retrofitted. Unit test: key `a/../b` produces two different canonical URIs under the two settings. Left undone, an S3 key containing `.`, `..` or `//` passes 100% of CI and fails 100% of production — identical in shape to Risk 3. |
| **27** | **Ktor 3.3.3's Curl engine freezes on responses whose unflushed buffer exceeds 1 MB** (KTOR-9527, fixed 3.5.0): `CurlHttpResponseBody.onBodyChunkReceived` bridges libcurl's write callback through `runBlocking`, `ByteChannel.flush()` suspends at 1 MB and blocks the curl thread where no timeout can rescue it. KTOR-9483 compounds it. **This is NOT an S3-only risk** — DynamoDB's Query/Scan page limit is exactly 1 MB, so a full-page native Query sits on the threshold. | HIGH | Bump is an **M0 gate** with its own checkbox and its own verification, because it touches `logging` (wasmJs + iosArm64 + iosSimulatorArm64 + ktor-client-core) — the real risk surface — plus the two ktor-server modules; `events` is unaffected. Verification must run on macOS. A native GetObject or full-page Query over the limit is a **hang until Lambda timeout**, not a test failure — engine-specific and size-dependent, so neither unit tests nor LocalStack reproduce it. Caveat recorded in M0: KTOR-9527's affected-versions field lists 3.4.3, and that 3.3.3 is affected is an inference from the mechanism, not a stated fact. |
| **28** | **Virtual-host addressing breaks on dotted bucket names and on LocalStack.** `my.bucket.s3.us-east-1.amazonaws.com` fails wildcard TLS validation. Every existing integration test reaches LocalStack via `getEndpointOverride` on an ephemeral localhost port (`DynamoKtTests.kt:16`, `ExposedTestBase.kt:17`, `DynamoStreamTest.kt:29-30`), and **no existing container config enables `Service.S3` at all**. | MEDIUM | `forcePathStyle`, forced true whenever an endpoint override is set. Automatic path-style fallback for non-DNS-compatible buckets over https — an explicit fallback beats an opaque TLS error, and path-style deprecation was delayed indefinitely. Addressing-style decision, **not** a validation gate (see M3.5a). Add `Service.S3` to the container configs. Cross-reference Risk 3: this is the **second** way endpoint/authority derivation can pass CI and fail production, and both live in the signature. |
| **29** | **The `ByteArray`-only design has a memory ceiling that will be discovered in production rather than read in the docs — and the draft put the cap on the WRONG operation.** `putObject` holds the caller's array while Curl copies it into its own send buffer (peak ~2N); `getObject` accumulates chunks then assembles (peak ~1.5–2N) **and its size is set by whoever wrote the object**, frequently an event payload or an untrusted key. Kotlin/Native's allocator does not compact, so a single large contiguous allocation fails before total footprint does. Lambda scales vCPU with memory, so the SHA-256 pass runs at a fraction of a core in a small function. In a Lambda an unbounded download is an **OOM kill**: no stack trace, no typed exception, no CloudWatch error entry. | MEDIUM | Split the config (`maxBufferedUploadBytes` / `maxBufferedDownloadBytes`, 64 MB each) so the asymmetry cannot be re-introduced. On download, check `Content-Length` **before consuming the body** and separately bound the accumulated bytes, on **every** attempt. Throw `S3PayloadTooLargeException` naming `Range` as the escape hatch. Document the per-tier table in the module KDoc (128 MB → ~30 MB objects; 256 → ~70; 512 → ~150; 1024 → ~350). Expose `Range` from day one — one header and one response field, and it is the bounded-memory escape hatch. |
| **30** | **A checksum-free client with no completeness check has NO end-to-end integrity guarantee.** TLS provides per-record integrity, not stream completeness: a connection that dies mid-body yields a well-formed, silently short `ByteArray`. Whether the engine raises a premature-close error is engine-dependent (CIO on JVM, Curl on native) — and this plan is bumping Ktor *specifically* because of Curl response-body defects, so "the engine will throw" is an untested assumption on linuxArm64, the one target where tests cannot execute. | MEDIUM | One line, and it is the whole integrity story: assert `body.size == contentLength` (and on a 206 that the length matches the `Content-Range` span) and throw `S3IncompleteDownloadException` classified **Transient/retryable** so the existing loop handles it. MockEngine cases for a short body with an honest `Content-Length` and for a missing one. Record in the checksum ADR that the reversal path is `x-amz-checksum-mode: ENABLED` plus a CRC32 comparison (~0.5 d from vendored Apache-2.0 source) and that today the response hash is **not even requested**. |
| **31** | **Presigned URLs leak through logs.** AWS is explicit that presigned URLs are bearer tokens and that the signature travels in a query parameter clients routinely log. This repo has a live amplifier: `logging/src/commonMain/.../HttpLogPublisher.kt` PUTs log payloads to an arbitrary URL — already Risk 15. The URL also carries `X-Amz-Security-Token` in full. | MEDIUM | `PresignedUrl.toString()` redacts **both** `X-Amz-Signature` and `X-Amz-Security-Token` by plain string split/rejoin — **not** a regex lookbehind, whose Kotlin/Native semantics are unverified and whose silent non-match is indistinguishable from success. Redaction test runs on `macosArm64Test` and `linuxX64Test`, not only `jvmTest`. Document that there is **no revocation mechanism** for a leaked URL. |
| **32** | **Unsigned `x-amz-*` headers at fetch time are a hard 403.** AWS: any `x-amz-*` header you plan to send must be in `CanonicalHeaders`. The error is `AccessDenied` / `HeadersNotSigned` and it names the header, not the rule. Non-obvious non-`x-amz-` trap: when `Range` is signed, S3 requires `If-Range` to be signed too if present. | MEDIUM | The presign API takes **no free-form header bag applied after the fact**. Anything the fetcher will send is passed at presign time via `PresignRequest.signedHeaders` and appears in `X-Amz-SignedHeaders`; `PresignedUrl.signedHeaderNames` is public. `contentType` is a first-class parameter on `presignPutObject`. Response-header overrides are first-class fields for the same reason — they are canonicalized and cannot be appended later. |
| **33** | **The presign differential harness depends on `aws.sdk.kotlin` shipping an S3 presigner resolvable as a `jvmTest` dependency** — unverified from the on-disk checkout, which contains only `aws-runtime` and no service codegen. It is the best oracle in M3.5a. | LOW probability, MEDIUM impact on the milestone plan | Ten-minute Maven Central check in **M0**, alongside the existing "re-verify the premise" task. If it does not resolve, the 37 query-vector oracle and the live unauthenticated-fetch oracle both still stand, so the milestone survives — it loses its offline deterministic byte comparison and M3.5a grows by ~0.5 day. |
| **34** | **The native Lambda runtime is strictly serial, and AWS now offers an execution model in which that is wrong.** `lambda-native`'s `LambdaRuntime.run()` polls `GET /runtime/invocation/next`, runs the handler to completion, posts the response, and only then polls again; `lambdaContext` (in `lambda-coroutines`, shared with the JVM runtime) is a process-global `lateinit var` overwritten at the top of each invocation. Under the classic on-demand execution environment — one invocation at a time per environment — both are correct, and every handler written against this runtime assumes it. **Lambda Managed Instances can dispatch concurrent invocations into a single execution environment** (custom-runtime reference: https://docs.aws.amazon.com/lambda/latest/dg/runtimes-custom.html), and this runtime does not support that. The degradation is two-tier and the second tier is silent: concurrency is first merely *lost* (the loop serializes it, buying latency and nothing else), but a second invocation starting before the first completed would overwrite the global context, so the first handler reads the **second invocation's** request id and deadline — a wrong `remainingTimeInMillis` fed to exactly the "is there time for more work?" decision it exists to answer, and log lines attributed to the wrong request. No test catches this, because no test can: the trigger is a deployment-mode choice made outside the process. | MEDIUM — bounded by deployment mode, silent if crossed | **Documented as an unsupported mode rather than half-fixed.** Recorded in the KDoc on `LambdaRuntime` (and at the `lambdaContext` declaration, which is where a reader meets the global) so the constraint is met before the deployment decision, not after. Deploying `lambda-native` functions on the on-demand execution environment keeps the guarantee the loop is written against. Supporting concurrent delivery is a design change, not a patch: `lambdaContext` moves off a global and onto the coroutine context so each invocation carries its own; `run()` becomes a dispatcher over a bounded worker scope rather than an inline runner; and the Runtime API's one-response-per-request-id contract has to be honoured per in-flight invocation. That is its own workstream, adjacent to the `lambda/*` migration already listed in §2's revisit-later set. **Explicitly NOT in scope here**, and the reason this row exists is so that "the runtime happens to be serial" is never mistaken for "the runtime is safe under concurrency". Scope note: this row is about the *runtime*. The transport below it is separately safe — `RetryTokenBucket`, `AwsServiceClient.clockSkewOffsetMillis` and `DefaultS3`'s per-bucket client cache are atomic precisely so a handler's `async { }` fan-out over one client is sound. |

---

## 9. Open Questions

These require Jon's decision. Per `CLAUDE.md`, API contract changes need explicit review **before** implementation.

### Q1 — Do you approve the public API break, and does this ship as 3.0? — **ANSWERED: yes, 3.0.x on a new `3.0.x` branch**

**Resolved 2026-08-09.** Jon approved the break in principle and set the version line: this is a major release, developed on a branch named `3.0.x` cut from `2.2.x` @ `20ece7b`. The gate on M0 is therefore **open** — with one carve-out below.

The inventory stays in the plan for two reasons: it is the migration note downstream consumers will need, and items (j) and (k) are still genuinely undecided. **(k) remains conditional on Q7** (does anything outside this repo use `S3Local`?) and must not be actioned until Q7 is answered; **(j)** is explicitly deferred to M7. Everything (a)–(i) is approved.

The full inventory:

| # | Change | File:line |
|---|---|---|
| (a) | `AttributeValue` becomes `com.steamstreet.dynamokt.AttributeValue` — same package, same variant names, same accessors, **different type identity** | new `dynamo/src/commonMain/.../AttributeValue.kt` |
| (b) | `DynamoKt.builder`, `defaultCredentials`, `session()` take an awskt `AwsCredentialsProvider` | `DynamoKt.kt:17,18,43` |
| (c) | `Database(client)`, `DatabaseBuilder.clientConfig`, `DatabaseBuilder.client(...)` take `DynamoDb` / `DynamoDbConfig`; `Database.connect()` stops being suspend | `Database.kt:21,89,94,51` |
| (d) | `AttributeValueUpdate` and `AttributeAction` become project-owned | `attributes.kt` |
| (e) | `ExpressionBuilder.apply(scan: ScanRequest.Builder)` redesigned — no honest one-to-one replacement | `ExpressionBuilder.kt:323` |
| (f) | **`AttributeValue.localDate/localTime/localDateTime` change from `java.time.*` to `kotlinx.datetime.*`** | `dates.kt:65-67` |
| (g) | **`EnumSerializer` / `NullableEnumSerializer` public constructors take `List<T>` instead of `KClass<T>`** | `delegates.kt:242,255` |
| (h) | **`api(libs.aws.dynamodb)` removed from `dynamo`, `dynamokt`, `dynamokt-exposed` POMs** — a transitive-dependency break independent of everything above | three `build.gradle.kts:6-7` |
| (i) | **`dynamo`, `dynamokt`, `dynamokt-exposed` go from a single jar to KMP root-metadata + `-jvm`** | convention-plugin change |
| (j) | ~~*(M7 only)* `lambda-eventbridge` / `lambda-sqs` package renames~~ | **STRUCK 2026-08-11 — already done.** Both modules were already in those packages on `3.0.x`; there was never a rename to approve. See M7's STATUS. |
| (k) | *(only if Q7 resolves to "delete")* **`public class S3Local` removed from the published `awskt-test` artifact, and `api(libs.aws.s3)` removed from its POM** — the same transitive-dependency break class as (h) | `test/src/jvmMain/.../S3Mock.kt:6`, `test/build.gradle.kts:17` |

Items (f), (g), (h) and (i) were absent from the draft plan and are the reason M5a is 9.5 days rather than 8.

**The S3 slice adds exactly one row — (k) — and only conditionally.** There is no S3 client in this repo today, so `aws-signing`, `aws-core` and `aws-s3` are wholly additive: **M3.5 can start the moment M2 lands, regardless of whether this approval has been granted.** See the amended Risk 1. What must *not* be claimed is that S3 adds *nothing*: row (k) is a real removal from a published artifact, and it is why the "additive, ship it anyway" fallback holds the `S3Local` deletion back with M5a rather than shipping it early.

### Q2 — What should `DynamoKtSession.describeTable()` return?

Currently the SDK's full `TableDescription` (`DynamoKtSession.kt:25-29`), with **zero callers** anywhere in the repo. Options: (1) narrow it to the project-owned type planned in M3 — my recommendation; (2) delete it; (3) reproduce the full 36-type closure. Option 3 is the only one preserving source compatibility for an unknown external caller, and it is disproportionate.

### Q3 — Is `dynamokt-exposed` in scope for Kotlin/Native?

It is forced into scope for **compilation** regardless (`build.gradle.kts:6` declares `api(project(":dynamo"))`). The open question is only whether it also gets native targets. The plan says JVM-only in v1 with sources in `src/jvmMain`, which saves ~4 days and sidesteps the `Column.kt` `enumConstants` API break entirely. Adding targets later is a build-file change plus whatever surfaces in its 2,968 lines.

### Q4 — macOS CI runner, or publishing stays manual?

Today `pr.yml` and `publish_sdk.yml` both run on `ubuntu-latest`, which cannot build the `iosArm64` targets `standards`/`logging`/`serialization` already carry — so release is already manual from macOS via `.run/Publish To Central.run.xml`. Native targets do not create this constraint but do mean a broken `linuxArm64` link task blocks all publishing (`tasks.named("final")` fans out across every subproject).

### Q5 — Do any downstream consumers resolve `com.steamstreet:awskt-dynamokt` via plain Maven rather than Gradle?

If yes, item (i) above needs a jvm-only relocation shim. If everything is Gradle, module metadata handles it transparently and this is a non-issue. Only you can answer this.

### Q6 — Should `gradle-plugin/` be a published included build (Decision 10), or is the packaging plugin internal-only?

Publishing it (~1 day, budgeted in M7) delivers the stated goal of `ref-2.3.x/NATIVE-LAMBDA-PLAN.md:5`. Not publishing it means downstream projects copy a 12-line `packageLambda` task. Either is defensible; the plan assumes publishing.

### Q7 — Does anything outside this repo use `S3Local` from the published `test` artifact?

It has **zero in-repo usages**. If no external consumer exists, delete it — a `mockk(relaxed = true)` over an S3 client returns empty objects and is a worse test double than none. If one does exist, re-shape it as a small real in-memory implementation of the new `S3` interface, which would be strictly more useful than what it is today. **This decides whether Q1 row (k) is live.** Low effort either way, but it cannot be answered from inside the repo.

### Q8 — What object-size ceiling is acceptable for v1?

v1 buffers both directions as `ByteArray`, bounding a single object to Lambda memory (advisory: 128 MB Lambda → ~30 MB objects; 256 → ~70; 512 → ~150; 1024 → ~350) and, for PutObject, to S3's 5 GiB single-request limit. The plan defaults `maxBufferedUploadBytes` / `maxBufferedDownloadBytes` to 64 MB and throws rather than OOM-ing.

**If objects can exceed that, multipart upload enters scope — and `CompleteMultipartUpload` has a structure `@httpPayload`, which breaks the no-XML-serializer premise the whole S3 estimate rests on.** Answer this in the same pass as Q1; it changes what the API should expose.

### Q9 — Must presigned URLs outlive the Lambda execution-role session?

**This is the one question the library cannot answer for you, and Risk 24 is why.** A URL minted inside a Lambda dies when the execution role's session ends — typically one hour with `AssumeRole` defaults — regardless of `X-Amz-Expires`, and no test in this plan or any other can catch it.

The plan's answer is to make the unknown case unrepresentable (`Known` vs `BoundedByUnknownSession`) and to cap at 1 hour by default when a session token is present and expiry is unknown, with an explicit opt-out flag. **Confirm that is acceptable.** If genuinely long-lived (multi-day) URLs are required, that needs long-lived IAM user credentials held in Secrets Manager, or a separately assumed longer-duration role — a security-posture decision, not an engineering one, and it must be made **before the presign API is designed** because it changes what the type should expose.

### Q10 — Do your Lambdas need to LIST objects, delete many at once, or handle objects above the buffered ceiling?

**This is the question the mechanical boundary test does not answer.** §2's two-clause rule defends the v1 scope well, but it answers "what is cheap", not "what is sufficient". And the repository contains **zero evidence** about downstream S3 usage — an exhaustive grep of `ref-2.2.x` for S3 finds only `settings.gradle.kts:11` (an unrelated dynamodb-local Maven URL), `test/build.gradle.kts:17`, `gradle/libs.versions.toml:25` and four lines in `S3Mock.kt`. No bucket names, no config keys, no env vars, no docs, no call sites. **The entire v1 boundary rests on a single sentence of stated requirement and must be confirmed, not assumed.**

If the answer is yes, the costs and the premise both change:

| Capability | Cost | Effect on the premise |
|---|---:|---|
| `ListObjectsV2` | +3 d, incl. a ~200-line XML pull reader | **Falsifies the no-XML-engine argument.** `ListObjectsV2Output` has 12 body-bound members. |
| `DeleteObjects` | +1 d on top | XML *writing*, plus a mandatory CRC32 — it **is** one of the 25 `requestChecksumRequired` operations |
| Multipart upload | +4 d, merging with streaming into a combined ~6 d | Structure `@httpPayload`; also the 200-OK-with-error-body case |

`ListObjectsV2` is the overwhelmingly common third operation after get/put. **A client that can get, put and presign but cannot list strands the user one sprint later, and finding that out after M3.5b ships is the expensive ordering.** Ask this in the same pass as Q1, Q8 and Q9.

---

## 10. Effort Summary

| Phase | Days | Δ vs pre-S3 | Confidence |
|---|---:|---:|---|
| M0 scaffolding + approval gate + **Ktor ≥ 3.5.0 gate** | 5 | +1 | High on mechanics; the Ktor bump is the one line in the whole plan with genuine external variance (`logging`'s wasmJs + Apple targets) |
| M0.5 vertical spike | 3.5 | +0.5 | Medium — this is where the project can die cheaply |
| M1 SigV4 — **header + query signing** | 7.5 | +2.5 | Medium-high — ~600 lines, fully specified, 80 fixture assertions on disk of which 74 are assertable |
| M2 aws-core | 8.5 | +1.5 | Medium — classification tables are copy-work; the risk is signing/engine/header interaction |
| M3 aws-dynamodb | 10.5 | +0.5 | Medium-high — mechanical once the harness exists, hence harness first |
| **M3.5a aws-s3 module + endpoint + presign** | **4.5** | **+4.5** | **Medium-high** — the presign oracle is vendored and free and the SDK gives a deterministic offline differential; the risk is concentrated entirely in endpoint/bucket addressing |
| **M3.5b aws-s3 get/put/head/delete** | **8** | **+8** | **Medium** — the operations are genuinely simple, but `ref-2.3.x` contributes **nothing** to cherry-pick (verified), so unlike M4 and M7 this milestone gets **zero head start**, and it carries a new error protocol, a new endpoint scheme and a new LocalStack service |
| M4 foundation native | 3 | — | High — `ref-2.3.x` did most of it |
| M5a type swap | 9.5 | +0.5 | Medium — 9 real rewrites; ExpressionBuilder has genuine design uncertainty |
| M5b implementation flip | 3 | — | Medium-high — bounded by 100 tests giving tight feedback |
| M6 EventBridge + events | 5 | — | Medium — the client is one operation; `jvmNativeMain` and EventBridgeMock are the unknowns |
| M7 native + Lambda | 10.5 | +0.5 | **Low-medium — highest variance.** libcrypt layer, Curl CA path, `LD_LIBRARY_PATH`, CI host split and the eventbridge/sqs native split are environment problems that fail in ways unit tests never see |
| **Planned total (1 FTE)** | **78.5** | **+19.5** | |
| **+20% contingency** | **~94** | | |
| *Critical path, 2 developers* | *63 (~76)* | | see §5 |

### How the +19.5 splits, stated honestly

| Attribution | Days | Note |
|---|---:|---|
| **S3-attributable** | ~16 | M3.5a (4.5) + M3.5b (8) + M0.5 S3 tasks (0.5) + ~1.5 of M1 + ~0.75 of M2 + M5a `S3Local` (0.5) + M7 smoke (0.5) |
| **Pre-existing debt the S3 analysis exposed** | ~3.5 | M0 Ktor gate (1) — DynamoDB's Query/Scan page limit is exactly KTOR-9527's 1 MB freeze threshold; ~1 of M1 (the two config flags and the corrected exclusion set improve M1 regardless of whether S3 ever ships); ~0.75 of M2 (request-id capture benefits DynamoDB equally, and generalizing `callRaw` is far cheaper before M3 validates against it than after); M3 exception re-parenting (0.5) |

**Where I would spend contingency first**, in order: (1) endpoint and bucket addressing in M3.5a — the least-specified line with the most environment-shaped failure modes; (2) the Ktor bump, the only line with genuine external variance; (3) the S3-mode encoding corpus, because writing test cases for a rule you have not yet implemented always takes longer than it looks.

**The draft plan's 45 days was fantasy.** It omitted CreateTable (+1.5d), the `DynamoStreamRunner` bridge and the `dates.kt`/`delegates.kt` API breaks (+1d), the `SdkBackedDynamoDb` adapter and response-side differential harness (+3d), the `events` `jvmNativeMain` restructuring (+1d), the foundation-native milestone it had buried inside M7 as an aside (+3d), the vertical spike (+3d), and the native handler entry points 2.3.x never delivered (+4d). At ~640 LOC/day of *modified* code against 8,377 LOC with 100 Docker-gated tests to keep green, 45 days was not defensible.

**S3 was excluded from the 59-day version by the terms of the brief, not by analysis.** Once the requirement is stated the analysis reverses cleanly: GetObject and PutObject have zero body-bound members, the presign oracles are already vendored, the retry table is already 95% complete, and no checksum work is needed. What it actually costs is a new module, a query-signing mode, and one signer configuration whose flag *combination* AWS's own fixtures never exercise.

Against a 78.5-day plan, 20% contingency is conservative given M7's variance and M3.5b's zero head start. Read the number as **90–98 developer-days, ≈18–20 weeks at 1 FTE**, or **≈15 weeks with a second developer taking M4 + M3.5**.

---

## 11. Success Criteria

1. `./gradlew build` green on macOS and on `ubuntu-latest` CI, at Ktor ≥ 3.5.0, including `:logging:compileKotlinWasmJs` and `:logging:compileKotlinJs`.
2. **74 AWS SigV4 vector assertions pass — 37 in header mode and 37 in query (presign) mode — on `jvm`, `macosArm64` and `linuxX64`**, asserting canonical request, string-to-sign and signature independently. (40 header-capable + 40 query-capable case directories, minus the three documented skips in each mode; see M1's arithmetic table. Not "all 42": two directories carry no vectors of either kind.)
3. Request- and response-side wire-differential harnesses pass for all 12 DynamoDB operations, PutEvents, **and the four S3 operations (`getObject`, `putObject`, `headObject`, `deleteObject`)** against the real `aws.sdk.kotlin`.
4. A credentialed live-AWS smoke test performs create/put/get/query/transactWrite against a real table.
5. All 100 DynamoDB integration tests pass against LocalStack on the hand-written client (`DefaultDynamoDb`), and still pass with `-Pawskt.dynamo.client=sdk`.
6. `./gradlew apiCheck` reports exactly the approved API deltas and nothing else. `aws-signing`, `aws-core` and `aws-s3` contribute **only additions**; the only S3-related removal is Q1 row (k), and only if Q7 resolves to "delete".
7. `./gradlew :dynamo:macosArm64Test :dynamokt:macosArm64Test :aws:aws-s3:macosArm64Test` runs a non-zero number of tests.
8. `./gradlew packageLambda packageNativeLayer` produces a `bootstrap` zip and a libcrypt layer zip; a real `provided.al2023` / arm64 Lambda invocation round-trips an item through DynamoDB, **round-trips an object through S3, and returns a presigned GET URL that CI then fetches to a 200**.
9. `aws.sdk.kotlin` appears in no published POM except the `test` module and `aws-dynamodb-sdk-adapter`. **`aws.sdk.kotlin:s3` is whitelisted as an `aws-s3` `jvmTest`-only dependency for the presign differential — verify explicitly that it does not reach the published POM.**
10. **A presigned GET and PUT URL generated by `aws-s3` is byte-identical to one generated by `aws.sdk.kotlin`'s S3 presigner for the same inputs at a fixed signing instant — INCLUDING `X-Amz-Signature`** — across keys containing spaces, `+`, `//`, `..` and non-ASCII, with and without a session token, and with a `response-content-disposition` override containing a space and a non-ASCII filename.
11. **A credentialed live-AWS smoke performs a `PutObject`/`GetObject` round-trip on a key containing `` `a b/c..d/e+f/日本語` ``, a `Range` request returning HTTP 206 with exactly the requested bytes, and an UNAUTHENTICATED HTTPS fetch of a presigned GET URL returning HTTP 200 with the expected bytes.** A 403 or `SignatureDoesNotMatch` anywhere in this criterion is a failure, not a warning.
12. **`PresignedUrl.toString()` redacts both `X-Amz-Signature` and `X-Amz-Security-Token`, verified on `macosArm64Test` and `linuxX64Test` as well as `jvmTest`**, and the secret, session token and signature appear in no `toString()`, no exception `message` and no `stackTraceToString()` anywhere in `aws-signing` or `aws-s3`.
