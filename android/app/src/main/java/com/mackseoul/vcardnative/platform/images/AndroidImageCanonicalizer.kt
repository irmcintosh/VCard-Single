package com.mackseoul.vcardnative.platform.images

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import com.mackseoul.vcardnative.domain.images.ImageException
import com.mackseoul.vcardnative.domain.images.ImageStructure
import com.mackseoul.vcardnative.domain.profile.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max

object AndroidImageCanonicalizer {
    fun canonicalize(input: InputStream, role: ImageRole): DraftImage {
        try {
            val source = boundedRead(input)
            val info = ImageStructure.inspect(source)
            val swapped = info.orientation in 5..8
            val orientedWidth = if (swapped) info.height else info.width
            val orientedHeight = if (swapped) info.width else info.height
            val longest = max(orientedWidth, orientedHeight)
            val targetWidth = if (longest <= role.maximumEdge) orientedWidth
                else max(1, (orientedWidth.toLong() * role.maximumEdge / longest).toInt())
            val targetHeight = if (longest <= role.maximumEdge) orientedHeight
                else max(1, (orientedHeight.toLong() * role.maximumEdge / longest).toInt())
            // PNG EXIF is not consistently applied by ImageDecoder. Remove orientation before
            // decoding and apply the same explicit transform to every codec at bounded size.
            val neutral = ImageStructure.withoutExif(source, info)
            require(ImageStructure.inspect(neutral).orientation == 1)
            val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(neutral))) { decoder, header, _ ->
                require(header.size.width == info.width && header.size.height == info.height)
                require(!header.isAnimated)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.setTargetSize(if(swapped) targetHeight else targetWidth, if(swapped) targetWidth else targetHeight)
                decoder.setOnPartialImageListener { false }
            }
            val bitmap = try {
                orient(decoded, info.orientation)
            } catch (e: Throwable) {
                decoded.recycle()
                throw e
            }
            if(bitmap !== decoded) decoded.recycle()
            try {
                require(bitmap.width == targetWidth && bitmap.height == targetHeight &&
                    bitmap.config != Bitmap.Config.HARDWARE &&
                    bitmap.colorSpace == ColorSpace.get(ColorSpace.Named.SRGB))
                val jpeg = info.mediaType == "image/jpeg" ||
                    info.mediaType == "image/webp" && !info.lossless && !info.alpha && !bitmap.hasAlpha()
                require(!jpeg || !bitmap.hasAlpha())
                val output = LimitedOutput()
                require(bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 90, output))
                val bytes = ImageStructure.stripEncoderMetadata(output.bytes())
                val asset = ImageAsset(UUID.randomUUID(), if (jpeg) "image/jpeg" else "image/png",
                    bytes.size.toLong(), bitmap.width, bitmap.height, ImageStructure.digest(bytes))
                ImageStructure.validateCanonical(bytes, asset, role)
                return DraftImage(asset, bytes)
            } finally { bitmap.recycle() }
        } catch (_: OutOfMemoryError) {
            throw ImageException("The image could not be processed safely. Choose a smaller image.")
        } catch (e: ImageException) {
            throw e
        } catch (e: Exception) {
            throw ImageException(cause = e)
        }
    }

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        if(orientation == 1) return bitmap
        val values = when(orientation) {
            2 -> floatArrayOf(-1f,0f,0f,0f,1f,0f,0f,0f,1f)
            3 -> floatArrayOf(-1f,0f,0f,0f,-1f,0f,0f,0f,1f)
            4 -> floatArrayOf(1f,0f,0f,0f,-1f,0f,0f,0f,1f)
            5 -> floatArrayOf(0f,1f,0f,1f,0f,0f,0f,0f,1f)
            6 -> floatArrayOf(0f,-1f,0f,1f,0f,0f,0f,0f,1f)
            7 -> floatArrayOf(0f,-1f,0f,-1f,0f,0f,0f,0f,1f)
            8 -> floatArrayOf(0f,1f,0f,-1f,0f,0f,0f,0f,1f)
            else -> throw IllegalArgumentException()
        }
        return Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,Matrix().apply { setValues(values) },false)
    }

    private fun boundedRead(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, ImageStructure.SOURCE_LIMIT - output.size() + 1))
            if (count < 0) break
            require(count > 0 && output.size().toLong() + count <= ImageStructure.SOURCE_LIMIT)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private class LimitedOutput : OutputStream() {
        private val output = ByteArrayOutputStream()
        override fun write(value: Int) {
            require(output.size() < ImageStructure.RETAINED_LIMIT)
            output.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(output.size().toLong() + length <= ImageStructure.RETAINED_LIMIT)
            output.write(bytes, offset, length)
        }
        fun bytes(): ByteArray = output.toByteArray()
    }
}
