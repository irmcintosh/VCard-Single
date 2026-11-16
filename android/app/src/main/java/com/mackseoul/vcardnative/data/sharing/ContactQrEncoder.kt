package com.mackseoul.vcardnative.data.sharing

import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Mode
import com.google.zxing.qrcode.encoder.Encoder
import com.mackseoul.vcardnative.domain.sharing.ContactExport
import com.mackseoul.vcardnative.domain.sharing.ContactQr
import com.mackseoul.vcardnative.domain.sharing.ContactQrException
import com.mackseoul.vcardnative.domain.sharing.QrFailure

object ContactQrEncoder {
    fun encode(export: ContactExport): ContactQr = encodePayload(export.bytes)

    fun encodeHostedProfileUrl(url: String): ContactQr {
        require(com.mackseoul.vcardnative.domain.profile.ProfileValidator.browserUrlCandidate(url, true) != null) {
            "A saved HTTPS Hosted Profile URL is required."
        }
        return encodePayload(url.toByteArray(Charsets.UTF_8), requireByteMode = false)
    }

    internal fun encodePayload(bytes: ByteArray, requireByteMode: Boolean = true): ContactQr {
        if (bytes.size > ContactQr.MAX_PAYLOAD_BYTES) throw ContactQrException(QrFailure.PayloadLimit)
        val unicode = bytes.any { it.toInt() and 0x80 != 0 }
        val hints = if (unicode) mapOf(EncodeHintType.CHARACTER_SET to "UTF-8") else emptyMap()
        val text = String(bytes, Charsets.UTF_8)
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
            throw ContactQrException(QrFailure.Encoding)
        }
        // The complete vCard framing always contains CR/LF and ';', which force BYTE
        // mode. No compaction, guessed encoding, GS1 or format fallback is enabled.
        val code = try {
            Encoder.encode(text, ErrorCorrectionLevel.M, hints)
        } catch (_: WriterException) {
            throw ContactQrException(QrFailure.EncodingCapacity)
        }
        val matrix = code.matrix
        if (requireByteMode && code.mode != Mode.BYTE || code.ecLevel != ErrorCorrectionLevel.M ||
            code.version.versionNumber !in 1..40 || matrix == null ||
            matrix.width != matrix.height || matrix.width != code.version.dimensionForVersion) {
            throw ContactQrException(QrFailure.Encoding)
        }
        return ContactQr(code.version.versionNumber, BooleanArray(matrix.width * matrix.height) {
            matrix.get(it % matrix.width, it / matrix.width).toInt() == 1
        })
    }
}
