package com.steamstreet.aws.lambda.apigateway

import com.steamstreet.aws.lambda.IOLambda
import com.steamstreet.aws.lambda.redactCredentials
import kotlinx.serialization.json.JsonElement

/**
 * Base class to handle API Gateway **HTTP API** (payload format 2.0) requests.
 *
 * The v1 counterpart is [ApiGatewayProxyHandler]; the two are separate classes because they carry
 * separate payload models, and a Lambda is wired to exactly one integration.
 */
public abstract class ApiGatewayV2HttpHandler :
    IOLambda<ApiGatewayV2HttpRequest, ApiGatewayV2HttpResponse>(
        ApiGatewayV2HttpRequest.serializer(), ApiGatewayV2HttpResponse.serializer()
    ) {
    /**
     * Requests and responses are logged with [redactCredentials] applied, so `Authorization`,
     * cookies and API keys stay out of the logs. Override to add names —
     * `{ redactCredentials(it, credentialFieldNames + "x-session-token") }` — or with null to log
     * them as they arrive.
     */
    override val logRedactor: ((JsonElement) -> JsonElement)? = { redactCredentials(it) }
}
