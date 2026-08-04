package com.zx.smartring.blessing

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable

object BlessingNfc {
    fun message(payload: BlessingPayload): NdefMessage = NdefMessage(
        arrayOf(
            NdefRecord.createMime(
                BlessingPayload.MIME_TYPE,
                payload.blessingId.toByteArray(Charsets.US_ASCII)
            ),
            NdefRecord.createApplicationRecord(payload.packageName)
        )
    )

    fun fromIntent(intent: Intent?): BlessingTagReference? {
        val source = intent ?: return null
        if (source.action !in setOf(
                NfcAdapter.ACTION_NDEF_DISCOVERED,
                NfcAdapter.ACTION_TAG_DISCOVERED,
                NfcAdapter.ACTION_TECH_DISCOVERED
            )
        ) {
            return null
        }
        @Suppress("DEPRECATION")
        val messages = source.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            ?.mapNotNull { it as? NdefMessage }
            .orEmpty()
        return messages.firstNotNullOfOrNull(::fromMessage)
    }

    fun read(tag: Tag): BlessingTagReference? {
        val technology = Ndef.get(tag) ?: return null
        return try {
            technology.connect()
            fromMessage(technology.ndefMessage)
        } finally {
            runCatching { technology.close() }
        }
    }

    fun write(tag: Tag, message: NdefMessage) {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                check(ndef.isWritable) { "NFC tag is read-only" }
                check(ndef.maxSize >= message.toByteArray().size) { "NFC tag is too small" }
                ndef.writeNdefMessage(message)
            } finally {
                runCatching { ndef.close() }
            }
            return
        }
        val formatable = NdefFormatable.get(tag) ?: error("NFC tag does not support NDEF")
        try {
            formatable.connect()
            formatable.format(message)
        } finally {
            runCatching { formatable.close() }
        }
    }

    private fun fromMessage(message: NdefMessage): BlessingTagReference? =
        message.records.firstNotNullOfOrNull { record ->
            if (
                record.tnf != NdefRecord.TNF_MIME_MEDIA ||
                !record.type.contentEquals(BlessingPayload.MIME_TYPE.toByteArray(Charsets.US_ASCII))
            ) {
                null
            } else {
                BlessingTagReference.fromNdefPayload(record.payload.toString(Charsets.UTF_8))
            }
        }
}
