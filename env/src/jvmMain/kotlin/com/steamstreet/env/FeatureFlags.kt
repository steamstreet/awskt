package com.steamstreet.env


import com.steamstreet.mutableLazy
import kotlinx.serialization.json.*

/**
 * Manages access to features.
 */
public interface FeatureFlags {
    /**
     * Get the feature with the given feature id.
     */
    public fun feature(featureId: String): Feature
}

/**
 * Gets the feature name from an enum. Converts the first letter to lowercase to match
 * what the console does.
 */
public val <E : Enum<E>> E.featureName: String
    get() {
        return this.name.take(1).lowercase() + this.name.drop(1)
    }

/**
 * Manages access to features using AppConfig.
 */
public class AppConfigFeaturesFlags(
    private val applicationName: String,
    private val environment: String,
    private val configuration: String
) : FeatureFlags {

    /**
     * Get a feature by id.
     */
    override fun feature(featureId: String): Feature {
        return AppConfig.getFeature(applicationName, environment, configuration, featureId)
    }
}

/**
 * The default feature set that uses environment variables to get the application, environment
 * and feature set.
 */
public var Features: FeatureFlags by mutableLazy {
    val (appId, environment, configurationId) = Env["FeatureFlagsConfiguration"].split(":")
    AppConfigFeaturesFlags(appId, environment, configurationId)
}

/**
 * Uses the name of the enumeration as the feature flag name
 */
public fun <E : Enum<E>> E.enabled(default: Boolean = false): Boolean {
    return Features.feature(this.featureName).enabled(default)
}

public fun <E : Enum<E>> E.featureAttribute(key: String): String? {
    return Features.feature(this.featureName).attribute(key)?.jsonPrimitive?.contentOrNull
}

public fun <E : Enum<E>> E.featureInt(key: String, defaultValue: Int = 0): Int {
    return Features.feature(this.featureName).attribute(key)?.jsonPrimitive?.intOrNull ?: defaultValue
}

/**
 * Executes a block if a feature is enabled
 */
public fun <E : Enum<E>, T> withFeature(feature: E, block: () -> T): T? {
    return if (feature.enabled()) {
        block()
    } else {
        null
    }
}

/**
 * Executes a given block if a feature is NOT enabled
 */
public fun <E : Enum<E>, T> withoutFeature(feature: E, block: () -> T): T? {
    return if (!feature.enabled()) {
        block()
    } else {
        null
    }
}

public interface Feature {
    public val enabled: Boolean
    public fun attribute(attributeKey: String): JsonElement?

    /**
     * Is the feature enabled? If the flag doesn't exist, uses the provided default
     */
    public fun enabled(default: Boolean = false): Boolean
}

/**
 * Represents a feature, its enabled state and associated attributes.
 */
public data class JsonFeature(
    val id: String,
    val data: JsonObject?
) : Feature {
    public override val enabled: Boolean by lazy {
        enabled(false)
    }

    public override fun enabled(default: Boolean): Boolean {
        return data?.get("enabled")?.jsonPrimitive?.booleanOrNull ?: default
    }

    public override fun attribute(attributeKey: String): JsonElement? {
        return data?.get(attributeKey)
    }
}

/**
 * Get a feature descriptor from the AppConfig configuration.
 */
public fun AppConfigConfiguration.getFeature(key: String): Feature {
    val featureInfo = getJson(key)
    return JsonFeature(key, featureInfo?.jsonObject)
}