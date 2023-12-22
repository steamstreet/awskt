package com.steamstreet.env


import com.steamstreet.mutableLazy
import kotlinx.serialization.json.*
import net.logstash.logback.marker.Markers
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient
import software.amazon.awssdk.services.appconfigdata.model.ResourceNotFoundException
import java.time.Instant

/**
 * Provides caching access to the AppConfig service.
 */
public object AppConfig {
    private var appConfig: AppConfigDataClient by mutableLazy {
        AppConfigDataClient.create()
    }

    private val configurations = mutableMapOf<String, AppConfigConfiguration>()


    public fun getString(
        applicationName: String,
        environment: String,
        configuration: String,
        key: String,
        default: String?
    ): String? {
        return getConfiguration(applicationName, environment, configuration).getString(key) ?: default
    }

    public fun getInt(applicationName: String, environment: String, configuration: String, key: String): Int? {
        return getConfiguration(applicationName, environment, configuration).getInt(key)
    }

    public fun getFeature(applicationName: String, environment: String, configuration: String, key: String): Feature {
        return getConfiguration(applicationName, environment, configuration).getFeature(key)
    }

    private fun getConfiguration(
        applicationName: String,
        environment: String,
        configuration: String
    ): AppConfigConfiguration {
        return synchronized(this) {
            configurations.getOrPut("${applicationName}/${environment}/${configuration}") {
                AppConfigConfiguration(applicationName, environment, configuration, appConfig)
            }
        }
    }
}

private const val NO_DEPLOYMENT_TOKEN = "NO_DEPLOYMENT"

/**
 * Caches and manages data for a single configuration.
 */
public class AppConfigConfiguration(
    private val applicationName: String,
    private val environment: String,
    private val configuration: String,
    private val appConfig: AppConfigDataClient
) {
    private val logger = LoggerFactory.getLogger(this.javaClass.name)

    private var token: String? = null
    private var nextPoll: Instant = Instant.EPOCH
    private var data: JsonObject = JsonObject(emptyMap())

    private fun initSession(now: Instant) {
        synchronized(this) {
            if (token == null || (token == NO_DEPLOYMENT_TOKEN && now > nextPoll)) {
                try {
                    token = appConfig.startConfigurationSession {
                        it.applicationIdentifier(applicationName)
                        it.environmentIdentifier(environment)
                        it.configurationProfileIdentifier(configuration)
                    }.initialConfigurationToken()
                } catch (e: ResourceNotFoundException) {
                    logger.warn("Missing AppConfig configuration: ${applicationName}, ${environment}, ${configuration}")
                    token = NO_DEPLOYMENT_TOKEN
                    nextPoll = now.plusSeconds(60)
                }
            }
        }
    }

    private fun loadConfig() {
        var now = Instant.now()

        initSession(now)

        if (now > nextPoll) {
            synchronized(this) {
                now = Instant.now()
                if (now > nextPoll) {
                    val result = appConfig.getLatestConfiguration {
                        it.configurationToken(token)
                    }

                    token = result.nextPollConfigurationToken()

                    val config = result.configuration()
                    val configString = config.asUtf8String()
                    if (configString.isNotBlank()) {
                        logger.info(Markers.appendRaw("configuration", configString), "Configuration loaded")
                        data = Json.parseToJsonElement(config.asUtf8String()).jsonObject
                    }

                    val interval = result.nextPollIntervalInSeconds()
                    nextPoll = now.plusSeconds(interval.toLong())
                }
            }
        }
    }

    public fun getJson(key: String): JsonElement? {
        loadConfig()
        return data[key]
    }

    public fun getString(key: String): String? {
        return getJson(key)?.jsonPrimitive?.contentOrNull
    }

    public fun getInt(key: String): Int? {
        return getJson(key)?.jsonPrimitive?.intOrNull
    }
}