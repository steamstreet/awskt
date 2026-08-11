package com.steamstreet.dynamokt

import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.dynamodb.DynamoDb
import com.steamstreet.mutableLazy
import kotlinx.serialization.json.Json

/**
 * The global object that stores the Dynamo configuration. Code to read and
 * write from a table will use DynamoKt session, which can be created here.
 */
public class DynamoKt(
    public val table: String,
    public val pkName: String = "pk",
    public val skName: String? = "sk",
    public val builder: (AwsCredentialsProvider?) -> DynamoDb = defaultClientBuilder,
    public val defaultCredentials: AwsCredentialsProvider? = null,
    public val ttlAttribute: String? = null
) {
    internal val indexes = hashMapOf<String, DynamoKtIndex>()

    private val defaultClient: DynamoDb by lazy {
        builder(defaultCredentials)
    }

    /**
     * Allows configuration of the encoder for serialized types
     */
    public var entityJsonEncoder: Json = Json {
        this.encodeDefaults = false
        this.ignoreUnknownKeys = true
    }

    /**
     * Get all registered indexes of this table.
     */
    public fun indexes(): Collection<DynamoKtIndex> = indexes.values

    /**
     * Create an AWS session, which is just operations linked with specific credentials.
     */
    public fun session(awsCredentialsProvider: AwsCredentialsProvider? = null): DynamoKtSession {
        val client = if (awsCredentialsProvider == null) {
            defaultClient
        } else {
            builder(awsCredentialsProvider)
        }
        return DynamoKtSession(
            this,
            client,
            table, pkName, skName
        )
    }

    public fun registerIndex(name: String, pk: String, sk: String?): DynamoKtIndex {
        return DynamoKtIndex(name, pk, sk).apply {
            indexes[name] = this
        }
    }

    public companion object {
        /**
         * How a client is built. Swappable so tests can point at LocalStack.
         *
         * No `runBlocking` any more: client construction is not suspending (plan Decision 4), so
         * region, endpoint and credentials all resolve lazily at the first call instead of blocking
         * a coroutine thread here. That deletes one of the five `runBlocking` wrappers the plan
         * counted, and it is what lets a client be constructed from a non-suspending context.
         */
        public var defaultClientBuilder: (AwsCredentialsProvider?) -> DynamoDb by mutableLazy {
            return@mutableLazy { credentials ->
                DynamoDb {
                    if (credentials != null) credentialsProvider = credentials
                }
            }
        }
    }
}