package com.steamstreet.aws.test

import aws.sdk.kotlin.services.s3.S3Client
import io.mockk.mockk

class S3Local(
    val mock: S3Client = mockk<S3Client>(relaxed = true)
) : S3Client by mock {
    override fun close() {
        TODO("not implemented")
    }
}