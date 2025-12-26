@file:OptIn(ExperimentalWasmJsInterop::class)

package com.steamstreet

import kotlin.js.ExperimentalWasmJsInterop

private fun dateNow(): Double = js("Date.now()")

public actual fun epochMillis(): Long = dateNow().toLong()