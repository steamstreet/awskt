package com.steamstreet.events

import com.steamstreet.env.Env
import com.steamstreet.mutableLazy

public var poster: ApplicationEventPoster by mutableLazy {
    EventBridgeSubmitter(
        Env.optional("EventBusArn") ?: throw IllegalStateException("Missing EventBusArn"),
        Env.optional("EventPosterSource") ?: throw IllegalStateException("Missing EventPosterSource")
    )
}