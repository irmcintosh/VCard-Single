package com.mackseoul.vcardnative.domain.sharing

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.max

/** Exports the exact matrix of a displayed [ContactQr] as a crisp 1-bit black/white PNG. */
object QrPng {
    const val FILE_NAME = "vcard-native-qr.png"
    const val MIME_TYPE = "image/png"
    const val QUIET_ZONE_MODULES = 4
    const val TARGET_PIXELS = 1200
    const val MIN_MODULE_PIXELS = 6

    // A stored (uncompressed) 1-bit image of at most 1200 x 1200 pixels is about 181 KB,
    // so this bound holds even if compression gained nothing.
    const val MAX_BYTES = 196_608

    private val SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

    fun modulePixels(side: Int): Int {
        require(side in 21..177)
        return max(MIN_MODULE_PIXELS, TARGET_PIXELS / (side + 2 * QUIET_ZONE_MODULES))
    }

    fun imagePixels(qr: ContactQr): Int = (qr.side + 2 * QUIET_ZONE_MODULES) * modulePixels(qr.side)

    fun encode(qr: ContactQr): ByteArray {
        val module = modulePixels(qr.side)
        val size = imagePixels(qr)
        val rowBytes = (size + 7) / 8
        val stride = rowBytes + 1
        val raw = ByteArray(stride * size)
        val row = ByteArray(rowBytes)
        for (moduleY in 0 until qr.side + 2 * QUIET_ZONE_MODULES) {
            // Grayscale bit depth 1: a set bit is white, a clear bit is black.
            row.fill(0xFF.toByte())
            val symbolY = moduleY - QUIET_ZONE_MODULES
            if (symbolY in 0 until qr.side) {
                for (symbolX in 0 until qr.side) {
                    if (!qr.isBlack(symbolX, symbolY)) continue
                    val start = (symbolX + QUIET_ZONE_MODULES) * module
                    for (x in start until start + module) {
                        row[x / 8] = (row[x / 8].toInt() and (0x80 ushr (x % 8)).inv()).toByte()
                    }
                }
            }
            for (line in moduleY * module until (moduleY + 1) * module) {
                // Filter byte 0 (None) is already zero.
                row.copyInto(raw, line * stride + 1)
            }
        }

        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        data.write(SIGNATURE)
        val header = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeInt(size)
                writeInt(size)
                write(byteArrayOf(1, 0, 0, 0, 0))
            }
        }.toByteArray()
        data.chunk("IHDR", header)
        data.chunk("IDAT", deflate(raw))
        data.chunk("IEND", ByteArray(0))
        val bytes = output.toByteArray()
        check(bytes.size <= MAX_BYTES)
        return bytes
    }

    fun isEncodedPng(bytes: ByteArray): Boolean =
        bytes.size in SIGNATURE.size + 1..MAX_BYTES && bytes.copyOf(SIGNATURE.size).contentEquals(SIGNATURE)

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16_384)
            while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
            return output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun DataOutputStream.chunk(type: String, content: ByteArray) {
        val name = type.toByteArray(Charsets.US_ASCII)
        writeInt(content.size)
        write(name)
        write(content)
        writeInt(CRC32().apply { update(name); update(content) }.value.toInt())
    }
}

enum class QrImageContext(internal val fileLabel: String) {
    PrimaryContact("Contact"),
    CustomShare("Custom-Share"),
    HostedProfile("Hosted-Profile"),
}

object QrImageNames {
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
    private val SUFFIX = Regex("[0-9a-f]{8}")
    private val VALID = Regex(
        "VCard-Native-(Contact|Custom-Share|Hosted-Profile)-QR-[0-9]{8}-[0-9]{6}-[0-9]{3}-[0-9a-f]{8}\\.png",
    )
    private val random = SecureRandom()

    fun fileName(
        context: QrImageContext,
        time: LocalDateTime = LocalDateTime.now(),
        suffix: String = randomSuffix(),
    ): String {
        require(suffix.matches(SUFFIX))
        return "VCard-Native-${context.fileLabel}-QR-${TIMESTAMP.format(time)}-$suffix.png"
    }

    fun isValid(name: String): Boolean = VALID.matches(name)

    fun randomSuffix(): String = String.format(Locale.ROOT, "%08x", random.nextInt())
}

/** Saves or shares the exact QR already displayed on a screen; implementations must not re-encode payloads. */
interface QrImageExport {
    /** Returns the file name Android stored after the image was published. */
    suspend fun save(qr: ContactQr, context: QrImageContext): String

    /** Hands the image to the Android Sharesheet; delivery is decided by the chosen app. */
    suspend fun share(qr: ContactQr)
}
