package com.example

import com.example.core.crypto.EphemeralSessionHandshake
import com.example.core.crypto.shouldAcceptIncomingSessionOffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

@RunWith(RobolectricTestRunner::class)
class EphemeralSessionHandshakeTest {
    private fun identity() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @Test fun simultaneousOffersChooseTheSameSessionOnBothPeers() {
        val offerA = "0000-a-session"
        val offerB = "ffff-b-session"
        assertFalse(shouldAcceptIncomingSessionOffer(offerA, offerB))
        assertTrue(shouldAcceptIncomingSessionOffer(offerB, offerA))
    }

    @Test fun simultaneousOfferTieBreakIsIndependentOfPeerOrder() {
        val first = "a-session"
        val second = "b-session"
        val acceptedByFirstPeer = shouldAcceptIncomingSessionOffer(first, second)
        val acceptedBySecondPeer = shouldAcceptIncomingSessionOffer(second, first)
        assertNotEquals(acceptedByFirstPeer, acceptedBySecondPeer)
    }

    @Test fun bothSidesDeriveMatchingOppositeDirectionalKeys() {
        val alice = identity()
        val bob = identity()
        val protocol = EphemeralSessionHandshake()
        val pending = protocol.createOffer("alice", "bob", alice.private)
        val accepted = protocol.acceptOffer(pending.offer, "alice", "bob", alice.public, bob.private)
        val initiator = protocol.finishOffer(pending, accepted.response, "bob", bob.public)
        assertArrayEquals(initiator.sendKey, accepted.sessionKeys.receiveKey)
        assertArrayEquals(initiator.receiveKey, accepted.sessionKeys.sendKey)
        assertArrayEquals(initiator.transcriptHash, accepted.sessionKeys.transcriptHash)
        assertFalse(initiator.sendKey.contentEquals(initiator.receiveKey))
    }

    @Test fun rejectsOfferSignedByWrongIdentity() {
        val alice = identity()
        val impostor = identity()
        val bob = identity()
        val protocol = EphemeralSessionHandshake()
        val offer = protocol.createOffer("alice", "bob", alice.private).offer
        try {
            protocol.acceptOffer(offer, "alice", "bob", impostor.public, bob.private)
            fail("Expected signature rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsTamperedResponse() {
        val alice = identity()
        val bob = identity()
        val protocol = EphemeralSessionHandshake()
        val pending = protocol.createOffer("alice", "bob", alice.private)
        val accepted = protocol.acceptOffer(pending.offer, "alice", "bob", alice.public, bob.private)
        try {
            protocol.finishOffer(pending, accepted.response.copy(nonceB64 = pending.offer.nonceB64), "bob", bob.public)
            fail("Expected response signature rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsWrongResponderBinding() {
        val alice = identity()
        val bob = identity()
        val protocol = EphemeralSessionHandshake()
        val pending = protocol.createOffer("alice", "bob", alice.private)
        try {
            protocol.acceptOffer(pending.offer, "alice", "mallory", alice.public, bob.private)
            fail("Expected responder binding rejection")
        } catch (_: IllegalArgumentException) { }
    }
}
