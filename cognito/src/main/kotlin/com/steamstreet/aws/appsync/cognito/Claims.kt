package com.steamstreet.aws.appsync.cognito

import com.auth0.jwt.interfaces.Claim
import kotlinx.serialization.json.*
import java.util.*


/**
 * Encapsulates JWT claims
 */
public class Claims(public val data: JsonObject?) {
    public fun claim(key: String): String? = data?.get(key)?.jsonPrimitive?.contentOrNull
}

/**
 * A JWT Claim from a JSON element.
 */
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

    // these functions rely on reflection which we'll generally try to avoid.
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