package com.mackseoul.vcardnative.ui.screens.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mackseoul.vcardnative.R
import com.mackseoul.vcardnative.domain.sharing.ContactExportException
import com.mackseoul.vcardnative.domain.sharing.ContactQr
import com.mackseoul.vcardnative.domain.sharing.QrImageContext
import com.mackseoul.vcardnative.domain.sharing.QrImageExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal enum class QrImageOperation { Save, Share }

internal sealed interface QrImageStatus {
    data class Saved(val displayName: String) : QrImageStatus
    data object SaveFailed : QrImageStatus
    data object ShareOpened : QrImageStatus
    data class ShareFailed(val detail: String?) : QrImageStatus
}

/** Save Image and Share Image for the exact [qr] displayed directly above these controls. */
@Composable
internal fun QrImageActions(
    qr: ContactQr,
    context: QrImageContext,
    export: QrImageExport,
    enabled: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf<QrImageOperation?>(null) }
    // Results belong to the QR they were produced for; a regenerated QR starts without stale feedback.
    var status by remember(qr) { mutableStateOf<QrImageStatus?>(null) }

    fun run(operation: QrImageOperation, block: suspend () -> QrImageStatus) {
        if (working != null) return
        working = operation
        status = null
        scope.launch {
            try {
                status = block()
            } finally {
                working = null
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = {
                val target = qr
                run(QrImageOperation.Save) {
                    try {
                        QrImageStatus.Saved(export.save(target, context))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        QrImageStatus.SaveFailed
                    }
                }
            },
            enabled = enabled && working == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(if (working == QrImageOperation.Save) R.string.qr_image_saving else R.string.qr_image_save))
        }
        OutlinedButton(
            onClick = {
                val target = qr
                run(QrImageOperation.Share) {
                    try {
                        export.share(target)
                        QrImageStatus.ShareOpened
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: ContactExportException) {
                        QrImageStatus.ShareFailed(error.message)
                    } catch (_: Exception) {
                        QrImageStatus.ShareFailed(null)
                    }
                }
            },
            enabled = enabled && working == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(if (working == QrImageOperation.Share) R.string.qr_image_preparing_share else R.string.qr_image_share))
        }
        status?.let { current ->
            val failed = current is QrImageStatus.SaveFailed || current is QrImageStatus.ShareFailed
            val message = when (current) {
                is QrImageStatus.Saved -> stringResource(R.string.qr_image_saved, current.displayName)
                QrImageStatus.SaveFailed -> stringResource(R.string.qr_image_save_failed)
                QrImageStatus.ShareOpened -> stringResource(R.string.qr_image_share_opened)
                is QrImageStatus.ShareFailed -> current.detail?.let { stringResource(R.string.qr_image_share_failed_detail, it) }
                    ?: stringResource(R.string.qr_image_share_failed)
            }
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
