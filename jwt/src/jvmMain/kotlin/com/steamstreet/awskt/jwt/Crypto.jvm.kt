package com.steamstreet.awskt.jwt

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK

internal actual val cryptoProvider: CryptographyProvider get() = CryptographyProvider.JDK
