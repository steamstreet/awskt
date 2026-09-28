package com.steamstreet.env

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EnvTest {
    private val missing = "AWSKT_ENV_TEST_NEVER_SET"

    @Test
    fun getReturnsASetValue() {
        registerEnvironmentVariable("AWSKT_ENV_TEST_GET", "hello")

        assertEquals("hello", Env["AWSKT_ENV_TEST_GET"])
    }

    @Test
    fun getNamesAMissingVariable() {
        val failure = assertFailsWith<IllegalStateException> { Env[missing] }

        assertEquals("Environment variable '$missing' is not set", failure.message)
    }

    @Test
    fun lazyDefersTheFailureAndNamesTheVariable() {
        // Creating the lazy must not read the variable.
        val lazy = Env.lazy(missing)

        val failure = assertFailsWith<IllegalStateException> { lazy.value }
        assertEquals("Environment variable '$missing' is not set", failure.message)
    }

    @Test
    fun lazyReturnsASetValue() {
        registerEnvironmentVariable("AWSKT_ENV_TEST_LAZY", "later")

        assertEquals("later", Env.lazy("AWSKT_ENV_TEST_LAZY").value)
    }

    @Test
    fun aRequiredPropertyNamesAMissingVariable() {
        val failure = assertFailsWith<IllegalStateException> { env(missing).value }

        assertEquals("Environment variable '$missing' is not set", failure.message)
    }

    @Test
    fun optionalAndDefaultsStillReadMissingAsAbsent() {
        assertNull(Env.optional(missing))
        assertNull(env(missing).optional.value)
        assertEquals("fallback", env(missing, "fallback").value)
    }
}
