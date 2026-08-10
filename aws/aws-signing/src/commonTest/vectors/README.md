# AWS SigV4 signing test-suite corpus

These are **not** hand-written fixtures. This directory is a verbatim copy of AWS's own SigV4
signing test suite, used here as an independent oracle: it is the only thing in this project that
validates the signer without also validating our own understanding of the signer.

## Provenance

| | |
|---|---|
| Corpus | `aws-signing-test-suite/v4` |
| Vendored from | [`awslabs/smithy-kotlin`](https://github.com/awslabs/smithy-kotlin) @ `6373a5f`, path `runtime/auth/aws-signing-tests/common/resources/aws-signing-test-suite/v4` |
| Originally from | [`awslabs/aws-c-auth`](https://github.com/awslabs/aws-c-auth), `tests/aws-signing-test-suite/v4` |
| Licence | Apache-2.0 |
| Copyright | Amazon.com, Inc. or its affiliates. All Rights Reserved. |

`awskt` is published under MIT. This directory is Apache-2.0 third-party material and the
attribution above must be preserved. Do not relicense these files, and do not edit them — a
"fix" to a fixture is almost always a bug in the signer.

## Layout

42 case directories. Each contains:

- `context.json` — credentials, region, service, timestamp, and the signing flags
  (`normalize`, `double_uri_encode`, `sign_body`, `expiration_in_seconds`)
- `request.txt` — the raw HTTP request to sign
- `header-*.txt` — expected canonical request, string-to-sign, signature and signed request for
  **header-based** (`Authorization`) signing — present in **40** of 42 cases
- `query-*.txt` — the same four, for **query-string (presigned URL)** signing — present in
  **40** of 42 cases

`get-vanilla-query-order-key` and `get-vanilla-query-order-value` carry only `context.json` and
`request.txt`; they have no expected output in either mode and are inert.

## What is asserted, and what is skipped

`SigV4VectorTest` asserts three levels per case per mode — canonical request, string-to-sign, and
signature — so a failure diffs to exactly one normalization rule rather than to an opaque hex
mismatch.

**37 header + 37 query = 74 assertions.** Documented skips:

| Case | Reason |
|---|---|
| `get-header-value-multiline` | Tests inbound obs-fold header parsing. We never *produce* a folded header, so there is nothing to sign. |
| `post-x-www-form-urlencoded` | The corpus signs `content-length`; we deliberately exclude that header. See below. |
| `post-x-www-form-urlencoded-parameters` | Same. |

### Why `content-length` is excluded, and why that costs two cases

AWS's own skip list does not contain `content-length`, and these two fixtures prove AWS signs it in
both modes. We diverge on purpose: **Ktor sets `Content-Length` after we sign**, so signing it is
latent breakage on any engine or version change — exactly the argument that already excludes
`user-agent`. The divergence is self-consistency, not convenience.

(It is also a no-op for presigning, since there is no body at signing time. That is a *consequence*,
not the justification — recording it as the reason would invite a future "simplification" that
re-enables the header for header-mode signing and breaks it on the next Ktor bump.)

The corpus is not silent on the S3-shaped flags, which is why the hand-authored supplement in
`S3EncodingTest` is small: `normalize = false` is covered by seven cases and
`double_uri_encode = false` by one (`get-percent-single-encoded`). What no AWS fixture covers is the
**two flags together under S3-shaped keys**, and that is all the supplement adds.
