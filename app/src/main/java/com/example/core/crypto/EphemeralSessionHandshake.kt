package com.example.core.crypto

import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import android.util.Base64
import java.util.UUID
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Versioned, identity-authenticated ephemeral ECDH handshake primitives.
 *
 * MeshRoutingEngine uses this protocol for directly connected, QR-verified private-text peers.
 * The negotiated keys are currently process-local and do not protect file transfer, group,
 * voice, location, or legacy queued traffic. This is a scoped session upgrade, not app-wide
 * forward secrecy.
 */
internal fun shouldAcceptIncomingSessionOffer(
    localPendingSessionId: String,
    incomingSessionId: String
): Boolean = incomingSessionId < localPendingSessionId

class EphemeralSessionHandshake(private val random: SecureRandom = SecureRandom()) {
    data class Offer(
        val version: Int, val sessionId: String, val initiatorId: String, val responderId: String,
        val ephemeralPublicKeyB64: String, val nonceB64: String, val signatureB64: String
    )
    data class Response(
        val version: Int, val sessionId: String, val initiatorId: String, val responderId: String,
        val ephemeralPublicKeyB64: String, val nonceB64: String, val signatureB64: String
    )
    data class SessionKeys(
        val sessionId: String,
        val sendKey: ByteArray,
        val receiveKey: ByteArray,
        val transcriptHash: ByteArray
    )
    data class PendingOffer internal constructor(
        val offer: Offer,
        internal val ephemeralKeyPair: KeyPair
    )
    data class AcceptedOffer(val response: Response, val sessionKeys: SessionKeys)

    fun createOffer(initiatorId: String, responderId: String, identitySigningPrivateKey: PrivateKey): PendingOffer {
        require(initiatorId.isNotBlank() && responderId.isNotBlank() && initiatorId != responderId)
        val ephemeral = generateEphemeralKeyPair()
        val unsigned = Offer(
            VERSION, UUID.randomUUID().toString(), initiatorId, responderId,
            encode(ephemeral.public.encoded), encode(randomBytes(NONCE_BYTES)), ""
        )
        return PendingOffer(unsigned.copy(signatureB64 = encode(sign(identitySigningPrivateKey, offerSigningBytes(unsigned)))), ephemeral)
    }

    fun acceptOffer(
        offer: Offer, expectedInitiatorId: String, responderId: String,
        initiatorIdentitySigningPublicKey: PublicKey, responderIdentitySigningPrivateKey: PrivateKey
    ): AcceptedOffer {
        validateOffer(offer, expectedInitiatorId, responderId, initiatorIdentitySigningPublicKey)
        val responderEphemeral = generateEphemeralKeyPair()
        val unsigned = Response(
            VERSION, offer.sessionId, offer.initiatorId, offer.responderId,
            encode(responderEphemeral.public.encoded), encode(randomBytes(NONCE_BYTES)), ""
        )
        val response = unsigned.copy(
            signatureB64 = encode(sign(responderIdentitySigningPrivateKey, responseSigningBytes(offer, unsigned)))
        )
        val keys = deriveKeys(
            offer.sessionId, offer.initiatorId, offer.responderId, responderEphemeral.private,
            decodeEcPublicKey(offer.ephemeralPublicKeyB64), offer.ephemeralPublicKeyB64,
            response.ephemeralPublicKeyB64, offer.nonceB64, response.nonceB64, false
        )
        return AcceptedOffer(response, keys)
    }

    fun finishOffer(
        pending: PendingOffer, response: Response, expectedResponderId: String,
        responderIdentitySigningPublicKey: PublicKey
    ): SessionKeys {
        val offer = pending.offer
        require(response.version == VERSION) { "Unsupported handshake version" }
        require(response.sessionId == offer.sessionId) { "Session ID mismatch" }
        require(response.initiatorId == offer.initiatorId) { "Initiator mismatch" }
        require(response.responderId == expectedResponderId && response.responderId == offer.responderId) {
            "Responder mismatch"
        }
        val unsigned = response.copy(signatureB64 = "")
        require(verify(responderIdentitySigningPublicKey, responseSigningBytes(offer, unsigned), decode(response.signatureB64))) {
            "Invalid responder signature"
        }
        return deriveKeys(
            offer.sessionId, offer.initiatorId, offer.responderId, pending.ephemeralKeyPair.private,
            decodeEcPublicKey(response.ephemeralPublicKeyB64), offer.ephemeralPublicKeyB64,
            response.ephemeralPublicKeyB64, offer.nonceB64, response.nonceB64, true
        )
    }

