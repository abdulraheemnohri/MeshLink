package com.example.routing

import com.example.core.crypto.EphemeralSessionHandshake
import org.json.JSONObject

/**
 * Wire codec for the authenticated ephemeral-session control packets.
 * The packet signature authenticates the outer MeshPacket; the handshake
 * signatures independently bind the ephemeral keys and transcript.
 */
object EphemeralSessionPacketCodec {
    fun encodeOffer(offer: EphemeralSessionHandshake.Offer): String = JSONObject().apply {
        put("version", offer.version)
        put("sessionId", offer.sessionId)
        put("initiatorId", offer.initiatorId)
        put("responderId", offer.responderId)
        put("ephemeralPublicKeyB64", offer.ephemeralPublicKeyB64)
        put("nonceB64", offer.nonceB64)
        put("signatureB64", offer.signatureB64)
    }.toString()

    fun decodeOffer(payload: String): EphemeralSessionHandshake.Offer? = runCatching {
        val obj = JSONObject(payload)
        EphemeralSessionHandshake.Offer(
            version = obj.getInt("version"),
            sessionId = obj.getString("sessionId"),
            initiatorId = obj.getString("initiatorId"),
            responderId = obj.getString("responderId"),
            ephemeralPublicKeyB64 = obj.getString("ephemeralPublicKeyB64"),
            nonceB64 = obj.getString("nonceB64"),
            signatureB64 = obj.getString("signatureB64")
        )
    }.getOrNull()

    fun encodeResponse(response: EphemeralSessionHandshake.Response): String = JSONObject().apply {
        put("version", response.version)
        put("sessionId", response.sessionId)
        put("initiatorId", response.initiatorId)
        put("responderId", response.responderId)
        put("ephemeralPublicKeyB64", response.ephemeralPublicKeyB64)
        put("nonceB64", response.nonceB64)
        put("signatureB64", response.signatureB64)
    }.toString()

    fun decodeResponse(payload: String): EphemeralSessionHandshake.Response? = runCatching {
        val obj = JSONObject(payload)
        EphemeralSessionHandshake.Response(
            version = obj.getInt("version"),
            sessionId = obj.getString("sessionId"),
            initiatorId = obj.getString("initiatorId"),
            responderId = obj.getString("responderId"),
            ephemeralPublicKeyB64 = obj.getString("ephemeralPublicKeyB64"),
            nonceB64 = obj.getString("nonceB64"),
            signatureB64 = obj.getString("signatureB64")
        )
    }.getOrNull()
}
