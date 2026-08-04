package com.zx.smartring.blessing

internal enum class BlessingNfcAction {
    READ,
    IGNORE,
    WRITE
}

internal object BlessingNfcMode {
    fun action(writeModeActive: Boolean, payloadReady: Boolean): BlessingNfcAction = when {
        !writeModeActive -> BlessingNfcAction.READ
        !payloadReady -> BlessingNfcAction.IGNORE
        else -> BlessingNfcAction.WRITE
    }
}
