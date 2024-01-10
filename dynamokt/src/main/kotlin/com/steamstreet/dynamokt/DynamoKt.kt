package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import com.steamstreet.mutableLazy
import kotlinx.coroutines.runBlocking

/**
 * The global object that stores the Dynamo configuration. Code to read and
 * write from a table will use DynamoKt session, which can be created here.
 */
public class DynamoKt(
    public val table: String,
    public val pkName: String = "pk",
    public val skName: String? = "sk",
    public val builder: (CredentialsProvider?) -> DynamoDbClient = defaultClientBuilder,
    public val defaultCredentials: CredentialsProvider? = null,
    public val ttlAttribute: String? = null
) {
    internal val indexes = hashMapOf<String, DynamoKtIndex>()

    private val defaultClient: DynamoDbClient by lazy {
        builder(defaultCredentials)
    }

    /**
     * Create an AWS session, which is just operations linked with specific credentials.
     */
    public fun session(awsCredentialsProvider: CredentialsProvider? = null): DynamoKtSession {
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
        public var defaultClientBuilder: (CredentialsProvider?) -> DynamoDbClient by mutableLazy {
            return@mutableLazy {
                runBlocking {
                    DynamoDbClient.fromEnvironment {
                        if (it != null) {
                            this.credentialsProvider = it
                        }
                    }
                }
            }
        }
    }
}