package com.example

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import com.example.transport.NfcPeerExchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NfcPeerExchangeTest {
    @Test fun acceptsOnlyMeshLinkPeerUris() {
        assertEquals("meshlink://peer/abc123", NfcPeerExchange.validatePeerUri("meshlink://peer/abc123"))
        assertNull(NfcPeerExchange.validatePeerUri("https://example.com/peer"))
        assertNull(NfcPeerExchange.validatePeerUri("meshlink://other/abc123"))
        assertNull(NfcPeerExchange.validatePeerUri("meshlink://peer"))
        assertNull(NfcPeerExchange.validatePeerUri("meshlink://user@peer/abc123"))
    }

    @Test fun createsAndReadsNdefUriMessage() {
        val message = NfcPeerExchange.createMessage("meshlink://peer/abc123")
        assertNotNull(message)
        val extracted = NfcPeerExchange.extractPeerUri(
            NfcAdapter.ACTION_NDEF_DISCOVERED,
            null,
            message
        )
        assertEquals("meshlink://peer/abc123", extracted)
    }

    @Test fun rejectsWrongIntentActionAndOversizedPayload() {
        val message = NdefMessage(arrayOf(NdefRecord.createUri("meshlink://peer/abc123")))
        assertNull(NfcPeerExchange.extractPeerUri("android.intent.action.VIEW", null, message))
        assertNull(NfcPeerExchange.validatePeerUri("meshlink://peer/" + "x".repeat(3000)))
    }
}
