package com.example

import com.example.core.crypto.EphemeralSessionHandshake
import com.example.routing.EphemeralSessionPacketCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

@RunWith(RobolectricTestRunner::class)
class EphemeralSessionRoutingExchangeTest {
    private fun identity() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @Test
    fun offerAndResponseWirePacketsCompleteAuthenticatedExchange() {
        val alice = identity()
        val bob = identity()
        val protocol = EphemeralSessionHandshake()

        // Initiator serializes the exact SESSION_OFFER payload used by MeshRoutingEngine.
        val pending = protocol.createOffer("alice", "bob", alice.private)
        val offerWire = EphemeralSessionPacketCodec.encodeOffer(pending.offer)
        val receivedOffer = EphemeralSessionPacketCodec.decodeOffer(offerWire)
        assertNotNull(receivedOffer)

        // Responder validates the initiator signature and returns a signed response.
        val accepted = protocol.acceptOffer(
            receivedOffer!!,
            "alice",
            "bob",
            alice.public,
            bob.private
        )
        val responseWire = EphemeralSessionPacketCodec.encodeResponse(accepted.response)
        val receivedResponse = EphemeralSessionPacketCodec.decodeResponse(responseWire)
        assertNotNull(receivedResponse)

        // Initiator validates responder identity and transcript before installing keys.
        val initiatorKeys = protocol.finishOffer(
            pending,
            receivedResponse!!,
            "bob",
            bob.public
        )
        assertArrayEquals(initiatorKeys.sendKey, accepted.sessionKeys.receiveKey)
        assertArrayEquals(initiatorKeys.receiveKey, accepted.sessionKeys.sendKey)
        assertArrayEquals(initiatorKeys.transcriptHash, accepted.sessionKeys.transcriptHash)
        assertTrue(!initiatorKeys.sendKey.contentEquals(initiatorKeys.receiveKey))
    }

    @Test
    fun malformedControlPayloadsAreRejected() {
        assertNull(EphemeralSessionPacketCodec.decodeOffer("{"))
        assertNull(EphemeralSessionPacketCodec.decodeResponse("""{"version":1}"""))
    }
}
