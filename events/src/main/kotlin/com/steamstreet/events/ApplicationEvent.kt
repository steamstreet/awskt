package com.steamstreet.events

public suspend fun postEvent(type: String, detail: String, source: String? = null): Unit =
    poster.post(type, detail, source)