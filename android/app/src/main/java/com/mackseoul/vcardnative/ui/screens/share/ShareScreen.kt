package com.mackseoul.vcardnative.ui.screens.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.mackseoul.vcardnative.R
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.sharing.ContactExport
import com.mackseoul.vcardnative.domain.sharing.ContactQr
import com.mackseoul.vcardnative.domain.sharing.QrImageContext
import com.mackseoul.vcardnative.domain.sharing.QrImageExport
import com.mackseoul.vcardnative.data.sharing.ContactQrEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mackseoul.vcardnative.ui.profile.Body
import com.mackseoul.vcardnative.ui.profile.ErrorMessage
import com.mackseoul.vcardnative.ui.profile.ProfileDestination
import com.mackseoul.vcardnative.ui.profile.ProfileScreenState
import com.mackseoul.vcardnative.ui.profile.ProfileViewModel
import com.mackseoul.vcardnative.ui.profile.ScreenFrame
import com.mackseoul.vcardnative.ui.profile.fieldLabel
import com.mackseoul.vcardnative.ui.screens.card.MyCardNavigation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: ProfileScreenState, model: ProfileViewModel, shareContact: (ContactExport) -> Unit) {
    ScreenFrame(
        R.string.share,
        bottom = {
            MyCardNavigation(
                selectedLabel = R.string.share,
                onHome = {},
                onMyCard = { model.select(ProfileDestination.MyCard) },
                onProfile = { model.select(ProfileDestination.Profile) },
                enabled = !state.busy && state.contactReview == null,
            )
        },
    ) {
        state.saved?.data?.value(ProfileField.FullName)?.let { name ->
            Text(name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Body(R.string.share_offline_description)
        }
        when {
            state.unavailable != null -> {
                ErrorMessage(state.unavailable)
                Body(R.string.recovery_unavailable)
            }
            state.qrLoading -> Body(R.string.qr_preparing)
            state.contactQr != null -> Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
            ) {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    ContactQrPanel(state.contactQr)
                }
            }
            state.qrFailure != null -> ErrorMessage(stringResource(qrFailureMessage(state.qrFailure)))
            state.saved == null -> Body(R.string.export_no_profile)
        }
        if (state.saved != null && state.unavailable == null) {
            if (state.contactQr != null) {
                OutlinedButton(
                    onClick = model::showShareQr,
                    enabled = !state.busy && state.contactReview == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.open_contact_qr)) }
            }
            OutlinedButton(
                onClick = model::reviewContact,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.contact_preview_action)) }
            Button(
                onClick = { model.shareContact(shareContact) },
                enabled = !state.busy && state.contactExport != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(if (state.exporting) R.string.preparing_contact_share else R.string.share_contact_file)) }
        }
        state.contactError?.let { ErrorMessage(it) }
        state.contactCleanupError?.let { ErrorMessage(it) }
        if (state.contactError != null || state.contactCleanupError != null || state.unavailable != null) {
            TextButton(onClick = model::reload, enabled = !state.busy) { Text(stringResource(R.string.retry_load)) }
        }
        if (state.handoffNotice) Body(R.string.contact_handoff_notice)
    }

    state.contactReview?.let { preview ->
        val busy by rememberUpdatedState(state.busy)
        ModalBottomSheet(
            onDismissRequest = model::dismissContactReview,
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { !busy },
            ),
            properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !state.busy),
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            val view = LocalView.current
            val window = (view.parent as? DialogWindowProvider)?.window
            val lightBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
            SideEffect {
                window?.let {
                    WindowCompat.getInsetsController(it, view).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    stringResource(R.string.contact_preview),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Body(R.string.contact_preview_description)
                state.saved?.data?.value(ProfileField.FullName)?.let {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_person),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                it,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                ContactRows(preview.export, excludeName = true)
                state.contactError?.let { ErrorMessage(it) }
                OutlinedButton(
                    onClick = model::editExportSelections,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.edit_sharing_settings)) }
                Button(
                    onClick = model::dismissContactReview,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.done)) }
            }
        }
    }
}

