package com.steamstreet.awskt.jwt

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.openssl3.Openssl3

internal actual val cryptoProvider: CryptographyProvider get() = CryptographyProvider.Openssl3
