# Running the live smoke suites

Everything in this repository is hermetic by default. The suites below make **real AWS calls** and
self-skip when their environment variables are absent, so `./gradlew check` on a laptop with no
credentials behaves exactly as it always has.

There are two of them and they cover different things:

| | `Live*Test` (JVM / macOS) | `lambda-native-smoke` (`services` mode) |
|---|---|---|
| Runs on | your machine, JVM + macosArm64 | a deployed Lambda, **linuxArm64** |
| Proves | the wire format against real AWS | the same, *on the architecture that ships* |
| Cost to run | one `./gradlew` invocation | a deploy |

The second is not redundant. **Kotlin/Native `linuxArm64` is a Tier 2 target with test execution
unsupported**, so nothing else in this repository executes a single line of code on Graviton. A
client that compiles for it and faults on it would otherwise ship unnoticed.

---

## The JVM live suites

Each module has one, and each skips unless both AWS credentials *and* its own variable are present.

```bash
eval "$(aws configure export-credentials --profile my-profile --format env)"
export AWS_REGION=us-west-2
```

| Module | Variable | Fixture needed |
|---|---|---|
| `aws-kms` | `SMOKE_KMS_KEY_ID` | a symmetric `ENCRYPT_DECRYPT` key or alias |
| `aws-kms` (`GetPublicKey`, `Sign`/`Verify`) | `SMOKE_KMS_SIGNING_KEY_ID` | an asymmetric `SIGN_VERIFY` key or alias (`alias/vegasful-test-signing` in vegasful-test) |
| `aws-secretsmanager` | `SMOKE_SECRET_ID` | any readable secret (read-only; never written) |
| `aws-sqs` | `SMOKE_QUEUE_URL` | a **standard** (non-FIFO) queue |
| `aws-sns` | `SMOKE_TOPIC_ARN` | a topic, ideally with no subscriptions |
| `aws-ses` | `SMOKE_SES_FROM`, `SMOKE_SES_TO` | a verified sending identity, and an address you own — **it really sends mail** |
| `aws-scheduler` | `SMOKE_SCHEDULER_TARGET_ARN`, `SMOKE_SCHEDULER_ROLE_ARN` | a target and a role trusting `scheduler.amazonaws.com` |
| `aws-bedrock-runtime` | `SMOKE_BEDROCK_MODEL_ID` | model access granted in the account |
| `aws-lambda` | `SMOKE_LAMBDA_FUNCTION` | any function that runs — **it really invokes it**, twice (once synchronously, once queued) |
| `aws-lambda` (streaming) | `SMOKE_LAMBDA_STREAM_FUNCTION` | a function configured with `InvokeMode = RESPONSE_STREAM` |
| `aws-cloudwatch-logs` | `SMOKE_LOG_GROUP` | any existing log group — **it may be empty** |
| `aws-opensearch` | `SMOKE_OPENSEARCH_ENDPOINT` | a **public-access** managed domain whose access policy names you — read-only, and it needs no index |
| `aws-core` | *(credentials alone)* | none — calls `ListTables` |

```bash
# One module
./gradlew :aws:aws-kms:jvmTest

# All of them
./gradlew :aws:aws-core:jvmTest :aws:aws-kms:jvmTest :aws:aws-secretsmanager:jvmTest \
          :aws:aws-sqs:jvmTest :aws:aws-sns:jvmTest :aws:aws-scheduler:jvmTest \
          :aws:aws-bedrock-runtime:jvmTest :aws:aws-cloudwatch-logs:jvmTest \
          :aws:aws-opensearch:jvmTest :aws:aws-lambda:jvmTest \
          :aws:aws-ses:jvmTest

# On macOS, the same tests through the Curl engine rather than CIO — worth doing at least once,
# because the native Lambda uses Curl and CIO is not evidence about it.
./gradlew :aws:aws-kms:macosArm64Test
```

Everything the suites create, they delete: SQS messages are received and deleted, Scheduler
schedules are deleted in a `finally`. Secrets Manager is read-only by design — `PutSecretValue`
would accumulate secret versions against an account quota, so its idempotency-token behaviour is
asserted hermetically instead.

**`aws-lambda` is the other suite that does something rather than reading something**: it invokes
the named function for real, synchronously and then asynchronously, so point it at a function whose
side effects you are happy to repeat. It is deliberately tolerant about what comes back — a fixture
whose handler *throws* still passes, because a `200` carrying `X-Amz-Function-Error` is precisely
the case that module exists to tell apart from a failed invocation.

**`aws-ses` is the exception, and it is not a fixable one: an email cannot be un-sent.** It is the
only suite here that leaves something behind outside AWS, which is why it needs its own
`SMOKE_SES_TO` rather than riding on credentials alone — it cannot mail anyone by accident, and it
sends exactly one message per run. Point it at an address you own.

### The two that are worth running even if you skip the rest

- **`aws-sns`** carries the only hand-written form encoder and the only hand-written XML reader in
  the library. Every other module's protocol was proven by an earlier differential; this one's was
  written from the wire format and checked only against tests written alongside it. A real 200 from
  SNS is the first independent confirmation either half is right.
- **`aws-bedrock-runtime`'s `converseStreamReceivesFramesAcrossChunkBoundaries`** is the only test
  that runs `callStreaming` against a **real chunked HTTP response**. `MockEngine` serves a body in
  one piece, so the hermetic tests prove the frame decoder works and cannot prove the transport
  survives a frame split across two network reads. A model generating tokens over several seconds
  produces exactly that.