    fun verifyOffer(offer: Offer, expectedInitiatorId: String, expectedResponderId: String, key: PublicKey) =
        validateOffer(offer, expectedInitiatorId, expectedResponderId, key)

    private fun validateOffer(offer: Offer, initiatorId: String, responderId: String, key: PublicKey) {
        require(offer.version == VERSION) { "Unsupported handshake version" }
        require(offer.sessionId.isNotBlank() && offer.sessionId.length <= 64)
        require(offer.initiatorId == initiatorId && offer.responderId == responderId)
        require(initiatorId != responderId)
        require(decode(offer.nonceB64).size == NONCE_BYTES) { "Invalid initiator nonce" }
        decodeEcPublicKey(offer.ephemeralPublicKeyB64)
        require(verify(key, offerSigningBytes(offer.copy(signatureB64 = "")), decode(offer.signatureB64))) {
            "Invalid initiator signature"
        }
    }

    private fun deriveKeys(
        sessionId: String, initiatorId: String, responderId: String,
        localPrivate: PrivateKey, remotePublic: PublicKey,
        initiatorEphemeralB64: String, responderEphemeralB64: String,
        initiatorNonceB64: String, responderNonceB64: String, initiatorOriented: Boolean
    ): SessionKeys {
        require(decode(initiatorNonceB64).size == NONCE_BYTES && decode(responderNonceB64).size == NONCE_BYTES)
        val agreement = KeyAgreement.getInstance("ECDH").apply {
            init(localPrivate)
            doPhase(remotePublic, true)
        }
        val shared = agreement.generateSecret()
        try {
            val transcript = listOf(
                "MeshLink-Ephemeral-Session-v$VERSION", sessionId, initiatorId, responderId,
                initiatorEphemeralB64, responderEphemeralB64, initiatorNonceB64, responderNonceB64
            ).joinToString("\n").toByteArray(UTF_8)
            val transcriptHash = java.security.MessageDigest.getInstance("SHA-256").digest(transcript)
            val salt = java.security.MessageDigest.getInstance("SHA-256")
                .digest(decode(initiatorNonceB64) + decode(responderNonceB64))
            val prk = hmac(salt, shared)
            val info = "MeshLink session traffic keys v$VERSION".toByteArray(UTF_8) + transcriptHash
            val material = hkdfExpand(prk, info, 64)
            val initiatorSend = material.copyOfRange(0, 32)
            val responderSend = material.copyOfRange(32, 64)
            prk.fill(0)
            material.fill(0)
            return if (initiatorOriented) SessionKeys(sessionId, initiatorSend, responderSend, transcriptHash)
            else SessionKeys(sessionId, responderSend, initiatorSend, transcriptHash)
        } finally {
            shared.fill(0)
        }
    }

    private fun generateEphemeralKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    private fun decodeEcPublicKey(value: String): PublicKey {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode(value)))
        val ec = key as? java.security.interfaces.ECPublicKey ?: throw IllegalArgumentException("Expected EC public key")
        require(ec.params.curve.field.fieldSize == 256) { "Expected P-256 key" }
        return key
    }

    private fun offerSigningBytes(o: Offer) = listOf(
        "MeshLink-Offer-v$VERSION", o.sessionId, o.initiatorId, o.responderId,
        o.ephemeralPublicKeyB64, o.nonceB64
    ).joinToString("\n").toByteArray(UTF_8)

    private fun responseSigningBytes(o: Offer, r: Response) =
        offerSigningBytes(o.copy(signatureB64 = "")) +
            "\nMeshLink-Response-v$VERSION\n".toByteArray(UTF_8) +
            listOf(r.sessionId, r.initiatorId, r.responderId, r.ephemeralPublicKeyB64, r.nonceB64)
                .joinToString("\n").toByteArray(UTF_8)

    private fun sign(key: PrivateKey, bytes: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
        initSign(key, random); update(bytes); sign()
    }
    private fun verify(key: PublicKey, bytes: ByteArray, signature: ByteArray) = runCatching {
        Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(bytes); verify(signature) }
    }.getOrDefault(false)
    private fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
    }
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val next = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            previous.fill(0)
            previous = next
            val count = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, output, offset, count)
            offset += count
            counter++
        }
        previous.fill(0)
        return output
    }
    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun decode(value: String) = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    companion object {
        const val VERSION = 1
        private const val NONCE_BYTES = 32
    }
}
