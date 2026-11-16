package com.example

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.transport.NfcPeerExchange
import com.example.ui.MeshLinkApp
import com.example.ui.viewmodel.MeshViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MeshViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)
        setContent {
            MeshLinkApp(viewModel = viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val ndefMessage = intent.getParcelableExtra(android.nfc.NfcAdapter.EXTRA_NDEF_MESSAGES, android.nfc.NdefMessage::class.java)
        val peerUri = NfcPeerExchange.extractPeerUri(intent.action, intent.dataString, ndefMessage)
        if (peerUri != null) viewModel.importPeerFromUri(peerUri)
    }
}
