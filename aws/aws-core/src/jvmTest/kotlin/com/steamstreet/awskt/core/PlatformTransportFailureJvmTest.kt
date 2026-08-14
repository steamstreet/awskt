package com.steamstreet.awskt.core

import io.ktor.client.network.sockets.ConnectTimeoutException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Lives in `jvmTest` rather than `commonTest` because the point of these assertions is the *types*.
 * The shared tests can only construct messages; here the throwables are the real
 * `java.net`/`javax.net.ssl` classes an engine actually raises, several of which arrive with no
 * message at all — which is the gap [platformTransportFailureHint] exists to close.
 */
class PlatformTransportFailureJvmTest {

    /**
     * Named so that neither `name.contains(...)` nor the message patterns can recognise them. If
     * these classify as NOT_SENT, it is the typed hint and nothing else that said so — which is the
     * upgrade-fragility this hook was added to remove, since an engine wrapping or renaming an
     * exception is exactly what breaks a name match.
     */
    private class EngineDialFailure : ConnectException()

    private class EngineTlsFailure : SSLHandshakeException("bad_certificate")

    @Test
    fun theTypedHintRecognisesShapesNoNameOrMessagePatternCan() {
        assertEquals(TransportFailure.NOT_SENT, classifyTransportFailure(EngineDialFailure()))
        assertEquals(TransportFailure.NOT_SENT, classifyTransportFailure(EngineTlsFailure()))
    }

    /**
     * The empty message is the point. `java.net.ConnectException` is routinely thrown with nothing
     * but its class, so the message matching in `classifyTransportFailure` sees an empty string.
     */
    @Test
    fun typedJvmFailuresAreNotSentEvenWithoutAMessage() {
        assertEquals(TransportFailure.NOT_SENT, classifyTransportFailure(ConnectException()))
        assertEquals(TransportFailure.NOT_SENT, classifyTransportFailure(UnknownHostException()))
        assertEquals(TransportFailure.NOT_SENT, classifyTransportFailure(UnresolvedAddressException()))
        assertEquals(
            TransportFailure.NOT_SENT,
            classifyTransportFailure(SSLHandshakeException("handshake_failure")),
        )
        assertEquals(
            TransportFailure.NOT_SENT,
            classifyTransportFailure(ConnectTimeoutException("engine gave up")),
        )
    }

    /** Asked of the hook directly, so a regression names the hook rather than the classifier. */
    @Test
    fun theHintAnswersOnlyForShapesItCanProve() {
        assertEquals(TransportFailure.NOT_SENT, platformTransportFailureHint(EngineDialFailure()))
        assertEquals(TransportFailure.NOT_SENT, platformTransportFailureHint(EngineTlsFailure()))
        assertEquals(TransportFailure.NOT_SENT, platformTransportFailureHint(UnresolvedAddressException()))
        assertEquals(TransportFailure.NOT_SENT, platformTransportFailureHint(UnknownHostException()))

        // Null, not AMBIGUOUS: a non-null answer ends the cause walk, so the hook staying quiet is
        // what lets a deeper cause still be recognised.
        assertNull(platformTransportFailureHint(RuntimeException("something odd")))
        assertNull(platformTransportFailureHint(SocketTimeoutException("Read timed out")))
        assertNull(platformTransportFailureHint(SSLException("connection reset during read")))
    }

    /**
     * `SocketTimeoutException` carries "Read timed out" as readily as "connect timed out", and the
     * first of those fires after AWS has applied the write. It must not be on the typed list — the
     * narrow message match is the only thing allowed to speak for it.
     */
    @Test
    fun postSendJvmShapesAreStillAmbiguous() {
        assertEquals(
            TransportFailure.AMBIGUOUS,
            classifyTransportFailure(SocketTimeoutException("Read timed out")),
        )
        // A TLS failure *mid-stream* says nothing about whether the request was received, so only
        // the handshake subclass qualifies.
        assertEquals(TransportFailure.AMBIGUOUS, classifyTransportFailure(SSLException("Connection reset")))
        // ...while the JDK's connect wording still resolves through the message patterns.
        assertEquals(
            TransportFailure.NOT_SENT,
            classifyTransportFailure(SocketTimeoutException("connect timed out")),
        )
    }

    /** A typed cause buried under untyped wrappers is still found — the hook runs per chain link. */
    @Test
    fun theHintIsConsultedForEveryCause() {
        val wrapped = RuntimeException("call failed", IllegalStateException("engine", EngineDialFailure()))
        assertEquals(TransportFailure.NOT_SENT, classifyTransportFailure(wrapped))
    }
}
