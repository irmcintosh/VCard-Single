package com.mackseoul.vcardnative.platform.sharing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.test.InstrumentationTestCase
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.mackseoul.vcardnative.data.sharing.ContactQrEncoder
import com.mackseoul.vcardnative.domain.profile.*
import com.mackseoul.vcardnative.domain.sharing.ContactExport
import java.util.UUID
import java.io.File

@Suppress("DEPRECATION")
class ContactQrRendererTest : InstrumentationTestCase() {
    fun testRealAndroidRasterHasOpaqueBlackWhiteIntegerModulesQuietZoneAndDecodes() {
        for (name in listOf("Saved Example", "Zoë 中文 😀 مرحبا")) {
            val draft = ProfileDraft.empty().copy(values = mapOf(ProfileField.FullName to name))
            val normalized = requireNotNull(ProfileValidator.validate(draft).normalized)
            val export = ContactExport.from(Profile(UUID.randomUUID(), normalized.values, normalized.visibility))
            val qr = ContactQrEncoder.encode(export)
            for (density in listOf(1.0, 2.625, 3.0)) {
                val geometry = qr.geometry((320 * density).toInt(), density)
                val bitmap = Bitmap.createBitmap(geometry.panelPixels, geometry.panelPixels, Bitmap.Config.ARGB_8888)
                try {
                    ContactQrRenderer.draw(Canvas(bitmap), qr, geometry)
                    val pixels = IntArray(geometry.panelPixels * geometry.panelPixels)
                    bitmap.getPixels(pixels, 0, geometry.panelPixels, 0, 0, geometry.panelPixels, geometry.panelPixels)
                    for (y in 0 until geometry.panelPixels) for (x in 0 until geometry.panelPixels) {
                        val dx = x - geometry.symbolOrigin
                        val dy = y - geometry.symbolOrigin
                        val inSymbol = dx >= 0 && dy >= 0 &&
                            dx < qr.side * geometry.modulePixels && dy < qr.side * geometry.modulePixels
                        val expected = if (inSymbol && qr.isBlack(dx / geometry.modulePixels, dy / geometry.modulePixels))
                            Color.BLACK else Color.WHITE
                        assertEquals(expected, pixels[y * geometry.panelPixels + x])
                    }
                    val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(
                        RGBLuminanceSource(geometry.panelPixels, geometry.panelPixels, pixels),
                    )))
                    assertEquals(String(export.bytes, Charsets.UTF_8), decoded.text)
                } finally { bitmap.recycle() }
            }

        }
    }

    fun testIndependentDecoderFixturesUseExactVcardBytesAndProductionRaster() {
        val root = File(instrumentation.targetContext.cacheDir, "qr-decoder-verification")
        check(root.isDirectory || root.mkdir())
        for ((id, name) in listOf("ascii" to "Saved Example", "unicode" to "Zoë 中文 😀 مرحبا")) {
            val draft = ProfileDraft.empty().copy(
                values = mapOf(ProfileField.FullName to name, ProfileField.Biography to "Line, one;\nNext\\line"),
                visibility = ProfileDraft.empty().visibility + (ProfileField.Biography to Visibility(false, true)),
            )
            val normalized = requireNotNull(ProfileValidator.validate(draft).normalized)
            val export = ContactExport.from(Profile(UUID.randomUUID(), normalized.values, normalized.visibility))
            val qr = ContactQrEncoder.encode(export)
            val geometry = qr.geometry(840, 2.625)
            val bitmap = Bitmap.createBitmap(geometry.panelPixels, geometry.panelPixels, Bitmap.Config.ARGB_8888)
            try {
                ContactQrRenderer.draw(Canvas(bitmap), qr, geometry)
                root.resolve("$id.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                root.resolve("$id.vcf").writeBytes(export.bytes)
            } finally { bitmap.recycle() }
        }
    }
}
