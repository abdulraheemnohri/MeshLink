package com.example.files

/**
 * File transfer is a storage- and bandwidth-consuming capability. Only peers whose
 * identity has been explicitly QR-verified and is not blocked may offer or receive files.
 */
internal fun canUseFileTransfer(
    verifiedByQr: Boolean,
    blocked: Boolean,
    agreementPublicKey: String
): Boolean = verifiedByQr && !blocked && agreementPublicKey.isNotBlank()
