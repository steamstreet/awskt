package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import com.steamstreet.mutableLazy

/**
 * The global object that stores the Dynamo configuration. Code to read and
 * write from a table will use DynamoKt session, which can be created here.
 */
public class DynamoKt(
    public val table: String,
    public val pkName: String = "pk",
    public val skName: String? = "sk",
    public val builder: DynamoDbClient.Builder = defaultClientBuilder,
    public val defaultCredentials: CredentialsProvider? = null,
    public val ttlAttribute: String? = null
) {
    internal val indexes = hashMapOf<String, DynamoKtIndex>()

    private val defaultClient: DynamoDbClient by lazy {
        builder.apply {
            if (defaultCredentials != null) {
                this.config.credentialsProvider = defaultCredentials
            }
        }.build()
    }

    /**
     * Create an AWS session, which is just operations linked with specific credentials.
     */
    public fun session(awsCredentialsProvider: CredentialsProvider? = null): DynamoKtSession {
        val client = if (awsCredentialsProvider == null) {
            defaultClient
        } else {
            builder.apply {
                config.credentialsProvider = awsCredentialsProvider
            }.build()
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
        public var defaultClientBuilder: DynamoDbClient.Builder by mutableLazy {
            DynamoDbClient.builder()
        }
    }
}