@Composable
fun QrPresentationScreen(
    state: ProfileScreenState,
    model: ProfileViewModel,
    qrImageExport: QrImageExport? = null,
    onClose: () -> Unit,
) {
    ScreenFrame(
        R.string.contact_qr_presentation,
        back = onClose,
        backEnabled = !state.busy,
        bottom = {
            Box(
                Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                ).fillMaxWidth().padding(16.dp),
            ) {
                Button(
                    onClick = onClose,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.done)) }
            }
        },
    ) {
        state.saved?.data?.value(ProfileField.FullName)?.let {
            Text(it, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        }
        Body(R.string.share_offline_description)
        when {
            state.qrLoading -> Body(R.string.qr_preparing)
            state.contactQr != null -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White,
                ) {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        ContactQrPanel(state.contactQr)
                    }
                }
                qrImageExport?.let {
                    QrImageActions(state.contactQr, QrImageContext.PrimaryContact, it, enabled = !state.busy)
                }
            }
            state.qrFailure != null -> ErrorMessage(stringResource(qrFailureMessage(state.qrFailure)))
            state.contactError != null -> ErrorMessage(state.contactError)
        }
    }
}

@Composable
fun OnlineCardQrScreen(state: ProfileScreenState, qrImageExport: QrImageExport? = null, onClose: () -> Unit) {
    ScreenFrame(
        R.string.online_card_qr,
        back = onClose,
        backEnabled = !state.busy,
        bottom = {
            Box(Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            ).fillMaxWidth().padding(16.dp)) {
                Button(onClick = onClose, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.done))
                }
            }
        },
    ) {
        Body(R.string.online_card_qr_instruction)
        val url = state.onlineCardUrl
        if (url == null) {
            ErrorMessage(stringResource(R.string.online_card_url_unavailable))
        } else {
            key(url) {
                val result by produceState<Result<ContactQr>?>(initialValue = null, key1 = url) {
                    value = withContext(Dispatchers.Default) {
                        runCatching { ContactQrEncoder.encodeHostedProfileUrl(url) }
                    }
                }
                val qr = result?.getOrNull()
                when {
                    result == null -> Body(R.string.online_card_qr_preparing)
                    qr != null -> {
                        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                            color = Color.White) {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                ContactQrPanel(qr, description = stringResource(R.string.show_online_card_qr),
                                    geometryError = stringResource(R.string.online_card_qr_geometry_error))
                            }
                        }
                        qrImageExport?.let {
                            QrImageActions(qr, QrImageContext.HostedProfile, it, enabled = !state.busy)
                        }
                    }
                    else -> ErrorMessage(stringResource(R.string.online_card_qr_failed))
                }
            }
            SelectionContainer { Text(url, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun ContactRows(export: ContactExport, excludeName: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        export.rows.filterNot { excludeName && ProfileField.FullName in it.fields }.forEach { row ->
            val fields = row.fields.ifEmpty { listOf(ProfileField.OtherProfessional) }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(contactFieldIcon(fields)),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f).padding(top = 1.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            row.label ?: fields.map { stringResource(fieldLabel(it)) }.joinToString(" · "),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SelectionContainer {
                            Text(
                                row.value,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                softWrap = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun contactFieldIcon(fields: List<ProfileField>): Int = when {
    ProfileField.Phone in fields -> R.drawable.ic_call
    ProfileField.Email in fields -> R.drawable.ic_email
    fields.any {
        it == ProfileField.Website || it == ProfileField.LinkedIn ||
            it == ProfileField.GitHub || it == ProfileField.OtherProfessional
    } -> R.drawable.ic_external
    ProfileField.Location in fields -> R.drawable.ic_location
    ProfileField.Biography in fields -> R.drawable.ic_message
    else -> R.drawable.ic_card
}
