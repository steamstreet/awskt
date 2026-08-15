#!/usr/bin/env bash
#
# Deployed-Lambda smoke test.
#
# `linuxArm64` is a Tier 2 Kotlin/Native target with test execution unsupported, so this script is
# the ONLY coverage the actual deployment architecture ever receives. Everything else in the
# repository is compile-verified, or tested on macosArm64 as a proxy.
#
# It deploys the native binary plus the libcrypt layer to `provided.al2023` on arm64, invokes it,
# and asserts that the invocation:
#   1. round-tripped a DynamoDB PutItem/GetItem against a real table,
#   2. round-tripped an S3 PutObject/GetObject against a real bucket,
#   3. returned presigned GET URLs that this script then fetches WITHOUT credentials and asserts
#      answer HTTP 200 with the expected bytes.
#
# (3) is the strongest oracle available: verifying a presigned URL needs no AWS client, no
# credentials on the verifying side, and no signer — so it cannot agree with a bug in our own
# signing code the way a differential against our own implementation could.
#
# It then invokes the `services` mode, which probes the M8–M10 clients (Secrets Manager, KMS, SQS,
# SNS, Scheduler, Bedrock, CloudWatch Logs Insights). Each probe is gated on its own SMOKE_* variable
# and self-skips when unset — see docs/live-smoke.md. The free, self-owned fixtures (an SQS queue,
# an SNS topic with no subscribers, a Scheduler role) are created idempotently by this script; the
# ones that cost money or already exist in every account (a KMS key, a secret, a Bedrock model) are
# pass-through only:
#
#   SMOKE_KMS_KEY_ID        a symmetric ENCRYPT_DECRYPT key id or alias
#   SMOKE_SECRET_ID         any readable secret — read only, never written
#   SMOKE_BEDROCK_MODEL_ID  a model with access granted; this probe costs (a little) money
#
# Gated on AWSKT_LIVE_SMOKE=1 so that a normal build never touches AWS.

set -euo pipefail

if [ "${AWSKT_LIVE_SMOKE:-}" != "1" ]; then
  echo "AWSKT_LIVE_SMOKE is not 1 — skipping the deployed-Lambda smoke."
  exit 0
fi

REGION="${AWS_REGION:-us-west-2}"
BUCKET="${SMOKE_BUCKET_NAME:-kotlin-native-test-443844975891-us-west-2-an}"
TABLE="${SMOKE_TABLE_NAME:-awskt-native-smoke}"
FUNCTION="${SMOKE_FUNCTION_NAME:-awskt-native-smoke}"
ROLE_NAME="${SMOKE_ROLE_NAME:-awskt-native-smoke-role}"
LAYER_NAME="${SMOKE_LAYER_NAME:-awskt-native-libcrypt}"
MEMORY_MB="${SMOKE_MEMORY_MB:-512}"

# M8–M10 fixtures. The first three are created below if unset; the rest are pass-through.
QUEUE_NAME="${SMOKE_QUEUE_NAME:-awskt-native-smoke}"
TOPIC_NAME="${SMOKE_TOPIC_NAME:-awskt-native-smoke}"
SCHEDULER_ROLE_NAME="${SMOKE_SCHEDULER_ROLE_NAME:-awskt-native-smoke-scheduler-role}"
KMS_KEY_ID="${SMOKE_KMS_KEY_ID:-}"
SECRET_ID="${SMOKE_SECRET_ID:-}"
BEDROCK_MODEL_ID="${SMOKE_BEDROCK_MODEL_ID:-}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ARTIFACTS="$REPO_ROOT/lambda/lambda-native-smoke/build/lambda"
BOOTSTRAP_ZIP="$ARTIFACTS/lambda-native-smoke.zip"
LAYER_ZIP="$ARTIFACTS/lambda-native-smoke-native-layer.zip"

aws() { command aws --region "$REGION" "$@"; }