**Bedrock costs money.** The calls are deliberately tiny — a handful of tokens, `maxTokens` capped —
but they are real inference, unlike every other live suite here.

**Insights is billed on bytes scanned**, not on rows returned, so a query over a wide window on a
busy log group is expensive however small its `limit`. The suite uses a one-hour window and a
`limit 5`; the smoke function's probe uses a wide window with `limit 1`, which is cheap because
Insights stops scanning once the limit is met on a `sort`-free query. Point them at a quiet group
if you have one.

---

## The deployed native smoke function

`lambda/lambda-native-smoke` ships to Graviton and is invoked with a JSON payload naming a mode.
The `services` mode covers Secrets Manager, KMS, SQS, SNS, Scheduler and Bedrock.

```bash
aws lambda invoke \
  --function-name awskt-native-smoke \
  --payload '{"mode":"services"}' --cli-binary-format raw-in-base64-out \
  /dev/stdout | jq
```

```json
{
  "requestId": "…",
  "probes": [
    {"service": "secretsmanager", "ok": true, "detail": "read 42 chars (redacted)"},
    {"service": "kms",            "ok": true, "detail": "round-tripped 184 ciphertext bytes"},
    {"service": "sqs",            "ok": true, "detail": "sent 1, received 1 of 1, deleted 1"},
    {"service": "sns",            "ok": true, "detail": "published 9c1f…"},
    {"service": "scheduler",      "ok": false, "skipped": true,
                                  "detail": "SMOKE_SCHEDULER_TARGET_ARN not set"},
    {"service": "bedrock",        "ok": true,
                                  "detail": "converse='pong', stream delivered 23 deltas"},
    {"service": "cloudwatch-insights", "ok": true,
                                  "detail": "completed, 1 row(s), 4096.0 bytes scanned"},
    {"service": "opensearch",     "ok": false, "skipped": true,
                                  "detail": "SMOKE_OPENSEARCH_ENDPOINT not set"}
  ],
  "allOk": true
}
```

Each probe is gated on the same variable its JVM counterpart uses, set on the function rather than
in your shell. **None of them is required**: an existing deployment has `AWS_REGION`,
`SMOKE_TABLE_NAME` and `SMOKE_BUCKET_NAME` and keeps working untouched, and an unset probe reports
`skipped` rather than failing the invocation.

`allOk` is false only when a probe *ran and failed* — skipped probes do not fail it, so the same
payload is meaningful in an account where only some fixtures exist.

The `opensearch` probe is the one exception to "every probe here has been run green at least once":
it was written without a test domain to point it at and has only ever reported `skipped`. Treat its
first non-skipped run as the thing that establishes the coverage, not as a regression check. Note
also that a **VPC-only** domain is unreachable from a Lambda outside its VPC and will report an
error rather than a skip — leave the variable unset in that case rather than attaching this function
to a VPC.

The other modes are unchanged: `ping`, `get`, `event`, `getevent` for latency measurement, and the
default `full` for the DynamoDB and S3 correctness workload.

### Deploying it

`.github/scripts/native-smoke.sh` builds nothing — run `./gradlew :lambda:lambda-native-smoke:packageLambda
:lambda:lambda-native-smoke:packageNativeLayer` first — and then creates or updates everything the
function needs, deploys it, and invokes both the `full` and the `services` modes, failing on any
probe that ran and failed.

```bash
export AWS_PROFILE=<admin-ish profile> AWS_REGION=us-west-2 AWSKT_LIVE_SMOKE=1
# Optional: the fixtures that cost money or already exist in every account. Unset = that probe skips.
export SMOKE_KMS_KEY_ID=alias/my-key SMOKE_SECRET_ID=my/secret SMOKE_BEDROCK_MODEL_ID=amazon.nova-lite-v1:0
bash .github/scripts/native-smoke.sh
```

What it owns, all named `awskt-native-smoke*` and all idempotent to re-run:

| Resource | Why the script creates it rather than reusing one |
|---|---|
| DynamoDB table `awskt-native-smoke` | as before |
| SQS queue `awskt-native-smoke` | the probe *sends*; an existing queue has a consumer that would eat the message |
| SNS topic `awskt-native-smoke` (no subscriptions) | the probe *publishes*; an existing topic has subscribers who would receive it |
| IAM role `awskt-native-smoke-scheduler-role` | `CreateSchedule` needs a role Scheduler can assume, and the function needs `iam:PassRole` on it. It can only invoke the smoke function, and the schedule is disabled, dated 2099 and deleted in the same invocation |
| IAM role `awskt-native-smoke-role` | the function's own; its inline policy is scoped to exactly the resources above plus, when configured, the one KMS key, secret and model |
| Layer `awskt-native-libcrypt` | a new version per run |
| Function `awskt-native-smoke` | `SMOKE_MEMORY_MB` (default 512) |

The scheduler target and the Insights log group are the function itself and its own log group; the
script derives both. Nothing it creates is billed at rest.

The account it has been run in is `443844975891` (`vegasful-test`), using `alias/vegasful-test-key`,
`vegasful/test/tiny` and `amazon.nova-lite-v1:0` for the three pass-through fixtures. The
`ai-vegasful-test-deploy` (`AgentDeploy`) role is **not** sufficient — it cannot read or update the
function or its role — so this needs the SSO administrator profile.
