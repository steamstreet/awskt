package com.steamstreet.awskt.jwt

import dev.whyoleg.cryptography.CryptographyProvider

/**
 * The cryptography provider, named explicitly rather than taken from `CryptographyProvider.Default`:
 * the JDK's providers on the JVM and the statically linked OpenSSL 3 on native. The default registry
 * resolves whatever is registered first, and which that is depends on the consumer's classpath.
 */
internal expect val cryptoProvider: CryptographyProvider