for f in "$BOOTSTRAP_ZIP" "$LAYER_ZIP"; do
  [ -f "$f" ] || { echo "Missing $f — run ./gradlew packageLambda packageNativeLayer first." >&2; exit 1; }
done

ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
echo "Account $ACCOUNT, region $REGION"

# --- DynamoDB table -----------------------------------------------------------------------------
if ! aws dynamodb describe-table --table-name "$TABLE" >/dev/null 2>&1; then
  echo "Creating table $TABLE"
  aws dynamodb create-table \
    --table-name "$TABLE" \
    --attribute-definitions AttributeName=pk,AttributeType=S \
    --key-schema AttributeName=pk,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST >/dev/null
  aws dynamodb wait table-exists --table-name "$TABLE"
fi

# A fixed row, so the perf harness's `get` mode measures a hit rather than a miss. Idempotent.
aws dynamodb put-item --table-name "$TABLE" --item \
  '{"pk":{"S":"perf#fixed"},"value":{"S":"fixed-perf-row"}}' >/dev/null

FUNCTION_ARN="arn:aws:lambda:$REGION:$ACCOUNT:function:$FUNCTION"
LOG_GROUP="/aws/lambda/$FUNCTION"

# --- SQS queue and SNS topic --------------------------------------------------------------------
# Dedicated, because the smoke *sends* to them: an existing queue has a consumer that would eat the
# probe's message, and an existing topic has subscribers who would receive it. Both calls are
# idempotent — they return the existing resource when it is already there.
QUEUE_URL="$(aws sqs create-queue --queue-name "$QUEUE_NAME" --query QueueUrl --output text)"
QUEUE_ARN="$(aws sqs get-queue-attributes --queue-url "$QUEUE_URL" --attribute-names QueueArn \
  --query Attributes.QueueArn --output text)"
TOPIC_ARN="$(aws sns create-topic --name "$TOPIC_NAME" --query TopicArn --output text)"
echo "Queue $QUEUE_ARN"
echo "Topic $TOPIC_ARN"

# --- Scheduler role -----------------------------------------------------------------------------
# The schedule the probe creates is DISABLED, dated 2099 and deleted in the same invocation, so this
# role never actually gets used — but CreateSchedule still requires a role that Scheduler can assume,
# and the function needs iam:PassRole on it. Its one permission is to invoke the smoke function,
# which is also the schedule's target.
if ! command aws iam get-role --role-name "$SCHEDULER_ROLE_NAME" >/dev/null 2>&1; then
  echo "Creating role $SCHEDULER_ROLE_NAME"
  command aws iam create-role --role-name "$SCHEDULER_ROLE_NAME" \
    --assume-role-policy-document "{
      \"Version\":\"2012-10-17\",
      \"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"scheduler.amazonaws.com\"},
        \"Action\":\"sts:AssumeRole\",
        \"Condition\":{\"StringEquals\":{\"aws:SourceAccount\":\"$ACCOUNT\"}}}]
    }" >/dev/null
fi
command aws iam put-role-policy --role-name "$SCHEDULER_ROLE_NAME" --policy-name invoke-smoke \
  --policy-document "{
    \"Version\":\"2012-10-17\",
    \"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"lambda:InvokeFunction\",\"Resource\":\"$FUNCTION_ARN\"}]
  }" >/dev/null
SCHEDULER_ROLE_ARN="$(command aws iam get-role --role-name "$SCHEDULER_ROLE_NAME" --query Role.Arn --output text)"

# --- Execution role -----------------------------------------------------------------------------
if ! command aws iam get-role --role-name "$ROLE_NAME" >/dev/null 2>&1; then
  echo "Creating role $ROLE_NAME"
  command aws iam create-role --role-name "$ROLE_NAME" \
    --assume-role-policy-document '{
      "Version":"2012-10-17",
      "Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]
    }' >/dev/null
  command aws iam attach-role-policy --role-name "$ROLE_NAME" \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole >/dev/null
  # Role propagation to Lambda is eventually consistent; creating the function too early fails with
  # an unhelpful "cannot be assumed" error.
  sleep 15
