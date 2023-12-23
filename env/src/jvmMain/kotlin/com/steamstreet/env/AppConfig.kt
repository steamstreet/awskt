package com.steamstreet.env


import aws.sdk.kotlin.services.appconfigdata.AppConfigDataClient
import aws.sdk.kotlin.services.appconfigdata.getLatestConfiguration
import aws.sdk.kotlin.services.appconfigdata.model.ResourceNotFoundException
import aws.sdk.kotlin.services.appconfigdata.startConfigurationSession
import com.steamstreet.mutableLazy
import com.steamstreet.strings.isNotNullOrBlank
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import net.logstash.logback.marker.Markers
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Provides caching access to the AppConfig service.
 */
public object AppConfig {
    private var appConfig: AppConfigDataClient by mutableLazy {
        AppConfigDataClient {}
    }

    private val configurations = mutableMapOf<String, AppConfigConfiguration>()


    public suspend fun getString(
        applicationName: String,
        environment: String,
        configuration: String,
        key: String,
        default: String?
    ): String? {
        return getConfiguration(applicationName, environment, configuration).getString(key) ?: default
    }

    public suspend fun getInt(applicationName: String, environment: String, configuration: String, key: String): Int? {
        return getConfiguration(applicationName, environment, configuration).getInt(key)
    }

    public suspend fun getFeature(
        applicationName: String,
        environment: String,
        configuration: String,
        key: String
    ): Feature {
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
    private val mutex = Mutex()

    private var token: String? = null
    private var nextPoll: Instant = Instant.EPOCH
    private var data: JsonObject = JsonObject(emptyMap())

    private suspend fun initSession(now: Instant) {
        mutex.withLock {
            if (token == null || (token == NO_DEPLOYMENT_TOKEN && now > nextPoll)) {
                try {
                    token = appConfig.startConfigurationSession {
                        applicationIdentifier = applicationName
                        environmentIdentifier = environment
                        configurationProfileIdentifier = configuration
                    }.initialConfigurationToken
                } catch (e: ResourceNotFoundException) {
                    logger.warn("Missing AppConfig configuration: ${applicationName}, ${environment}, ${configuration}")
                    token = NO_DEPLOYMENT_TOKEN
                    nextPoll = now.plusSeconds(60)
                }
            }
        }
    }

    private suspend fun loadConfig() {
        var now = Instant.now()

        initSession(now)

        if (now > nextPoll) {
            mutex.withLock {
                now = Instant.now()
                if (now > nextPoll) {
                    val result = appConfig.getLatestConfiguration {
                        configurationToken = token
                    }

                    token = result.nextPollConfigurationToken

                    val config = result.configuration
                    val configString = config?.toString(Charsets.UTF_8)
                    if (configString.isNotNullOrBlank()) {
                        logger.info(Markers.appendRaw("configuration", configString), "Configuration loaded")
                        data = Json.parseToJsonElement(configString!!).jsonObject
                    }
                    val interval = result.nextPollIntervalInSeconds
                    nextPoll = now.plusSeconds(interval.toLong())
                }
            }
        }
    }

    public suspend fun getJson(key: String): JsonElement? {
        loadConfig()
        return data[key]
    }

    public suspend fun getString(key: String): String? {
        return getJson(key)?.jsonPrimitive?.contentOrNull
    }

    public suspend fun getInt(key: String): Int? {
        return getJson(key)?.jsonPrimitive?.intOrNull
    }
}