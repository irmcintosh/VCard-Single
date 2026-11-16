package com.mackseoul.vcardnative.platform.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.test.InstrumentationTestCase
import com.mackseoul.vcardnative.domain.images.ImageException
import com.mackseoul.vcardnative.domain.images.ImageStructure
import com.mackseoul.vcardnative.domain.profile.ImageRole
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@Suppress("DEPRECATION")
class AndroidImageCanonicalizerTest : InstrumentationTestCase() {
    private val canonicalizer = AndroidImageCanonicalizer

    fun testCanonicalFormatsAlphaAndFreshMetadata() {
        for(format in listOf(Bitmap.CompressFormat.JPEG,Bitmap.CompressFormat.PNG,
            Bitmap.CompressFormat.WEBP_LOSSY,Bitmap.CompressFormat.WEBP_LOSSLESS)) {
            for(alpha in if(format == Bitmap.CompressFormat.JPEG) listOf(false) else listOf(false,true)) {
                val bitmap = fixture(40,20,alpha)
                val source = encode(bitmap,format);bitmap.recycle()
                val image = canonicalizer.canonicalize(ByteArrayInputStream(source),ImageRole.Portrait)
                val expected = if(format == Bitmap.CompressFormat.JPEG ||
                    format == Bitmap.CompressFormat.WEBP_LOSSY && !alpha) "image/jpeg" else "image/png"
                assertEquals(expected,image.asset.mediaType)
                ImageStructure.validateCanonical(image.bytes,image.asset,ImageRole.Portrait)
                val decoded = BitmapFactory.decodeByteArray(image.bytes,0,image.bytes.size)
                try {
                    assertEquals(40,decoded.width);assertEquals(20,decoded.height)
                    if(alpha) assertTrue(Color.alpha(decoded.getPixel(0,0)) < 255)
                } finally { decoded.recycle() }
            }
        }
        val bitmap = fixture(40,20,false)
        val source = encode(bitmap,Bitmap.CompressFormat.JPEG);bitmap.recycle()
        val withComment = source.copyOfRange(0,2)+byteArrayOf(-1,-2,0,13)+"private-gps".toByteArray()+source.copyOfRange(2,source.size)
        val image = canonicalizer.canonicalize(ByteArrayInputStream(withComment),ImageRole.Portrait)
        assertFalse(String(image.bytes,Charsets.ISO_8859_1).contains("private-gps"))
        ImageStructure.inspect(image.bytes,true)
    }

    fun testAllEightExifOrientationsIncludingMirrors() {
        val bitmap = fixture(80,40,false)
        val jpeg = encode(bitmap,Bitmap.CompressFormat.JPEG);bitmap.recycle()
        for(orientation in 1..8) {
            val source = withExif(jpeg,orientation)
            val image = canonicalizer.canonicalize(ByteArrayInputStream(source),ImageRole.Portrait)
            val swap = orientation >= 5
            assertEquals(if(swap) 40 else 80,image.asset.width)
            assertEquals(if(swap) 80 else 40,image.asset.height)
            val decoded = BitmapFactory.decodeByteArray(image.bytes,0,image.bytes.size)
            val expected = when(orientation) {
                1 -> intArrayOf(Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW)
                2 -> intArrayOf(Color.GREEN,Color.RED,Color.YELLOW,Color.BLUE)
                3 -> intArrayOf(Color.YELLOW,Color.BLUE,Color.GREEN,Color.RED)
                4 -> intArrayOf(Color.BLUE,Color.YELLOW,Color.RED,Color.GREEN)
                5 -> intArrayOf(Color.RED,Color.BLUE,Color.GREEN,Color.YELLOW)
                6 -> intArrayOf(Color.BLUE,Color.RED,Color.YELLOW,Color.GREEN)
                7 -> intArrayOf(Color.YELLOW,Color.GREEN,Color.BLUE,Color.RED)
                else -> intArrayOf(Color.GREEN,Color.YELLOW,Color.RED,Color.BLUE)
            }
            try {
                val actual = intArrayOf(decoded.getPixel(5,5),decoded.getPixel(decoded.width-6,5),
                    decoded.getPixel(5,decoded.height-6),decoded.getPixel(decoded.width-6,decoded.height-6))
                for(i in 0..3) {
                    assertTrue("Orientation $orientation corner $i red", kotlin.math.abs(Color.red(actual[i])-Color.red(expected[i])) < 35)
                    assertTrue("Orientation $orientation corner $i green", kotlin.math.abs(Color.green(actual[i])-Color.green(expected[i])) < 35)
                    assertTrue("Orientation $orientation corner $i blue", kotlin.math.abs(Color.blue(actual[i])-Color.blue(expected[i])) < 35)
                }
                assertEquals(1,ImageStructure.inspect(image.bytes,true).orientation)
            } finally { decoded.recycle() }
        }
    }

