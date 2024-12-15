package com.steamstreet.aws.lambda.apigateway.ktor

import com.auth0.jwt.interfaces.Claim
import com.auth0.jwt.interfaces.Payload
import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyRequest
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import kotlinx.serialization.json.*
import java.time.format.DateTimeFormatter
import java.util.*


private val apiGatewayDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss z yyyy", Locale.ENGLISH)

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

/**
 * Install API gateway JWT authentication.
 */
public fun Application.apiGatewayJwtAuth() {
    install(Authentication) {
        provider {
            authenticate {
                it.principal(JWTPrincipal(it.call.apiGatewayRequest))
            }
        }
    }
}

/**
 * Encapsulates JWT claims
 */
public class Claims(public val data: JsonObject?) {
    public fun claim(key: String): String? = data?.get(key)?.jsonPrimitive?.contentOrNull
}

public class JsonElementClaim(private val el: JsonElement?) : Claim {
    private val asPrimitive: JsonPrimitive? get() = el?.jsonPrimitive

    override fun isNull(): Boolean {
        return el is JsonNull
    }

    override fun isMissing(): Boolean = el == null
    override fun asBoolean(): Boolean? = asPrimitive?.booleanOrNull
    override fun asInt(): Int? = asPrimitive?.intOrNull
    override fun asLong(): Long? = asPrimitive?.longOrNull
    override fun asDouble(): Double? = asPrimitive?.doubleOrNull
    override fun asString(): String? = asPrimitive?.contentOrNull
    override fun asDate(): Date? {
        return null
    }

    override fun <T : Any?> asArray(p0: Class<T>?): Array<T>? {
        return null
    }

    override fun <T : Any?> asList(p0: Class<T>?): MutableList<T>? {
        return null
    }

    override fun asMap(): MutableMap<String, Any>? {
        return null
    }

    override fun <T : Any?> `as`(p0: Class<T>?): T? {
        return null
    }
}