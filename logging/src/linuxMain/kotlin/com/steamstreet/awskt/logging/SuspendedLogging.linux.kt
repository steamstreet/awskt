package com.steamstreet.awskt.logging

// Linux is where native Lambdas run, and Lambda forwards stdout to CloudWatch, so the default is one
// JSON object per line. See JsonLogPublisher.
public actual var log: Log = Log(JsonLogPublisher())
