package com.mackseoul.vcardnative.ui.profile

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.mackseoul.vcardnative.R
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.profile.Theme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupScreen(state: ProfileScreenState, model: ProfileViewModel, exit: () -> Unit) {
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        model.backupDestinationResult(it)
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        model.restoreSelectionResult(it)
    }
    LaunchedEffect(state.backupOperation) {
        if (state.backupOperation == BackupOperation.ReadyForDestination) {
            val filename = model.consumeBackupDestination()
            if (filename != null) {
                try { create.launch(filename) }
                catch (_: ActivityNotFoundException) { model.backupPickerFailed() }
                catch (_: SecurityException) { model.backupPickerFailed() }
            }
        }
    }
    ScreenFrame(
        R.string.backup_restore, back = { model.requestBack(exit) }, backEnabled = !state.busy,
    ) {
        Body(R.string.backup_scope)
        Body(R.string.backup_privacy_warning)
        Heading(R.string.create_backup_heading)
        Button(
            onClick = model::createBackup,
            enabled = !state.busy && state.backupReview == null && state.saved != null && state.unavailable == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.create_full_backup)) }
        if (state.saved == null && state.unavailable == null) Body(R.string.backup_no_saved_profile)
        Heading(R.string.restore_backup_heading)
        Body(R.string.restore_scope)
        OutlinedButton(
            onClick = {
                if (model.startRestorePicker()) {
                    try { restore.launch(arrayOf("*/*")) }
                    catch (_: ActivityNotFoundException) { model.backupPickerFailed() }
                    catch (_: SecurityException) { model.backupPickerFailed() }
                }
            },
            enabled = !state.busy && state.backupReview == null && state.pendingOperation == null &&
                (state.unavailable == null || state.recoveryToken != null),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.restore_application_backup)) }
        state.backupOperation?.let {
            Body(when (it) {
                BackupOperation.Preparing -> R.string.preparing_backup
                BackupOperation.ReadyForDestination, BackupOperation.ChoosingDestination -> R.string.choosing_backup_destination
                BackupOperation.Writing -> R.string.writing_backup
                BackupOperation.ChoosingRestore -> R.string.choosing_restore
                BackupOperation.Validating -> R.string.validating_backup
                BackupOperation.Committing -> R.string.restoring_backup
            })
        }
        state.backupNotice?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        state.backupError?.let { ErrorMessage(it) }
        state.unavailable?.let {
            ErrorMessage(it)
            Body(R.string.recovery_unavailable)
            TextButton(onClick = model::reload, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.retry_verification))
            }
            TextButton(onClick = { model.requestBack(exit) }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.leave_recovery))
            }
        }
    }
    state.backupReview?.let { review ->
        val candidate = review.candidate
        val profile = candidate.data.profile
        val view = LocalView.current
        ModalBottomSheet(
            onDismissRequest = model::dismissRestoreReview,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            val sheetView = LocalView.current
            val window = (sheetView.parent as? DialogWindowProvider)?.window
            val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
            SideEffect {
                window?.let { WindowCompat.getInsetsController(it, sheetView).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                } }
            }
            Column(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Heading(R.string.review_restore)
                Body(R.string.restore_replacement_warning)
                if (review.recoveryToken != null) {
                    Body(R.string.recovery_confirmation)
                } else {
                    Text(stringResource(R.string.restore_local_profile,
                        review.replacingProfileName ?: stringResource(R.string.absent)),
                        style = MaterialTheme.typography.bodyLarge)
                }
                Text(stringResource(R.string.restore_profile_label), style = MaterialTheme.typography.bodyLarge)
                Text(requireNotNull(candidate.data.value(ProfileField.FullName)), style = MaterialTheme.typography.bodyLarge)
                Body(R.string.restore_identity_warning)
                Text(stringResource(R.string.restore_asset_summary,
                    stringResource(if (profile.profileImage != null) R.string.present else R.string.absent),
                    stringResource(if (profile.coverImage != null) R.string.present else R.string.absent),
                    stringResource(if (profile.publicKey != null) R.string.present else R.string.absent)),
                    style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.restore_appearance_summary,
                    stringResource(if (candidate.data.appearance.theme == Theme.dark) R.string.theme_dark else R.string.theme_light),
                    candidate.data.appearance.accent.name),
                    style = MaterialTheme.typography.bodyLarge)
                Body(R.string.backup_privacy_warning)
                profile.publicKey?.let { asset ->
                    PublicKeyMetadata(asset, review.keyStatus)
                }
                state.backupError?.let { ErrorMessage(it) }
                Button(
                    onClick = model::confirmRestore, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(when {
                    review.recoveryToken != null -> R.string.confirm_backup_recovery
                    review.expected == null -> R.string.restore_profile
                    else -> R.string.replace_and_restore
                })) }
                TextButton(onClick = {
                    view.clearFocus()
                    model.dismissRestoreReview()
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cancel_restore))
                }
            }
        }
    }
}
