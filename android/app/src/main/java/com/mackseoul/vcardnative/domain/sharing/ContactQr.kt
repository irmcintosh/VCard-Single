package com.mackseoul.vcardnative.domain.sharing

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

enum class QrFailure {
    PayloadLimit, EncodingCapacity, Encoding, Geometry
}

class ContactQrException(val failure: QrFailure) : Exception()

class ContactQr(val version: Int, modules: BooleanArray) {
    val side = 17 + 4 * version
    private val modules = modules.copyOf()
    init {
        require(version in 1..40 && modules.size == side * side)
    }

    fun isBlack(x: Int, y: Int): Boolean {
        require(x in 0 until side && y in 0 until side)
        return modules[y * side + x]
    }

    fun geometry(availablePixels: Int, density: Double): QrGeometry {
        require(availablePixels >= 0 && density.isFinite() && density > 0)
        val maximum = min(availablePixels, floor(320 * density).toInt())
        val preferred = min(maximum, floor(280 * density).toInt())
        val outerPadding = ceil(16 * density).toInt()
        val minimumModule = max(3, ceil(2 * density).toInt())
        val fullSide = side + 8
        fun fits(panel: Int) = (panel - 2 * outerPadding) / fullSide >= minimumModule
        val panel = when {
            fits(preferred) -> preferred
            fits(maximum) -> maximum
            else -> throw ContactQrException(QrFailure.Geometry)
        }
        val module = (panel - 2 * outerPadding) / fullSide
        val start = outerPadding + (panel - 2 * outerPadding - fullSide * module) / 2
        return QrGeometry(panel, module, start + 4 * module)
    }

    companion object {
        const val MAX_PAYLOAD_BYTES = 4096
    }
}

data class QrGeometry(val panelPixels: Int, val modulePixels: Int, val symbolOrigin: Int)
