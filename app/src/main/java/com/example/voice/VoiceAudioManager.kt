package com.example.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import com.example.core.permissions.HardwareCapabilities
import com.example.routing.MeshRoutingEngine
import com.example.routing.VoiceIncomingClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.abs

data class VoiceRecordingItem(
    val id: String,
    val peerOrChannel: String,
    val filePath: String,
    val durationMs: Long,
    val isOutgoing: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class VoiceAudioManager(
    private val context: Context,
    private val routingEngine: MeshRoutingEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _playingClipId = MutableStateFlow<String?>(null)
    val playingClipId: StateFlow<String?> = _playingClipId.asStateFlow()

    private val _clips = MutableStateFlow<List<VoiceRecordingItem>>(emptyList())
    val clips: StateFlow<List<VoiceRecordingItem>> = _clips.asStateFlow()

    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var capturedPcm = ByteArrayOutputStream()
    private var recordStartedAt = 0L

    init {
        scope.launch {
            routingEngine.incomingVoice.collect { incoming ->
                saveIncomingVoiceClip(incoming)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startPushToTalk(): Boolean {
        if (!HardwareCapabilities.hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
            return false
        }
        if (_isRecording.value) return true

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf
            )
        }.getOrNull() ?: return false

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }

        audioRecord = recorder
        capturedPcm = ByteArrayOutputStream()
        recordStartedAt = System.currentTimeMillis()
        _isRecording.value = true
        recorder.startRecording()

        recordJob = scope.launch {
            val buffer = ShortArray(minBuf / 2)
            while (isActive && _isRecording.value) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    var peak = 0
                    val bytes = ByteArray(read * 2)
                    for (i in 0 until read) {
                        val sample = buffer[i].toInt()
                        peak = maxOf(peak, abs(sample))
                        bytes[i * 2] = (sample and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                    }
                    capturedPcm.write(bytes)
                    _amplitude.value = (peak / 32768f).coerceIn(0f, 1f)
                }
            }
        }
        return true
    }

    fun stopAndTransmit(targetPeerId: String?) {
        if (!_isRecording.value) return
        _isRecording.value = false
        _amplitude.value = 0f
        recordJob?.cancel()
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null

        val pcmBytes = capturedPcm.toByteArray()
        val durationMs = (System.currentTimeMillis() - recordStartedAt).coerceAtLeast(500L)
        if (pcmBytes.isEmpty()) return

        val dir = File(context.filesDir, "mesh_voice").apply { mkdirs() }
        val clipId = "ptt_${UUID.randomUUID().toString().take(8)}"
        val file = File(dir, "$clipId.pcm")
        file.writeBytes(pcmBytes)

        val item = VoiceRecordingItem(
            id = clipId,
            peerOrChannel = targetPeerId ?: "Local Loopback",
            filePath = file.absolutePath,
            durationMs = durationMs,
            isOutgoing = true
        )
        _clips.value = listOf(item) + _clips.value

        if (!targetPeerId.isNullOrBlank()) {
            scope.launch {
                val b64 = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
                routingEngine.sendVoicePacket(targetPeerId, b64, durationMs, file.absolutePath)
            }
        }
    }

    fun playClip(item: VoiceRecordingItem) {
        scope.launch {
            val file = File(item.filePath)
            if (!file.exists()) return@launch
            val pcm = file.readBytes()
            if (pcm.isEmpty()) return@launch

            _playingClipId.value = item.id
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            runCatching {
                track.play()
                track.write(pcm, 0, pcm.size)
                track.stop()
            }
            runCatching { track.release() }
            _playingClipId.value = null
        }
    }

    private fun saveIncomingVoiceClip(clip: VoiceIncomingClip) {
        val pcmBytes = runCatching { Base64.decode(clip.audioB64, Base64.NO_WRAP) }.getOrNull() ?: return
        val dir = File(context.filesDir, "mesh_voice").apply { mkdirs() }
        val file = File(dir, "${clip.messageId}.pcm")
        file.writeBytes(pcmBytes)
        val item = VoiceRecordingItem(
            id = clip.messageId,
            peerOrChannel = clip.senderAlias,
            filePath = file.absolutePath,
            durationMs = clip.durationMs,
            isOutgoing = false,
            timestamp = clip.timestamp
        )
        _clips.value = listOf(item) + _clips.value
    }

    companion object {
        private const val SAMPLE_RATE = 16000
    }
}
