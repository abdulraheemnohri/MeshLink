package com.example.core.crypto

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.core.protocol.MeshPacket
import com.example.core.protocol.MeshPacketCodec
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class LocalIdentityKeys(
    val deviceId: String,
    val alias: String,
    val signingPublicKeyB64: String,
    val agreementPublicKeyB64: String,
    val fingerprint: String
)

data class PeerIdentityPayload(
    val deviceId: String,
    val alias: String,
    val signingPublicKeyB64: String,
    val agreementPublicKeyB64: String,
    val fingerprint: String
)

class MeshCryptoEngine(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    private var signingKeyPair: KeyPair? = null
    private var agreementKeyPair: KeyPair? = null

    @Synchronized
    fun ensureLocalIdentity(defaultAlias: String = "Node-${android.os.Build.MODEL.take(10)}"): LocalIdentityKeys {
        val existingSignPub = prefs.getString(KEY_SIGN_PUB, null)
        val existingSignPriv = prefs.getString(KEY_SIGN_PRIV_ENC, null)
        val existingKxPub = prefs.getString(KEY_KX_PUB, null)
        val existingKxPriv = prefs.getString(KEY_KX_PRIV_ENC, null)
        val alias = prefs.getString(KEY_ALIAS, null) ?: defaultAlias

        if (existingSignPub != null && existingSignPriv != null && existingKxPub != null && existingKxPriv != null) {
            val kf = KeyFactory.getInstance("EC")
            val signPub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(existingSignPub, Base64.NO_WRAP)))
            val signPriv = kf.generatePrivate(PKCS8EncodedKeySpec(unwrapWithKeystore(existingSignPriv)))
            val kxPub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(existingKxPub, Base64.NO_WRAP)))
            val kxPriv = kf.generatePrivate(PKCS8EncodedKeySpec(unwrapWithKeystore(existingKxPriv)))
            signingKeyPair = KeyPair(signPub, signPriv)
            agreementKeyPair = KeyPair(kxPub, kxPriv)
            return buildIdentityModel(alias, existingSignPub, existingKxPub)
        }

        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), random)
        val newSign = kpg.generateKeyPair()
        val newKx = kpg.generateKeyPair()

        val signPubB64 = Base64.encodeToString(newSign.public.encoded, Base64.NO_WRAP)
        val kxPubB64 = Base64.encodeToString(newKx.public.encoded, Base64.NO_WRAP)

        prefs.edit()
            .putString(KEY_ALIAS, alias)
            .putString(KEY_SIGN_PUB, signPubB64)
            .putString(KEY_SIGN_PRIV_ENC, wrapWithKeystore(newSign.private.encoded))
            .putString(KEY_KX_PUB, kxPubB64)
            .putString(KEY_KX_PRIV_ENC, wrapWithKeystore(newKx.private.encoded))
            .apply()

        signingKeyPair = newSign
        agreementKeyPair = newKx
        return buildIdentityModel(alias, signPubB64, kxPubB64)
    }

    fun updateAlias(newAlias: String): LocalIdentityKeys {
        val clean = newAlias.trim().ifEmpty { "MeshNode" }
        prefs.edit().putString(KEY_ALIAS, clean).apply()
        return ensureLocalIdentity(clean)
    }

    private fun buildIdentityModel(
        alias: String,
        signPubB64: String,
        kxPubB64: String
    ): LocalIdentityKeys {
        val fp = computeFingerprint(signPubB64, kxPubB64)
        val deviceId = fp.replace(":", "").take(16).lowercase()
        return LocalIdentityKeys(
            deviceId = deviceId,
            alias = alias,
            signingPublicKeyB64 = signPubB64,
            agreementPublicKeyB64 = kxPubB64,
            fingerprint = fp
        )
    }

    fun computeFingerprint(signPubB64: String, kxPubB64: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val combined = "$signPubB64|$kxPubB64".toByteArray(Charsets.UTF_8)
        val hash = digest.digest(combined)
        return hash.take(16).joinToString(":") { "%02X".format(it) }
    }

    fun areValidIdentityPublicKeys(signPubB64: String, kxPubB64: String): Boolean = runCatching {
        val keyFactory = KeyFactory.getInstance("EC")
        val signing = keyFactory.generatePublic(
            X509EncodedKeySpec(Base64.decode(signPubB64, Base64.NO_WRAP))
        ) as? java.security.interfaces.ECPublicKey ?: return false
        val agreement = keyFactory.generatePublic(
            X509EncodedKeySpec(Base64.decode(kxPubB64, Base64.NO_WRAP))
        ) as? java.security.interfaces.ECPublicKey ?: return false
        signing.params.curve.field.fieldSize == 256 &&
            agreement.params.curve.field.fieldSize == 256
    }.getOrDefault(false)

    fun signPacket(packet: MeshPacket): MeshPacket {
        val identity = ensureLocalIdentity()
        val priv = signingKeyPair?.private
            ?: throw IllegalStateException("Signing key unavailable")
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(priv)
            update(packet.canonicalSigningString().toByteArray(Charsets.UTF_8))
        }.sign()
        val sigB64 = Base64.encodeToString(sig, Base64.NO_WRAP)
        val hash = MeshPacketCodec.computeIntegrityHash(packet)
        return packet.copy(
            senderId = if (packet.senderId.isBlank()) identity.deviceId else packet.senderId,
            signature = sigB64,
            integrityHash = hash
        )
    }

    fun verifyPacketSignature(packet: MeshPacket, senderSigningPubB64: String): Boolean {
        return runCatching {
            val expectedHash = MeshPacketCodec.computeIntegrityHash(packet)
            if (packet.integrityHash.isNotBlank() && packet.integrityHash != expectedHash) {
                return false
            }
            if (packet.signature.isBlank()) return false
            val kf = KeyFactory.getInstance("EC")
            val pubBytes = Base64.decode(senderSigningPubB64, Base64.NO_WRAP)
            val pubKey: PublicKey = kf.generatePublic(X509EncodedKeySpec(pubBytes))
            val sigBytes = Base64.decode(packet.signature, Base64.NO_WRAP)
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(pubKey)
                update(packet.canonicalSigningString().toByteArray(Charsets.UTF_8))
                verify(sigBytes)
            }
        }.getOrDefault(false)
    }

    fun encryptForPeer(
        plaintext: String,
        peerAgreementPubB64: String,
        ratchetCounter: Int = 0
    ): String {
        val aesKey = derivePairwiseAesKey(peerAgreementPubB64, ratchetCounter)
        return encryptAesGcm(plaintext.toByteArray(Charsets.UTF_8), aesKey)
    }

    fun decryptFromPeer(
        ciphertextB64: String,
        peerAgreementPubB64: String,
        ratchetCounter: Int = 0
    ): String? {
        return runCatching {
            val aesKey = derivePairwiseAesKey(peerAgreementPubB64, ratchetCounter)
            String(decryptAesGcm(ciphertextB64, aesKey), Charsets.UTF_8)
        }.getOrNull()
    }

    fun generateGroupKeyB64(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun isValidGroupKeyB64(groupKeyB64: String): Boolean = runCatching {
        Base64.decode(groupKeyB64, Base64.NO_WRAP).size == 32
    }.getOrDefault(false)

    fun encryptWithGroupKey(plaintext: String, groupKeyB64: String): String {
        val key = SecretKeySpec(Base64.decode(groupKeyB64, Base64.NO_WRAP), "AES")
        return encryptAesGcm(plaintext.toByteArray(Charsets.UTF_8), key)
    }

    fun decryptWithGroupKey(ciphertextB64: String, groupKeyB64: String): String? {
        return runCatching {
            val key = SecretKeySpec(Base64.decode(groupKeyB64, Base64.NO_WRAP), "AES")
            String(decryptAesGcm(ciphertextB64, key), Charsets.UTF_8)
        }.getOrNull()
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun encodeIdentityUri(identity: LocalIdentityKeys): String {
        return Uri.Builder()
            .scheme("meshlink")
            .authority("peer")
            .appendQueryParameter("v", "1")
            .appendQueryParameter("id", identity.deviceId)
            .appendQueryParameter("alias", identity.alias)
            .appendQueryParameter("sig", identity.signingPublicKeyB64)
            .appendQueryParameter("kx", identity.agreementPublicKeyB64)
            .build()
            .toString()
    }

    fun decodeIdentityUri(rawUri: String): PeerIdentityPayload? {
        return runCatching {
            val trimmed = rawUri.trim()
            if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                val sig = obj.getString("sig")
                val kx = obj.getString("kx")
                val fp = computeFingerprint(sig, kx)
                return PeerIdentityPayload(
                    deviceId = obj.optString("id", fp.replace(":", "").take(16).lowercase()),
                    alias = obj.getString("alias"),
                    signingPublicKeyB64 = sig,
                    agreementPublicKeyB64 = kx,
                    fingerprint = fp
                )
            }
            val uri = Uri.parse(trimmed)
            if (uri.scheme != "meshlink" || uri.host != "peer") return null
            val sig = uri.getQueryParameter("sig") ?: return null
            val kx = uri.getQueryParameter("kx") ?: return null
            val alias = uri.getQueryParameter("alias") ?: "Peer"
            val fp = computeFingerprint(sig, kx)
            val id = uri.getQueryParameter("id") ?: fp.replace(":", "").take(16).lowercase()
            PeerIdentityPayload(
                deviceId = id,
                alias = alias,
                signingPublicKeyB64 = sig,
                agreementPublicKeyB64 = kx,
                fingerprint = fp
            )
        }.getOrNull()
    }

    data class GroupInvitePayload(
        val groupId: String,
        val name: String,
        val description: String,
        val category: String,
        val groupKeyB64: String,
        val epoch: Int,
        val createdBy: String
    )

    /**
     * Public QR invitations are disabled: embedding the group AES key in a URI turns every
     * screenshot, clipboard entry and log into a copy of the group secret. Use an authenticated
     * peer-to-peer invitation instead. Kept as a compatibility API and fails closed.
     */
    @Deprecated("Public group QR invites expose group secrets; use authenticated peer invitations")
    fun encodeGroupInviteUri(
        groupId: String, name: String, description: String, category: String,
        groupKeyB64: String, epoch: Int, createdBy: String
    ): String = "meshlink://group-invite-disabled"

    /** Legacy v1 group QR URIs contain a bearer group key and are deliberately rejected. */
    @Deprecated("Legacy group QR invites are insecure and no longer accepted")
    fun decodeGroupInviteUri(rawUri: String): GroupInvitePayload? = null
    private fun derivePairwiseAesKey(peerAgreementPubB64: String, ratchetCounter: Int): SecretKey {
        ensureLocalIdentity()
        val priv: PrivateKey = agreementKeyPair?.private
            ?: throw IllegalStateException("Agreement private key missing")
        val kf = KeyFactory.getInstance("EC")
        val peerPub = kf.generatePublic(
            X509EncodedKeySpec(Base64.decode(peerAgreementPubB64, Base64.NO_WRAP))
        )
        val ka = KeyAgreement.getInstance("ECDH").apply {
            init(priv)
            doPhase(peerPub, true)
        }
        val sharedSecret = ka.generateSecret()
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec("MeshLink-HKDF-v1-$ratchetCounter".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
        val derived = mac.doFinal(sharedSecret)
        return SecretKeySpec(derived.copyOf(32), "AES")
    }

    private fun encryptAesGcm(plain: ByteArray, key: SecretKey): String {
        val iv = ByteArray(12)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        val cipherBytes = cipher.doFinal(plain)
        val combined = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptAesGcm(payloadB64: String, key: SecretKey): ByteArray {
        val combined = Base64.decode(payloadB64, Base64.NO_WRAP)
        require(combined.size > 12) { "Invalid ciphertext length" }
        val iv = combined.copyOfRange(0, 12)
        val cipherBytes = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(cipherBytes)
    }

    private fun getOrCreateKeystoreMasterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    private fun wrapWithKeystore(rawBytes: ByteArray): String {
        val key = getOrCreateKeystoreMasterKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val iv = cipher.iv
        val enc = cipher.doFinal(rawBytes)
        val combined = ByteArray(1 + iv.size + enc.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(enc, 0, combined, 1 + iv.size, enc.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun unwrapWithKeystore(wrappedB64: String): ByteArray {
        val key = getOrCreateKeystoreMasterKey()
        val combined = Base64.decode(wrappedB64, Base64.NO_WRAP)
        val ivLen = combined[0].toInt() and 0xFF
        val iv = combined.copyOfRange(1, 1 + ivLen)
        val enc = combined.copyOfRange(1 + ivLen, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(enc)
    }

    companion object {
        private const val PREFS_NAME = "meshlink_keystore_vault"
        private const val KEYSTORE_ALIAS = "meshlink_master_aes_key"
        private const val KEY_ALIAS = "node_alias"
        private const val KEY_SIGN_PUB = "sign_pub"
        private const val KEY_SIGN_PRIV_ENC = "sign_priv_enc"
        private const val KEY_KX_PUB = "kx_pub"
        private const val KEY_KX_PRIV_ENC = "kx_priv_enc"
    }
}