fi

# Statements for the pass-through fixtures are added only when the fixture is configured, and are
# scoped to the one resource each probe touches. Aliases and secret names are resolved to ARNs here
# because IAM does not match on them.
OPTIONAL_STATEMENTS=""
if [ -n "$KMS_KEY_ID" ]; then
  KMS_KEY_ARN="$(aws kms describe-key --key-id "$KMS_KEY_ID" --query KeyMetadata.Arn --output text)"
  OPTIONAL_STATEMENTS+=",{\"Effect\":\"Allow\",\"Action\":[\"kms:Encrypt\",\"kms:Decrypt\"],\"Resource\":\"$KMS_KEY_ARN\"}"
fi
if [ -n "$SECRET_ID" ]; then
  SECRET_ARN="$(aws secretsmanager describe-secret --secret-id "$SECRET_ID" --query ARN --output text)"
  OPTIONAL_STATEMENTS+=",{\"Effect\":\"Allow\",\"Action\":\"secretsmanager:GetSecretValue\",\"Resource\":\"$SECRET_ARN\"}"
fi
if [ -n "$BEDROCK_MODEL_ID" ]; then
  # Foundation-model ARNs are region-qualified but account-less; inference profiles (`us.…` ids)
  # route across regions and are account-scoped. Allow the one model under either shape.
  OPTIONAL_STATEMENTS+=",{\"Effect\":\"Allow\",\"Action\":[\"bedrock:InvokeModel\",\"bedrock:InvokeModelWithResponseStream\"],
    \"Resource\":[\"arn:aws:bedrock:*::foundation-model/$BEDROCK_MODEL_ID\",
                  \"arn:aws:bedrock:$REGION:$ACCOUNT:inference-profile/$BEDROCK_MODEL_ID\"]}"
fi