    fun testTargetSizeAspectNoUpscaleAndLimits() {
        val bitmap = fixture(4096,2048,false)
        val source = encode(bitmap,Bitmap.CompressFormat.JPEG);bitmap.recycle()
        for(role in ImageRole.entries) {
            val image = canonicalizer.canonicalize(ByteArrayInputStream(source),role)
            assertEquals(role.maximumEdge,image.asset.width)
            assertEquals(role.maximumEdge/2,image.asset.height)
            assertTrue(image.asset.byteLength <= ImageStructure.RETAINED_LIMIT)
        }
        val tiny = fixture(3,2,false);val bytes = encode(tiny,Bitmap.CompressFormat.PNG);tiny.recycle()
        assertEquals(3,canonicalizer.canonicalize(ByteArrayInputStream(bytes),ImageRole.Cover).asset.width)
        val over = object : java.io.InputStream() {
            var remaining = ImageStructure.SOURCE_LIMIT+1
            override fun read(): Int = if(remaining-- > 0) 0 else -1
            override fun read(b: ByteArray,off: Int,len: Int): Int {
                if(remaining == 0) return -1
                val count = minOf(len,remaining);b.fill(0,off,off+count);remaining-=count;return count
            }
        }
        try { canonicalizer.canonicalize(over,ImageRole.Portrait);fail("Accepted oversized input") } catch (_: ImageException) { }
        for(bad in listOf(source.copyOf(source.size-1),source+byteArrayOf(0),byteArrayOf(1,2,3),
            withExif(source,9))) {
            try { canonicalizer.canonicalize(ByteArrayInputStream(bad),ImageRole.Portrait);fail("Accepted malformed source") }
            catch(e: ImageException) { assertFalse(e.message!!.contains("private")) }
        }
    }

    fun testPngAnimatedPolyglotCrcAndDimensionsRejectedBeforeDecode() {
        val bitmap = fixture(40,20,true)
        val png = encode(bitmap,Bitmap.CompressFormat.PNG);bitmap.recycle()
        val animated = png.copyOfRange(0,33)+chunk("acTL",byteArrayOf(0,0,0,1,0,0,0,0))+png.copyOfRange(33,png.size)
        val oversized = png.copyOf()
        // Replace IHDR width with 8193 and recompute its checksum, so geometry is the rejection.
        oversized[16]=0;oversized[17]=0;oversized[18]=32;oversized[19]=1
        val crc = java.util.zip.CRC32().apply { update(oversized,12,17) }.value
        repeat(4) { oversized[29+it]=(crc ushr (24-it*8)).toByte() }
        val badCrc = png.copyOf().also { it[29]=(it[29].toInt() xor 1).toByte() }
        for(bad in listOf(animated,oversized,badCrc,png+byteArrayOf(80,75,3,4),png.copyOf(png.size-12))) {
            try { canonicalizer.canonicalize(ByteArrayInputStream(bad),ImageRole.Cover);fail("Accepted malformed PNG") }
            catch (_: ImageException) { }
        }
    }

    fun testPngExifMirrorAndTranspose() {
        val bitmap = fixture(80,40,false)
        val png = encode(bitmap,Bitmap.CompressFormat.PNG);bitmap.recycle()
        for(orientation in 1..8) {
            val source = png.copyOfRange(0,33)+chunk("eXIf",exifTiff(orientation))+png.copyOfRange(33,png.size)
            val image = canonicalizer.canonicalize(ByteArrayInputStream(source),ImageRole.Portrait)
            assertEquals("image/png",image.asset.mediaType)
            assertEquals(if(orientation >= 5) 40 else 80,image.asset.width)
            assertEquals(if(orientation >= 5) 80 else 40,image.asset.height)
            assertEquals(1,ImageStructure.inspect(image.bytes,true).orientation)
        }
    }

    private fun chunk(type: String,payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        java.io.DataOutputStream(out).apply {
            writeInt(payload.size);writeBytes(type);write(payload)
            writeInt(java.util.zip.CRC32().apply { update(type.toByteArray());update(payload) }.value.toInt())
        }
        return out.toByteArray()
    }

    private fun fixture(w: Int,h: Int,alpha: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        bitmap.setHasAlpha(alpha)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint()
        val colors = intArrayOf(Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW)
        for(i in 0..3) {
            paint.color = colors[i];if(alpha) paint.alpha=100
            val x = if(i%2 == 0) 0 else w/2
            val y = if(i<2) 0 else h/2
            canvas.drawRect(x.toFloat(),y.toFloat(),(if(i%2 == 0) w/2 else w).toFloat(),
                (if(i<2) h/2 else h).toFloat(),paint)
        }
        return bitmap
    }
    private fun encode(bitmap: Bitmap,format: Bitmap.CompressFormat): ByteArray =
        ByteArrayOutputStream().also { assertTrue(bitmap.compress(format,95,it)) }.toByteArray()
    private fun withExif(jpeg: ByteArray,orientation: Int): ByteArray {
        val payload = "Exif\u0000\u0000".toByteArray()+exifTiff(orientation)
        val n = payload.size+2
        return jpeg.copyOfRange(0,2)+byteArrayOf(-1,-31,(n ushr 8).toByte(),n.toByte())+payload+jpeg.copyOfRange(2,jpeg.size)
    }
    private fun exifTiff(orientation: Int) =
        byteArrayOf(73,73,42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,orientation.toByte(),0,0,0,0,0,0,0)
}
