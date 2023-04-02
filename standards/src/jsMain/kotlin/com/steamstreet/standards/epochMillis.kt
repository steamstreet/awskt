package com.steamstreet.standards

import kotlin.js.Date

public actual fun epochMillis(): Long = Date.now().toLong()