package com.steamstreet.aws.appsync

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream

/**
 * The context provided to AppSync lambda handlers
 */
@Serializable
public data class AppSyncContext(
    val arguments: JsonObject,
    val source: JsonObject? = null,
    val identity: AppSyncIdentity? = null,
    val info: AppSyncInfo,
    val request: AppSyncRequest? = null,
    val stash: JsonObject? = null
)

/**
 * Provides information about the HTTP request that invoked the GraphQL API.
 */
@Serializable
public data class AppSyncRequest(
    val headers: JsonObject? = null
)

/**
 * Provides information about the entity making the call.
 */
@Serializable
public data class AppSyncIdentity(
    val accountId: String? = null,
    val cognitoIdentityPoolId: String? = null,
    val cognitoIdentityId: String? = null,
    val cognitoIdentityAuthType: String? = null,
    val cognitoIdentityAuthProvider: String? = null,
    val username: String? = null,
    val userArn: String? = null,
    val sourceIp: List<String>? = null,
    val sub: String? = null,
    val issuer: String? = null,
    val claims: JsonObject? = null,
    val defaultAuthStrategy: String? = null,
    val groups: List<String>? = null
)

/**
 * Provides information about the GraphQL request.
 */
@Serializable
public data class AppSyncInfo(
    val fieldName: String,
    val parentTypeName: String? = null,
    val variables: JsonObject? = null,

    /**
     * Contains a list of paths requested. For example:
     * owner
     * id
     * name/first
     *
     * Note that when a nested path is requested, the parent field will be included
     * separately as well. So, if name/first is requested, the selection set will include both:
     * - name
     * - name/first
     */
    val selectionSetList: List<String>? = null,
    val selectionSetGraphQL: String? = null
)

private val appSyncParser = Json {
    ignoreUnknownKeys = true
}

/**
 * Get the AppSyncContext from the InputStream of a Lambda.
 */
@OptIn(ExperimentalSerializationApi::class)
public fun InputStream.appSyncContext(): AppSyncContext = appSyncParser.decodeFromStream(AppSyncContext.serializer(), this)
public fun String.appSyncContext(): AppSyncContext = appSyncParser.decodeFromString(AppSyncContext.serializer(), this)