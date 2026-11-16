package com.mackseoul.vcardnative.platform.sharing

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.mackseoul.vcardnative.domain.sharing.ContactQr
import com.mackseoul.vcardnative.domain.sharing.QrGeometry

object ContactQrRenderer {
    fun draw(canvas: Canvas, qr: ContactQr, geometry: QrGeometry) {
        val paint = Paint().apply { isAntiAlias = false; color = Color.WHITE }
        canvas.drawRect(0f, 0f, geometry.panelPixels.toFloat(), geometry.panelPixels.toFloat(), paint)
        paint.color = Color.BLACK
        for (y in 0 until qr.side) for (x in 0 until qr.side) if (qr.isBlack(x, y)) {
            val left = geometry.symbolOrigin + x * geometry.modulePixels
            val top = geometry.symbolOrigin + y * geometry.modulePixels
            canvas.drawRect(
                left.toFloat(), top.toFloat(),
                (left + geometry.modulePixels).toFloat(), (top + geometry.modulePixels).toFloat(), paint,
            )
        }
    }
}
