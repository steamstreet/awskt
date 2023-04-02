package com.steamstreet

import kotlin.js.Date

public actual fun epochMillis(): Long = Date.now().toLong()