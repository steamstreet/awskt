package com.steamstreet.aws.lambda.apigateway

import com.steamstreet.aws.lambda.IOLambda

/**
 * Base class to handle ApiGateway requests.
 *
 * JVM-only, and not because of anything in the proxy model: [IOLambda] is an AWS
 * `RequestStreamHandler`, which is how the *JVM* runtime finds a handler. A Kotlin/Native Lambda is
 * found by its `main` function instead, so the equivalent entry point lives in
 * `:lambda:lambda-native`. The request and response types are shared by both — see
 * `ApiGatewayProxy.kt` in `commonMain`.
 */
public abstract class ApiGatewayProxyHandler : IOLambda<ApiGatewayProxyRequest, ApiGatewayProxyResponse>(
    ApiGatewayProxyRequest.serializer(), ApiGatewayProxyResponse.serializer()
)
