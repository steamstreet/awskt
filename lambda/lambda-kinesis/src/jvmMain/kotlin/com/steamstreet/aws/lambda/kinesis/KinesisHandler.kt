package com.steamstreet.aws.lambda.kinesis

import com.steamstreet.aws.lambda.InputLambda

/**
 * Base class for a lambda function that processes a Kinesis stream.
 *
 * The body is now a call to the common [processRecords], so a JVM handler and a native one built
 * with `kinesisLambda` dispatch records through the same code.
 *
 * ### Behaviour change: records are no longer processed twice
 *
 * The previous implementation ran the whole batch through `processRecord`, and then fell into a
 * trailing `return input.Records.forEach { processRecord(it) }` that ran it again — so every record
 * reached the handler exactly twice, on both the async and the sequential path. That was not
 * intended by anything in the class and no test covered it. Delegating to [processRecords] drops
 * the second pass.
 *
 * A subclass that was silently relying on the duplicate — for instance one that counted on a
 * second, sequential pass after an async first pass — will now see each record once.
 */
public abstract class KinesisHandler : InputLambda<KinesisRecords>(KinesisRecords.serializer()) {
    /**
     * If true, records are dispatched concurrently rather than one at a time. Leave it false if the
     * shard's ordering guarantee is why you are reading from Kinesis.
     */
    protected var async: Boolean = false

    override suspend fun handle(input: KinesisRecords) {
        input.processRecords(async) { record ->
            processRecord(record)
        }
    }

    protected open suspend fun processRecord(record: KinesisRecord) {}
}
