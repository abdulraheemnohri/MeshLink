package com.example

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
        if (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
            val uriData = intent.dataString
            if (!uriData.isNullOrBlank()) {
                viewModel.importPeerFromUri(uriData)
                return
            }
            val rawMsgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            }
            val firstMsg = rawMsgs?.firstOrNull() as? NdefMessage
            val firstRecord = firstMsg?.records?.firstOrNull()
            val payloadStr = firstRecord?.toUri()?.toString()
                ?: firstRecord?.payload?.let { String(it, Charsets.UTF_8) }
            if (!payloadStr.isNullOrBlank()) {
                viewModel.importPeerFromUri(payloadStr)
            }
        }
    }
}
