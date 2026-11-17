package com.example.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferPolicyTest {
    @Test
    fun allowsOnlyQrVerifiedUnblockedPeersWithAgreementKey() {
        assertTrue(canUseFileTransfer(verifiedByQr = true, blocked = false, agreementPublicKey = "pinned-key"))
    }

    @Test
    fun rejectsUnverifiedPeers() {
        assertFalse(canUseFileTransfer(verifiedByQr = false, blocked = false, agreementPublicKey = "pinned-key"))
    }

    @Test
    fun rejectsBlockedPeers() {
        assertFalse(canUseFileTransfer(verifiedByQr = true, blocked = true, agreementPublicKey = "pinned-key"))
    }

    @Test
    fun rejectsPeersWithoutAgreementKey() {
        assertFalse(canUseFileTransfer(verifiedByQr = true, blocked = false, agreementPublicKey = ""))
    }
}
