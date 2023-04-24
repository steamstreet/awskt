package com.steamstreet.aws.test

import software.amazon.awssdk.services.s3.S3Client

class S3Local: S3Client {
    override fun close() {
        TODO("not implemented")
    }

    override fun serviceName(): String {
        TODO("not implemented")
    }
}