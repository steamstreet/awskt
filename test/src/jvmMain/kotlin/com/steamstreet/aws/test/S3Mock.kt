package com.steamstreet.aws.test

import aws.sdk.kotlin.services.s3.S3Client
import io.mockk.mockk

public class S3Local(
    private val mock: S3Client = mockk<S3Client>(relaxed = true)
) : S3Client by mock, MockService {
    override fun close() {
    }
}