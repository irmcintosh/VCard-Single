package com.mackseoul.vcardnative.data.sharing

import com.mackseoul.vcardnative.domain.profile.Profile
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.sharing.ContactExport
import com.mackseoul.vcardnative.domain.sharing.ContactExportException
import com.mackseoul.vcardnative.domain.sharing.ContactQr
import com.mackseoul.vcardnative.domain.sharing.ContactQrException
import com.mackseoul.vcardnative.domain.sharing.QrFailure
import com.mackseoul.vcardnative.domain.sharing.QuickShareContactExport

sealed interface QuickShareQrResult {
    data class ExportUnavailable(val error: ContactExportException) : QuickShareQrResult

    data class QrAvailable(
        val export: ContactExport,
        val qr: ContactQr,
    ) : QuickShareQrResult

    data class QrUnavailable(
        val export: ContactExport,
        val failure: QrFailure,
    ) : QuickShareQrResult
}

object QuickShareQrGenerator {
    fun generate(profile: Profile, selection: Set<ProfileField>): QuickShareQrResult =
        generate(profile, selection) { export -> ContactQrEncoder.encode(export) }

    internal fun generate(
        profile: Profile,
        selection: Set<ProfileField>,
        encode: (ContactExport) -> ContactQr,
    ): QuickShareQrResult {
        val export = try {
            QuickShareContactExport.from(profile, selection)
        } catch (error: ContactExportException) {
            return QuickShareQrResult.ExportUnavailable(error)
        }

        return try {
            QuickShareQrResult.QrAvailable(export, encode(export))
        } catch (error: ContactQrException) {
            QuickShareQrResult.QrUnavailable(export, error.failure)
        }
    }
}