command aws iam put-role-policy --role-name "$ROLE_NAME" --policy-name smoke-access \
  --policy-document "{
    \"Version\":\"2012-10-17\",
    \"Statement\":[
      {\"Effect\":\"Allow\",\"Action\":[\"dynamodb:PutItem\",\"dynamodb:GetItem\"],
       \"Resource\":\"arn:aws:dynamodb:$REGION:$ACCOUNT:table/$TABLE\"},
      {\"Effect\":\"Allow\",\"Action\":[\"s3:PutObject\",\"s3:GetObject\"],
       \"Resource\":\"arn:aws:s3:::$BUCKET/*\"},
      {\"Effect\":\"Allow\",\"Action\":[\"events:PutEvents\"],
       \"Resource\":\"arn:aws:events:$REGION:$ACCOUNT:event-bus/default\"},
      {\"Effect\":\"Allow\",\"Action\":[\"sqs:SendMessage\",\"sqs:ReceiveMessage\",\"sqs:DeleteMessage\"],
       \"Resource\":\"$QUEUE_ARN\"},
      {\"Effect\":\"Allow\",\"Action\":\"sns:Publish\",\"Resource\":\"$TOPIC_ARN\"},
      {\"Effect\":\"Allow\",\"Action\":[\"scheduler:CreateSchedule\",\"scheduler:GetSchedule\",\"scheduler:DeleteSchedule\"],
       \"Resource\":\"arn:aws:scheduler:$REGION:$ACCOUNT:schedule/default/awskt-smoke-*\"},
      {\"Effect\":\"Allow\",\"Action\":\"iam:PassRole\",\"Resource\":\"$SCHEDULER_ROLE_ARN\",
       \"Condition\":{\"StringEquals\":{\"iam:PassedToService\":\"scheduler.amazonaws.com\"}}},
      {\"Effect\":\"Allow\",\"Action\":\"logs:StartQuery\",
       \"Resource\":\"arn:aws:logs:$REGION:$ACCOUNT:log-group:$LOG_GROUP:*\"},
      {\"Effect\":\"Allow\",\"Action\":[\"logs:GetQueryResults\",\"logs:StopQuery\"],\"Resource\":\"*\"}
      $OPTIONAL_STATEMENTS
    ]
  }" >/dev/null

ROLE_ARN="$(command aws iam get-role --role-name "$ROLE_NAME" --query Role.Arn --output text)"

# --- libcrypt layer -----------------------------------------------------------------------------
echo "Publishing layer $LAYER_NAME"
LAYER_ARN="$(aws lambda publish-layer-version \
  --layer-name "$LAYER_NAME" \
  --description "libcrypt.so.1 for Kotlin/Native on provided.al2023 (KT-55643)" \
  --compatible-architectures arm64 \
  --compatible-runtimes provided.al2023 \
  --zip-file "fileb://$LAYER_ZIP" \
  --query LayerVersionArn --output text)"
echo "  $LAYER_ARN"

# --- Function -----------------------------------------------------------------------------------
# LD_LIBRARY_PATH puts the layer's lib/ ahead of the system paths without hiding them: /opt is where
# Lambda unpacks layers, and dropping /lib64:/usr/lib64 would break everything else the binary links.
#
# The function's environment is written as a JSON document rather than the shorthand form: the SQS
# queue URL and the ARNs contain characters (`:`, `/`) that the shorthand parser treats as syntax.
ENV_JSON="$(python3 - "$TABLE" "$BUCKET" "$QUEUE_URL" "$TOPIC_ARN" "$FUNCTION_ARN" "$SCHEDULER_ROLE_ARN" \
                     "$LOG_GROUP" "$KMS_KEY_ID" "$SECRET_ID" "$BEDROCK_MODEL_ID" <<'PY'
import json, sys
(table, bucket, queue, topic, target, role, group, kms, secret, model) = sys.argv[1:]
v = {
    "LD_LIBRARY_PATH": "/opt/lib:/lib64:/usr/lib64",
    "SMOKE_TABLE_NAME": table,
    "SMOKE_BUCKET_NAME": bucket,
    "SMOKE_QUEUE_URL": queue,
    "SMOKE_TOPIC_ARN": topic,
    "SMOKE_SCHEDULER_TARGET_ARN": target,
    "SMOKE_SCHEDULER_ROLE_ARN": role,
    "SMOKE_LOG_GROUP": group,
}
# Unset pass-through fixtures are omitted rather than set to "", so the probe reports "skipped".
for k, val in (("SMOKE_KMS_KEY_ID", kms), ("SMOKE_SECRET_ID", secret), ("SMOKE_BEDROCK_MODEL_ID", model)):
    if val:
        v[k] = val
print(json.dumps({"Variables": v}))
PY
)"

if aws lambda get-function --function-name "$FUNCTION" >/dev/null 2>&1; then
  echo "Updating function $FUNCTION"
  aws lambda update-function-code --function-name "$FUNCTION" \
    --zip-file "fileb://$BOOTSTRAP_ZIP" >/dev/null
  aws lambda wait function-updated --function-name "$FUNCTION"
  aws lambda update-function-configuration --function-name "$FUNCTION" \
    --layers "$LAYER_ARN" --environment "$ENV_JSON" --timeout 60 --memory-size "$MEMORY_MB" >/dev/null
  aws lambda wait function-updated --function-name "$FUNCTION"
else
  echo "Creating function $FUNCTION"
  aws lambda create-function --function-name "$FUNCTION" \
    --runtime provided.al2023 --architectures arm64 --handler bootstrap \
    --role "$ROLE_ARN" --zip-file "fileb://$BOOTSTRAP_ZIP" \
    --layers "$LAYER_ARN" --environment "$ENV_JSON" --timeout 60 --memory-size "$MEMORY_MB" >/dev/null
  aws lambda wait function-active --function-name "$FUNCTION"
fi

# --- Invoke -------------------------------------------------------------------------------------
OUT="$(mktemp)"
echo "Invoking $FUNCTION"
STATUS="$(aws lambda invoke --function-name "$FUNCTION" \
  --cli-binary-format raw-in-base64-out --payload '{}' \
  --query 'FunctionError' --output text "$OUT")"

RESPONSE="$(cat "$OUT")"
echo "--- response ---"
echo "$RESPONSE"
echo "----------------"

if [ "$STATUS" != "None" ]; then
  echo "FAIL: the invocation reported FunctionError=$STATUS" >&2
  exit 1
fi

# The function returns a JSON *string* containing JSON, because the Runtime API response body is
# whatever the handler produced. Unwrap once if needed.
BODY="$(printf '%s' "$RESPONSE" | python3 -c 'import json,sys; v=json.load(sys.stdin); print(v if isinstance(v,str) else json.dumps(v))')"

fail=0
check() {
  local expr="$1" label="$2"
  local got
  got="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print($expr)")"
  if [ "$got" = "True" ]; then
    echo "PASS: $label"
  else
    echo "FAIL: $label (got $got)" >&2
    fail=1
  fi
}

check "d['dynamo']['matched']" "DynamoDB PutItem/GetItem round-tripped (including 38-digit N precision)"
check "d['s3']['matched']" "S3 PutObject/GetObject round-tripped"

EXPECTED="$(printf '%s' "$BODY" | python3 -c "import json,sys; print(json.load(sys.stdin)['s3']['expectedBody'])")"

# --- Unauthenticated presigned fetches ----------------------------------------------------------
for field in presignedSimpleUrl presignedAwkwardUrl; do
  URL="$(printf '%s' "$BODY" | python3 -c "import json,sys; print(json.load(sys.stdin)['s3']['$field'])")"
  TMP="$(mktemp)"
  # `env -u` strips AWS credentials so this fetch cannot accidentally be authenticated — the whole
  # point is that the URL alone carries the authorization.
  CODE="$(env -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN -u AWS_PROFILE \
    curl -s -o "$TMP" -w '%{http_code}' "$URL")"
  if [ "$CODE" = "200" ] && [ "$(cat "$TMP")" = "$EXPECTED" ]; then
    echo "PASS: unauthenticated GET of $field returned 200 with the expected bytes"
  else
    echo "FAIL: unauthenticated GET of $field returned $CODE, body: $(head -c 400 "$TMP")" >&2
    fail=1
  fi
  rm -f "$TMP"
done

# --- The M8–M10 services -----------------------------------------------------------------------
# One invocation, every probe. A probe that skipped is reported but does not fail the run — the
# function's own `allOk` has the same semantics — so the pass-through fixtures are genuinely
# optional. A probe that ran and failed does fail the run.
echo "Invoking $FUNCTION (services)"
STATUS="$(aws lambda invoke --function-name "$FUNCTION" \
  --cli-binary-format raw-in-base64-out --payload '{"mode":"services"}' \
  --query 'FunctionError' --output text "$OUT")"
RESPONSE="$(cat "$OUT")"
if [ "$STATUS" != "None" ]; then
  echo "FAIL: the services invocation reported FunctionError=$STATUS" >&2
  echo "$RESPONSE" >&2
  fail=1
else
  python3 - "$OUT" <<'PY' || fail=1
import json, sys
with open(sys.argv[1]) as f:
    v = json.load(f)
d = json.loads(v) if isinstance(v, str) else v
for p in d["probes"]:
    if p.get("skipped"):
        print("SKIP: %s: %s" % (p["service"], p.get("detail")))
    elif p["ok"]:
        print("PASS: %s: %s" % (p["service"], p.get("detail")))
    else:
        print("FAIL: %s: %s" % (p["service"], p.get("error")), file=sys.stderr)
sys.exit(0 if d["allOk"] else 1)
PY
fi

rm -f "$OUT"
[ "$fail" -eq 0 ] || { echo "SMOKE FAILED" >&2; exit 1; }
echo "SMOKE PASSED"
