package com.steamstreet.awskt.logging

public actual var log: Log = Log(Slf4JLogPublisher())