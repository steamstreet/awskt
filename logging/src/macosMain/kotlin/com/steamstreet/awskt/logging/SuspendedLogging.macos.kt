package com.steamstreet.awskt.logging

// macOS is the development host for native Lambdas, so it logs the way they do in production.
public actual var log: Log = Log(JsonLogPublisher())
