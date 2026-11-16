package com.example.transport

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.net.Uri

/**
 * NFC is an identity/bootstrap channel only. It does not provide a sustained
 * packet transport and Android Beam is not used.
 */
object NfcPeerExchange {
    private const val MAX_URI_BYTES = 2048
    private const val SCHEME = "meshlink"
    private const val HOST = "peer"

    fun createMessage(peerUri: String): NdefMessage? {
        val normalized = validatePeerUri(peerUri) ?: return null
        return NdefMessage(arrayOf(NdefRecord.createUri(Uri.parse(normalized))))
    }

    fun extractPeerUri(intentAction: String?, dataUri: String?, message: NdefMessage?): String? {
        if (intentAction != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            intentAction != NfcAdapter.ACTION_TAG_DISCOVERED &&
            intentAction != NfcAdapter.ACTION_TECH_DISCOVERED
        ) return null

        validatePeerUri(dataUri)?.let { return it }
        val records = message?.records ?: return null
        for (record in records) {
            val uri = runCatching { record.toUri()?.toString() }.getOrNull()
            validatePeerUri(uri)?.let { return it }
            val text = runCatching { String(record.payload, Charsets.UTF_8).trim() }.getOrNull()
            validatePeerUri(text)?.let { return it }
        }
        return null
    }

    fun validatePeerUri(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.toByteArray(Charsets.UTF_8).size > MAX_URI_BYTES) return null
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
        if (!uri.isHierarchical || uri.scheme != SCHEME || uri.host != HOST) return null
        if (uri.userInfo != null || uri.port != -1 || uri.fragment != null) return null
        if (uri.lastPathSegment.isNullOrBlank()) return null
        return uri.toString()
    }
}
