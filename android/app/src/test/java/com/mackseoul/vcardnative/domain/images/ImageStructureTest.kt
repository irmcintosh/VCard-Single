package com.mackseoul.vcardnative.domain.images

import com.mackseoul.vcardnative.domain.profile.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

class ImageStructureTest {
    @Test fun strictPngFramingDigestGeometryAndPixels() {
        val bytes = png()
        val info = ImageStructure.inspect(bytes, true)
        assertEquals(1, info.width)
        val asset = ImageAsset(UUID.randomUUID(), "image/png", bytes.size.toLong(), 1, 1, ImageStructure.digest(bytes))
        ImageStructure.validateCanonical(bytes, asset, ImageRole.Portrait)
        for (bad in listOf(bytes + byteArrayOf(0), bytes.copyOf(bytes.size-1),
            png(raw = byteArrayOf(0,1,2)), png(raw = byteArrayOf(5,1,2,3,4)),
            png(extraType = "acTL"), png(extraType = "tEXt"), png(width = 8193), png(width=8192,height=8192))) {
            try { ImageStructure.inspect(bad, true); fail("Accepted malformed PNG") } catch (_: Exception) { }
        }
        try { ImageStructure.validateCanonical(bytes,asset.copy(sha256="0".repeat(64)),ImageRole.Portrait); fail() }
        catch (_: IllegalArgumentException) { }
        val image = DraftImage(asset,bytes)
        bytes[0] = 0
        assertEquals(-119, image.bytes[0].toInt())
        val copy = image.bytes; copy[0] = 0
        assertEquals(-119, image.bytes[0].toInt())
    }

    @Test fun unsupportedAnimatedTruncatedAndTrailingWebpRejected() {
        val still = webp("VP8L",byteArrayOf(47,0,0,0,0,1))
        assertTrue(ImageStructure.inspect(still).lossless)
        for(bad in listOf(still+byteArrayOf(0),still.copyOf(still.size-1),webp("ANIM",ByteArray(6)),
            webp("VP8L",byteArrayOf(47,0,0,0,-32)))) {
            try { ImageStructure.inspect(bad); fail() } catch (_: Exception) { }
        }
        try { ImageStructure.inspect(still,true); fail() } catch (_: Exception) { }
    }

    companion object {
        fun png(width: Int = 1,height: Int = 1,raw: ByteArray = byteArrayOf(0,1,2,3,4),
            extraType: String? = null): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(-119,80,78,71,13,10,26,10))
            fun chunk(type: String,bytes: ByteArray) {
                val data = java.io.DataOutputStream(out)
                data.writeInt(bytes.size); data.writeBytes(type); data.write(bytes)
                data.writeInt(CRC32().apply { update(type.toByteArray()); update(bytes) }.value.toInt())
            }
            val header = ByteArrayOutputStream()
            java.io.DataOutputStream(header).apply { writeInt(width);writeInt(height);write(byteArrayOf(8,6,0,0,0)) }
            chunk("IHDR",header.toByteArray())
            extraType?.let { chunk(it,ByteArray(0)) }
            val compressed = ByteArrayOutputStream()
            DeflaterOutputStream(compressed).use { it.write(raw) }
            chunk("IDAT",compressed.toByteArray());chunk("IEND",ByteArray(0))
            return out.toByteArray()
        }
        private fun webp(type: String,payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            fun little(value: Int) { repeat(4) { out.write(value ushr (it*8) and 255) } }
            out.write("RIFF".toByteArray()); little(12+payload.size+(payload.size%2))
            out.write("WEBP$type".toByteArray());little(payload.size);out.write(payload)
            if(payload.size%2 != 0) out.write(0)
            return out.toByteArray()
        }
    }
}
