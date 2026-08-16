package com.steamstreet.aws.lambda.apigateway

import com.steamstreet.aws.lambda.IOLambda

/**
 * Base class to handle API Gateway **HTTP API** (payload format 2.0) requests.
 *
 * The v1 counterpart is [ApiGatewayProxyHandler]; the two are separate classes because they carry
 * separate payload models, and a Lambda is wired to exactly one integration.
 */
public abstract class ApiGatewayV2HttpHandler :
    IOLambda<ApiGatewayV2HttpRequest, ApiGatewayV2HttpResponse>(
        ApiGatewayV2HttpRequest.serializer(), ApiGatewayV2HttpResponse.serializer()
    )
