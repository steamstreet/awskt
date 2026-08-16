package com.steamstreet.aws.lambda.apigateway.ktor

import com.auth0.jwt.interfaces.Claim
import com.auth0.jwt.interfaces.Payload
import com.steamstreet.aws.appsync.cognito.Claims
import com.steamstreet.aws.appsync.cognito.JsonElementClaim
import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyRequest
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.format.DateTimeFormatter
import java.util.*


private val apiGatewayDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss z yyyy", Locale.ENGLISH)

/**
 * Creates a JWTPrincipal from data in an ApiGatewayProxyRequest.
 */
public fun JWTPrincipal(request: ApiGatewayProxyRequest): JWTPrincipal {
    val auth = Claims(request.requestContext.authorizer?.get("claims")?.jsonObject)

    return JWTPrincipal(object : Payload {
        override fun getIssuer(): String {
            return getClaim("iss").asString()
        }

        override fun getSubject(): String {
            return getClaim("sub").asString()
        }

        override fun getAudience(): MutableList<String> {
            return mutableListOf()
        }

        override fun getExpiresAt(): Date? {
            return getClaim("exp").asString()?.let {
                java.time.Instant.from(apiGatewayDateFormat.parse(it))
            }?.let {
                Date.from(it)
            }
        }

        override fun getNotBefore(): Date? {
            return getClaim("nbf").asString()?.let {
                java.time.Instant.from(apiGatewayDateFormat.parse(it))
            }?.let {
                Date.from(it)
            }
        }

        override fun getIssuedAt(): Date? {
            return getClaim("iat").asString()?.let {
                java.time.Instant.from(apiGatewayDateFormat.parse(it))
            }?.let {
                Date.from(it)
            }
        }

        override fun getId(): String {
            return getClaim("jti").asString()
        }

        override fun getClaim(p0: String?): Claim {
            return if (p0 == null) {
                JsonElementClaim(null)
            } else {
                if (p0 == "sourceIp") {
                    JsonElementClaim(request.requestContext.identity?.sourceIp?.let {
                        JsonPrimitive(it)
                    } ?: JsonNull)
                } else {
                    JsonElementClaim(auth.data?.get(p0))
                }
            }
        }

        override fun getClaims(): MutableMap<String, Claim> {
            val result: Map<String, Claim> = auth.data?.mapValues { (_, value) ->
                JsonElementClaim(value)
            }.orEmpty()

            return result.toMutableMap()
        }
    })
}

public class ApiGatewayJWTConfig {
    /**
     * If set, will be called to allow for a custom JWTPrincipal in test scenarios.
     */
    public var mockPrinciple: (() -> JWTPrincipal?)? = null
}

/**
 * Install the ApiGatewayJWT authentication. This doesn't authenticate (this would be handled by
 * cognito), but installs the JWTPrincipal based on the data in the ApiGateway request.
 */
public val ApiGatewayJWT: ApplicationPlugin<ApiGatewayJWTConfig> =
    createApplicationPlugin("ApiGatewayJWT", ::ApiGatewayJWTConfig) {
        val config = pluginConfig
        application.install(Authentication) {
            provider("api-gateway-jwt") {
                authenticate { context ->
                    if (config.mockPrinciple != null) {
                        config.mockPrinciple?.invoke()
                    } else {
                        context.principal(JWTPrincipal(context.call.apiGatewayRequest))
                    }
                }
            }
        }
    }