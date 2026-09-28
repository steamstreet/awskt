package com.steamstreet.aws.lambda

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

class RedactCredentialsTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun redactsApiGatewayRestCredentialsWhereverTheyAppear() {
        val request = json(
            """
            {
              "path": "/orders",
              "headers": {"Authorization": "Bearer abc", "Cookie": "session=1", "X-API-Key": "k", "Accept": "*/*"},
              "multiValueHeaders": {"authorization": ["Bearer abc"], "Accept": ["*/*"]},
              "queryStringParameters": {"access_token": "t", "page": "2"},
              "requestContext": {
                "identity": {"apiKey": "k", "sourceIp": "1.2.3.4"},
                "authorizer": {"claims": {"sub": "user-1"}}
              },
              "body": "{\"Authorization\":\"in-the-body\"}"
            }
            """
        )

        val expected = json(
            """
            {
              "path": "/orders",
              "headers": {"Authorization": "[REDACTED]", "Cookie": "[REDACTED]", "X-API-Key": "[REDACTED]", "Accept": "*/*"},
              "multiValueHeaders": {"authorization": ["[REDACTED]"], "Accept": ["*/*"]},
              "queryStringParameters": {"access_token": "[REDACTED]", "page": "2"},
              "requestContext": {
                "identity": {"apiKey": "[REDACTED]", "sourceIp": "1.2.3.4"},
                "authorizer": {"claims": {"sub": "user-1"}}
              },
              "body": "{\"Authorization\":\"in-the-body\"}"
            }
            """
        )

        assertEquals(expected, redactCredentials(request))
    }

    @Test
    fun redactsHttpApiCookiesAndKeepsTheirShape() {
        val request = json("""{"cookies": ["a=1", "b=2"], "headers": {"authorization": "Bearer x"}}""")

        assertEquals(
            json("""{"cookies": ["[REDACTED]", "[REDACTED]"], "headers": {"authorization": "[REDACTED]"}}"""),
            redactCredentials(request)
        )
    }

    @Test
    fun redactsTheAuthorizationHeaderAppSyncForwards() {
        val context = json(
            """
            {
              "arguments": {"nextToken": "page-2"},
              "identity": {"sub": "user-1", "claims": {"email": "a@example.com"}},
              "info": {"fieldName": "orders"},
              "request": {"headers": {"authorization": "eyJ...", "x-amz-security-token": "s", "host": "api"}}
            }
            """
        )

        assertEquals(
            json(
                """
                {
                  "arguments": {"nextToken": "page-2"},
                  "identity": {"sub": "user-1", "claims": {"email": "a@example.com"}},
                  "info": {"fieldName": "orders"},
                  "request": {"headers": {"authorization": "[REDACTED]", "x-amz-security-token": "[REDACTED]", "host": "api"}}
                }
                """
            ),
            redactCredentials(context)
        )
    }

    @Test
    fun leavesNullsAndUnnamedFieldsAlone() {
        val payload = json("""{"headers": {"Authorization": null, "X-Trace": "t"}, "list": [1, {"cookie": {"a": 1}}]}""")

        assertEquals(
            json("""{"headers": {"Authorization": null, "X-Trace": "t"}, "list": [1, {"cookie": "[REDACTED]"}]}"""),
            redactCredentials(payload)
        )
    }

    @Test
    fun acceptsExtraNamesInAnyCase() {
        val payload = json("""{"headers": {"X-Session-Token": "s", "Authorization": "a"}}""")

        assertEquals(
            json("""{"headers": {"X-Session-Token": "[REDACTED]", "Authorization": "[REDACTED]"}}"""),
            redactCredentials(payload, credentialFieldNames + "X-SESSION-TOKEN")
        )
    }
}
