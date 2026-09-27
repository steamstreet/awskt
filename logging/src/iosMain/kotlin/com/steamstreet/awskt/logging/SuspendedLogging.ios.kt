package com.steamstreet.awskt.logging

// An iOS app logs to the Xcode console, where readable text serves better than JSON.
public actual var log: Log = Log(DefaultLogPublisher())
