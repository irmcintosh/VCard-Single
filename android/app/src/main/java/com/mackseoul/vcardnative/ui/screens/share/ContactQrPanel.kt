package com.mackseoul.vcardnative.ui.screens.share

import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.mackseoul.vcardnative.R
import com.mackseoul.vcardnative.domain.sharing.ContactQr
import com.mackseoul.vcardnative.domain.sharing.ContactQrException
import com.mackseoul.vcardnative.domain.sharing.QrFailure
import com.mackseoul.vcardnative.ui.profile.ErrorMessage
import com.mackseoul.vcardnative.platform.sharing.ContactQrRenderer

@Composable
internal fun ContactQrPanel(
    qr: ContactQr,
    description: String = stringResource(R.string.contact_qr_description),
    geometryError: String = stringResource(qrFailureMessage(QrFailure.Geometry)),
) {
    val density = LocalDensity.current.density.toDouble()
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val available = constraints.maxWidth
        val geometry = remember(qr, available, density) {
            try { qr.geometry(available, density) } catch (_: ContactQrException) { null }
        }
        if (geometry == null) {
            ErrorMessage(geometryError)
        } else {
            val image = remember(qr, geometry) {
                createBitmap(geometry.panelPixels, geometry.panelPixels).also {
                    ContactQrRenderer.draw(Canvas(it), qr, geometry)
                }.asImageBitmap()
            }
            Image(
                bitmap = image, contentDescription = description,
                modifier = Modifier.size((geometry.panelPixels / density).toFloat().dp),
                contentScale = ContentScale.None, filterQuality = FilterQuality.None,
            )
        }
    }
}

internal fun qrFailureMessage(failure: QrFailure): Int = when (failure) {
    QrFailure.PayloadLimit -> R.string.qr_payload_limit
    QrFailure.EncodingCapacity -> R.string.qr_encoding_capacity
    QrFailure.Encoding -> R.string.qr_encoding_failed
    QrFailure.Geometry -> R.string.qr_geometry_limit
}
