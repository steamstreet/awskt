package com.steamstreet.awskt.core

import com.steamstreet.awskt.signing.AwsCredentials
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultCredentialsOverrideTest {

    private val overridden = AwsCredentials("AKID-OVERRIDE", "secret-override")

    @AfterTest
    fun restoreBuiltInChain() {
        AwsCredentialsDefaults.provider = null
    }

    @Test
    fun theOverrideIsUsedWhenSet() = runTest {
        AwsCredentialsDefaults.provider = StaticCredentialsProvider(overridden)

        assertEquals("AKID-OVERRIDE", defaultCredentialsProvider().resolve().accessKeyId)
    }

    /**
     * The property the override exists for: a client built during startup, before the application
     * got round to installing its provider, must not be stuck on the built-in chain for its life.
     */
    @Test
    fun aProviderObtainedBeforeTheOverrideWasSetStillPicksItUp() = runTest {
        val obtainedEarly = defaultCredentialsProvider()

        AwsCredentialsDefaults.provider = StaticCredentialsProvider(overridden)

        assertEquals("AKID-OVERRIDE", obtainedEarly.resolve().accessKeyId)
    }

    @Test
    fun clearingTheOverrideRestoresTheBuiltInChain() = runTest {
        val provider = defaultCredentialsProvider()
        AwsCredentialsDefaults.provider = StaticCredentialsProvider(overridden)
        assertEquals("AKID-OVERRIDE", provider.resolve().accessKeyId)

        AwsCredentialsDefaults.provider = null

        // The built-in chain reads the ambient environment, which a test cannot rely on, so assert
        // on what it reports itself as rather than on what it resolves.
        assertTrue(provider.toString().contains("EnvironmentCredentialsProvider"), provider.toString())
    }

    @Test
    fun toStringNamesTheProviderInEffectWithoutASecret() {
        AwsCredentialsDefaults.provider = StaticCredentialsProvider(overridden)

        val rendered = defaultCredentialsProvider().toString()

        assertEquals("StaticCredentialsProvider(AKID-OVERRIDE)", rendered)
    }
}
