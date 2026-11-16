package com.mackseoul.vcardnative.ui.profile

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.material3.Tab
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.content.ActivityNotFoundException
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mackseoul.vcardnative.R
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.profile.PhoneNumbers
import com.mackseoul.vcardnative.domain.profile.ProfileDraft
import com.mackseoul.vcardnative.domain.profile.ProfileValidator
import com.mackseoul.vcardnative.domain.profile.legacySocialFields
import com.mackseoul.vcardnative.domain.profile.SocialProfile
import com.mackseoul.vcardnative.domain.profile.SocialEntryField
import com.mackseoul.vcardnative.domain.profile.structuredNameFields
import com.mackseoul.vcardnative.domain.profile.ImageRole
import com.mackseoul.vcardnative.ui.screens.card.MyCardNavigation
import com.mackseoul.vcardnative.ui.screens.card.MyCardScreen
import com.mackseoul.vcardnative.ui.screens.share.HomeScreen
import com.mackseoul.vcardnative.ui.screens.share.QrPresentationScreen
import com.mackseoul.vcardnative.ui.screens.share.OnlineCardQrScreen
import com.mackseoul.vcardnative.ui.screens.share.QuickShareQrScreen
import com.mackseoul.vcardnative.ui.screens.share.QuickShareReviewScreen
import com.mackseoul.vcardnative.ui.screens.share.QuickShareSelectInformationScreen
import com.mackseoul.vcardnative.domain.sharing.ContactExport
import com.mackseoul.vcardnative.domain.sharing.PublicKeyExport
import android.net.Uri

@Composable
fun ProfileApplication(
    state: ProfileScreenState,
    model: ProfileViewModel,
    exit: () -> Unit,
    shareContact: (ContactExport) -> Unit,
    sharePublicKey: (PublicKeyExport) -> Unit,
    writePublicKey: (Uri, PublicKeyExport) -> Unit,
    onContactAction: (com.mackseoul.vcardnative.ui.screens.card.CardContactAction) -> Unit,
    qrImageExport: com.mackseoul.vcardnative.domain.sharing.QrImageExport? = null,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refreshPublicKeyStatus()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val keyDestination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PublicKeyExport.MIME_TYPE)) {
        model.publicKeySaveResult(it, writePublicKey)
    }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        model.keyImportResult(it)
    }
    val pendingQuickShareHandoff = state.quickShareSession?.pendingContactHandoff
    val quickShareHandoffFailure = stringResource(R.string.quick_share_handoff_failed)
    LaunchedEffect(state.destination, pendingQuickShareHandoff?.id) {
        if (state.destination == ProfileDestination.QuickShareReview && pendingQuickShareHandoff != null) {
            val handoff = model.takeQuickShareContactHandoff() ?: return@LaunchedEffect
            try {
                shareContact(handoff.export)
            } catch (_: Exception) {
                model.quickShareContactHandoffFailed(quickShareHandoffFailure)
            }
        }
    }
    LaunchedEffect(
        state.pendingShortcutQr, state.loading, state.busy, state.destination, state.dirty, state.confirmDiscard,
    ) {
        model.resolvePendingShortcutQr()
    }
    LaunchedEffect(state.pendingWidgetLaunch, state.loading, state.busy, state.destination,
        state.dirty, state.backupReview, state.contactReview) {
        model.resolvePendingWidgetLaunch()
    }
    BackHandler { model.requestBack(exit) }
    when {
        state.loading -> ScreenFrame(R.string.profile) {
            CircularProgressIndicator()
            Body(R.string.loading_profile)
        }
        state.pendingOperation != null -> ScreenFrame(
            R.string.profile, back = exit,
        ) {
            Heading(if (state.pendingOperation == "delete") R.string.deletion_pending else R.string.operation_pending)
            ErrorMessage(requireNotNull(state.unavailable))
            Body(if (state.pendingOperation == "delete") R.string.deletion_pending_help else R.string.operation_pending_help)
            Button(onClick = model::reload, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.retry_operation))
            }
            TextButton(onClick = exit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.leave_recovery))
            }
        }
        state.unavailable != null && state.destination != ProfileDestination.Editor &&
            state.destination != ProfileDestination.Home && state.destination != ProfileDestination.QrPresentation &&
            state.destination != ProfileDestination.Backup &&
            state.destination !in setOf(ProfileDestination.Welcome, ProfileDestination.SetupDetails,
                ProfileDestination.SetupContact, ProfileDestination.SetupReview) -> ScreenFrame(
            R.string.profile,
            bottom = {
                MyCardNavigation(
                    R.string.profile,
                    onHome = { model.select(ProfileDestination.Home) },
                    onMyCard = { model.select(ProfileDestination.MyCard) },
                    onProfile = { model.select(ProfileDestination.Profile) },
                )
            },
        ) {
            Heading(R.string.saved_profile_unavailable)
            ErrorMessage(state.unavailable)
            Body(R.string.recovery_unavailable)
            Button(onClick = model::reload, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.retry_verification))
            }
            if (state.recoveryToken != null) {
                Button(onClick = model::openBackup, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.restore_application_backup))
                }
            }
            TextButton(onClick = exit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.leave_recovery))
            }
        }
        state.destination == ProfileDestination.Home -> HomeScreen(state, model, shareContact)
        state.destination == ProfileDestination.QrPresentation -> QrPresentationScreen(state, model, qrImageExport) {
            model.closeQrPresentation(exit)
        }
        state.destination == ProfileDestination.OnlineCardQr -> OnlineCardQrScreen(state, qrImageExport) {
            model.requestBack(exit)
        }
        state.destination == ProfileDestination.QuickShareSelectInformation -> QuickShareSelectInformationScreen(
            state = state,
            model = model,
            onEditProfile = {
                model.cancelQuickShare()
                model.select(ProfileDestination.Editor)
            },
        )
        state.destination == ProfileDestination.QuickShareReview -> QuickShareReviewScreen(state, model)
        state.destination == ProfileDestination.QuickShareQr -> QuickShareQrScreen(state, model, qrImageExport)
        state.destination == ProfileDestination.MyCard -> MyCardScreen(
            state = requireNotNull(state.saved).data.toMyCard(state.keyStatus),
            portrait = state.visibleCardImages().portrait,
            cover = state.visibleCardImages().cover,
            appearance = requireNotNull(state.saved).data.appearance,
            onCreateProfile = { model.select(ProfileDestination.Editor) },
            onHome = { model.select(ProfileDestination.Home) },
            onProfile = { model.select(ProfileDestination.Profile) },
            onQuickShare = model::startQuickShare,
            onShowOnlineQr = model::showOnlineCardQr,
            onContactAction = onContactAction,
            onDownloadPublicKey = {
                if (model.startPublicKeySave()) {
                    try { keyDestination.launch(PublicKeyExport.FILE_NAME) }
                    catch (_: ActivityNotFoundException) { model.keyPickerFailed() }
                    catch (_: SecurityException) { model.keyPickerFailed() }
                }
            },
            onSharePublicKey = { model.sharePublicKey(sharePublicKey) },
            keyError = state.keyError ?: state.backupError ?: state.quickShareError,
            keyNotice = state.keyNotice ?: state.backupNotice, keyBusy = state.busy,
        )
        state.destination == ProfileDestination.Profile -> ProfileHub(state, model)
        state.destination == ProfileDestination.Appearance -> AppearanceScreen(state, model, exit)
        state.destination == ProfileDestination.Backup -> BackupScreen(state, model, exit)
        state.destination == ProfileDestination.Welcome -> ProfileSetupWelcome(state, model)
        state.destination == ProfileDestination.SetupDetails -> ProfileSetupDetails(state, model)
        state.destination == ProfileDestination.SetupContact -> ProfileSetupContact(state, model)
        state.destination == ProfileDestination.SetupReview -> ProfileSetupReview(state, model)
        state.destination == ProfileDestination.Visibility -> VisibilitySettingsScreen(state, model)
        state.destination == ProfileDestination.Security -> SecuritySettingsScreen(state, model) {
            model.startKeyImport()
            try { keyPicker.launch(arrayOf("*/*")) }
            catch (_: ActivityNotFoundException) { model.keyPickerFailed() }
            catch (_: SecurityException) { model.keyPickerFailed() }
        }
        state.destination == ProfileDestination.HostedProfile -> HostedProfileScreen(state, model)
        state.destination == ProfileDestination.ProfileImages -> ProfileImagesScreen(state, model)
        else -> ProfileEditor(state, model, exit) {
            model.startKeyImport()
            try { keyPicker.launch(arrayOf("*/*")) }
            catch (_: ActivityNotFoundException) { model.keyPickerFailed() }
            catch (_: SecurityException) { model.keyPickerFailed() }
        }
    }
    if (state.confirmDiscard) {
        AlertDialog(
            onDismissRequest = model::keepEditing,
            title = {
                Text(
                    stringResource(
                        if (state.destination == ProfileDestination.Appearance) R.string.discard_appearance_title
                        else R.string.unsaved_changes,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (state.destination == ProfileDestination.Appearance) R.string.discard_appearance_prompt
                        else R.string.discard_prompt,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(onClick = model::keepEditing) { Text(stringResource(R.string.keep_editing)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { model.leaveEditor(exit) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.discard_changes)) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(28.dp),
        )
    }
    if (state.confirmDeleteProfile) {
        AlertDialog(
            onDismissRequest = model::cancelDeleteProfile,
            title = { Text(stringResource(R.string.delete_profile_title)) },
            text = { Text(stringResource(R.string.delete_profile_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = model::deleteProfile,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.delete_profile)) }
            },
            dismissButton = {
                TextButton(onClick = model::cancelDeleteProfile) { Text(stringResource(R.string.cancel)) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(28.dp),
        )
    }
}

@Composable
private fun ProfileHub(state: ProfileScreenState, model: ProfileViewModel) {
    ScreenFrame(
        R.string.profile,
        bottom = {
            MyCardNavigation(
                selectedLabel = R.string.profile,
                onHome = { model.select(ProfileDestination.Home) },
                onMyCard = { model.select(ProfileDestination.MyCard) },
                onProfile = {},
            )
        },
    ) {
        val saved = state.saved
        val profile = saved?.data?.profile
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                Modifier.padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val image = state.savedImages[ImageRole.Portrait]
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = stringResource(R.string.profile_image_preview),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(36.dp)),
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(72.dp),
                        shape = RoundedCornerShape(36.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painterResource(R.drawable.ic_person), contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        profile?.values?.get(ProfileField.FullName).orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    profile?.values?.get(ProfileField.ProfessionalTitle)?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge)
                    }
                    profile?.values?.get(ProfileField.Organization)?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Button(
            onClick = { model.select(ProfileDestination.Editor) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text(stringResource(R.string.edit_profile)) }
        Heading(R.string.personalization)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ManagementRow(R.string.appearance, R.string.appearance_scope, enabled = true) {
                model.select(ProfileDestination.Appearance)
            }
            ManagementRow(R.string.visibility_and_sharing, R.string.visibility_scope, enabled = true) {
                model.openEditorSettings(ProfileDestination.Visibility)
            }
        }
        Heading(R.string.data_security)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ManagementRow(R.string.backup_restore, R.string.backup_scope, enabled = true, onClick = model::openBackup)
            ManagementRow(R.string.security_and_encryption, R.string.public_key_settings_scope, enabled = true) {
                model.openEditorSettings(ProfileDestination.Security)
            }
        }
        state.deleteError?.let { ErrorMessage(it) }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ManagementRow(
                title = R.string.delete_profile,
                description = R.string.delete_profile_scope,
                enabled = !state.busy && state.unavailable == null,
                destructive = true,
                onClick = model::requestDeleteProfile,
            )
        }
    }
}

@Composable
private fun ManagementRow(
    @StringRes title: Int,
    @StringRes description: Int,
    enabled: Boolean = false,
    destructive: Boolean = false,
    onClick: () -> Unit = {},
) {
    Surface(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(if (title == R.string.edit_profile) R.drawable.ic_person else R.drawable.ic_card),
                contentDescription = null,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(title), style = MaterialTheme.typography.titleMedium,
                    color = when {
                        destructive -> MaterialTheme.colorScheme.error
                        enabled -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    stringResource(description), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (enabled) Icon(painterResource(R.drawable.ic_chevron), contentDescription = null)
        }
    }
}

@Composable
private fun ProfileSetupWelcome(state: ProfileScreenState, model: ProfileViewModel) {
    ScreenFrame(R.string.welcome_to_vcard) {
        Heading(R.string.welcome_headline)
        Body(R.string.welcome_message)
        state.backupError?.let { ErrorMessage(it) }
        Button(
            onClick = model::beginProfileSetup,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text(stringResource(R.string.create_my_card)) }
        OutlinedButton(
            onClick = model::openBackup,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.restore_existing_card)) }
    }
}

@Composable
private fun ProfileSetupDetails(state: ProfileScreenState, model: ProfileViewModel) {
    ScreenFrame(
        R.string.basic_information,
        back = model::previousProfileSetup,
        backEnabled = !state.busy,
        bottom = {
            SetupAction(state.busy, R.string.continue_setup, model::advanceProfileSetup)
        },
    ) {
        Body(R.string.basic_information_help)
        FullNamePreview(state)
        Body(R.string.structured_names_help)
        structuredNameFields.forEach { ProfileSetupTextField(it, state, model) }
        ProfileSetupTextField(ProfileField.ProfessionalTitle, state, model)
        ProfileSetupTextField(ProfileField.Organization, state, model)
    }
}

@Composable
private fun ProfileSetupContact(state: ProfileScreenState, model: ProfileViewModel) {
    ScreenFrame(
        R.string.contact_information,
        back = model::previousProfileSetup,
        backEnabled = !state.busy,
        bottom = {
            SetupAction(state.busy, R.string.continue_setup, model::advanceProfileSetup)
        },
    ) {
        Body(R.string.contact_information_help)
        PhoneFieldEditor(state, model)
        ProfileSetupTextField(ProfileField.Email, state, model)
        ProfileSetupTextField(ProfileField.Website, state, model)
        TextButton(
            onClick = model::skipProfileContact,
            enabled = !state.busy,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.skip_contact_information)) }
    }
}

@Composable
private fun ProfileSetupReview(state: ProfileScreenState, model: ProfileViewModel) {
    val draft = requireNotNull(state.draft)
    ScreenFrame(
        R.string.review_your_card,
        back = model::previousProfileSetup,
        backEnabled = !state.busy,
        bottom = {
            SetupAction(state.busy, R.string.create_my_card, model::save)
        },
    ) {
        Body(R.string.review_card_help)
        state.saveError?.let {
            ErrorMessage(it)
            TextButton(onClick = model::save, enabled = !state.busy) {
                Text(stringResource(R.string.try_saving_again))
            }
        }
        SetupSummaryGroup(R.string.basic_information) {
            SetupSummaryValue(R.string.full_name, state.fullNamePreview)
            structuredNameFields.forEach { field ->
                draft.values[field]?.takeIf(String::isNotBlank)?.let { SetupSummaryValue(fieldLabel(field), it) }
            }
            draft.values[ProfileField.ProfessionalTitle]?.takeIf(String::isNotBlank)?.let {
                SetupSummaryValue(R.string.professional_title, it)
            }
            draft.values[ProfileField.Organization]?.takeIf(String::isNotBlank)?.let {
                SetupSummaryValue(R.string.organization, it)
            }
        }
        SetupSummaryGroup(R.string.contact_information) {
            val contact = listOf(ProfileField.Phone, ProfileField.Email, ProfileField.Website)
                .mapNotNull { field -> draft.values[field]?.takeIf(String::isNotBlank)?.let { field to it } }
            if (contact.isEmpty()) Body(R.string.no_contact_information)
            contact.forEach { (field, value) -> SetupSummaryValue(fieldLabel(field), value) }
        }
        OutlinedButton(
            onClick = model::previousProfileSetup,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.edit_contact_information)) }
    }
}

@Composable
private fun FullNamePreview(state: ProfileScreenState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.full_name), style = MaterialTheme.typography.labelLarge)
            SelectionContainer {
                Text(state.fullNamePreview, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun SetupSummaryGroup(@StringRes title: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Heading(title)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    }
}

@Composable
private fun SetupSummaryValue(@StringRes label: Int, value: String) {
    SetupSummaryValue(stringResource(label), value)
}

@Composable
private fun SetupSummaryValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SetupAction(busy: Boolean, @StringRes label: Int, action: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Box(
            Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            ).imePadding().fillMaxWidth().padding(16.dp),
        ) {
            Button(
                onClick = action,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(stringResource(label), style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun ProfileSetupTextField(field: ProfileField, state: ProfileScreenState, model: ProfileViewModel) {
    val focus = remember(field) { FocusRequester() }
    val bringIntoView = remember(field) { BringIntoViewRequester() }
    LaunchedEffect(state.validationAttempt) {
        if (state.errors.containsKey(field)) {
            focus.requestFocus()
            bringIntoView.bringIntoView()
        }
    }
    OutlinedTextField(
        value = state.draft?.values?.get(field).orEmpty(),
        onValueChange = { model.edit(field, it) },
        modifier = Modifier.fillMaxWidth().focusRequester(focus).bringIntoViewRequester(bringIntoView),
        enabled = !state.busy,
        isError = state.errors.containsKey(field),
        supportingText = state.errors[field]?.let { message -> { ErrorMessage(message) } },
        label = {
            Text(
                stringResource(fieldLabel(field)) +
                    if (field == ProfileField.FullName) " *" else "",
            )
        },
        textStyle = MaterialTheme.typography.bodyLarge,
        singleLine = field != ProfileField.Biography,
        keyboardOptions = KeyboardOptions(keyboardType = when (field) {
            ProfileField.Phone -> KeyboardType.Phone
            ProfileField.Email -> KeyboardType.Email
            ProfileField.Website -> KeyboardType.Uri
            else -> KeyboardType.Text
        }),
        shape = RoundedCornerShape(12.dp),
    )
}

@Composable
private fun PhoneFieldEditor(state: ProfileScreenState, model: ProfileViewModel) {
    val draft = requireNotNull(state.draft)
    val focus = remember { FocusRequester() }
    val bringIntoView = remember { BringIntoViewRequester() }
    val error = state.errors[ProfileField.Phone]
    LaunchedEffect(state.validationAttempt) {
        if (error != null) {
            focus.requestFocus()
            bringIntoView.bringIntoView()
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView),
    ) {
        val stacked = LocalDensity.current.fontScale >= 1.35f || maxWidth < 460.dp
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PhoneCountrySelector(
                    region = draft.phoneRegion,
                    enabled = !state.busy && state.unavailable == null,
                    onSelect = model::selectPhoneRegion,
                    modifier = Modifier.fillMaxWidth(),
                )
                PhoneNumberInput(
                    value = draft.values[ProfileField.Phone].orEmpty(),
                    error = error,
                    focus = focus,
                    enabled = !state.busy && state.unavailable == null,
                    modifier = Modifier.fillMaxWidth(),
                    onChange = { model.edit(ProfileField.Phone, it) },
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                PhoneCountrySelector(
                    region = draft.phoneRegion,
                    enabled = !state.busy && state.unavailable == null,
                    onSelect = model::selectPhoneRegion,
                    modifier = Modifier.weight(1.1f),
                )
                PhoneNumberInput(
                    value = draft.values[ProfileField.Phone].orEmpty(),
                    error = error,
                    focus = focus,
                    enabled = !state.busy && state.unavailable == null,
                    modifier = Modifier.weight(1.9f),
                    onChange = { model.edit(ProfileField.Phone, it) },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PhoneCountrySelector(
    region: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val countries = remember(locale) { PhoneNumbers.regions(locale) }
    val current = remember(region, locale) { PhoneNumbers.region(region, locale) }
    val description = current?.let {
        stringResource(R.string.phone_country_selector_description, it.countryName, it.callingCode)
    } ?: stringResource(R.string.phone_country_region)
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var anchorWidth by remember { mutableStateOf(0.dp) }
    val results = remember(query, countries) { PhoneNumbers.filterRegions(countries, query) }
    val open = expanded && enabled
    val shape = RoundedCornerShape(12.dp)
    fun close() {
        expanded = false
        query = ""
        keyboard?.hide()
    }
    Box(modifier.onSizeChanged { anchorWidth = with(density) { it.width.toDp() } }) {
        OutlinedTextField(
            value = current?.let { "+${it.callingCode} ${it.isoRegion}" } ?: region,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.phone_country_region), maxLines = 1) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = shape,
            modifier = Modifier.fillMaxWidth().focusProperties { canFocus = false }.clearAndSetSemantics {},
        )
        Box(
            Modifier.matchParentSize().clip(shape)
                .clickable(enabled = enabled, role = Role.DropdownList) {
                    if (expanded) {
                        close()
                    } else {
                        keyboard?.hide()
                        focusManager.clearFocus()
                        query = ""
                        expanded = true
                    }
                }
                .semantics { contentDescription = description },
        )
        // Focusable DropdownMenu rather than ExposedDropdownMenu so the search field can take IME input.
        DropdownMenu(
            expanded = open,
            onDismissRequest = { close() },
            modifier = Modifier.width(maxOf(anchorWidth, 280.dp)),
        ) {
            val listScroll = rememberScrollState()
            LaunchedEffect(query) { listScroll.scrollTo(0) }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.phone_country_search), maxLines = 1) },
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = shape,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
            if (results.isEmpty()) {
                Text(
                    stringResource(R.string.phone_country_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                // Bounded non-lazy list: LazyColumn breaks DropdownMenu's intrinsic-width measurement.
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(listScroll)) {
                    results.forEach { country ->
                        DropdownMenuItem(
                            text = { Text(country.selectorLabel) },
                            modifier = Modifier.semantics { selected = country.isoRegion == region },
                            onClick = {
                                close()
                                onSelect(country.isoRegion)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneNumberInput(
    value: String,
    error: String?,
    focus: FocusRequester,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.focusRequester(focus),
        enabled = enabled,
        isError = error != null,
        supportingText = error?.let { message -> { ErrorMessage(message) } },
        label = { Text(stringResource(R.string.phone)) },
        textStyle = MaterialTheme.typography.bodyLarge,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        shape = RoundedCornerShape(12.dp),
    )
}

@Composable
private fun VisibilitySettingsScreen(state: ProfileScreenState, model: ProfileViewModel) {
    val draft = requireNotNull(state.draft)
    ScreenFrame(
        R.string.visibility_and_sharing,
        back = model::returnToEditor,
        backEnabled = !state.busy,
    ) {
        Body(R.string.visibility_draft_help)
        (if (state.contactExportSettingsFirst) listOf(true, false) else listOf(false, true)).forEach { contactExport ->
            if (contactExport) {
                Heading(R.string.contact_export_category)
                Body(R.string.contact_export_visibility_help)
                ProfileField.entries.filter { it !in legacySocialFields && draft.visibility[it]?.includeInContactExport != null }.forEach { field ->
                    VisibilitySetting(
                        field = field,
                        checked = requireNotNull(draft.visibility[field]?.includeInContactExport),
                        label = R.string.include_in_export,
                        locked = field == ProfileField.FullName,
                        enabled = !state.busy,
                    ) { checked ->
                        model.privacy(
                            field,
                            requireNotNull(draft.visibility[field]).copy(includeInContactExport = checked),
                        )
                    }
                }
                PhoneCountryCodeSetting(
                    draft = draft,
                    enabled = !state.busy,
                    onChange = model::includeCountryCodeWhenSharing,
                )
            } else {
                Heading(R.string.my_card_category)
                Body(R.string.my_card_visibility_help)
                ProfileField.entries.filter { it !in legacySocialFields && draft.visibility[it]?.showOnCard != null }.forEach { field ->
                    VisibilitySetting(
                        field = field,
                        checked = requireNotNull(draft.visibility[field]?.showOnCard),
                        label = R.string.show_on_card,
                        locked = field == ProfileField.FullName,
                        enabled = !state.busy,
                    ) { checked ->
                        model.privacy(
                            field,
                            requireNotNull(draft.visibility[field]).copy(showOnCard = checked),
                        )
                    }
                }
            }
        }
        state.saveError?.let { ErrorMessage(it) }
        Button(
            onClick = model::returnToEditor,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.return_to_editor)) }
    }
}

@Composable
private fun VisibilitySetting(
    field: ProfileField,
    checked: Boolean,
    @StringRes label: Int,
    locked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val description = stringResource(R.string.visibility_setting_description, stringResource(fieldLabel(field)), stringResource(label))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .toggleable(checked, enabled = enabled && !locked, role = Role.Switch, onValueChange = onChange)
                    .semantics { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(fieldLabel(field)), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked, onCheckedChange = null, enabled = enabled && !locked)
            }
            if (locked) Body(R.string.name_export_required)
        }
    }
}

@Composable
private fun PhoneCountryCodeSetting(
    draft: ProfileDraft,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val label = stringResource(R.string.include_country_code_when_sharing)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .toggleable(
                        draft.includeCountryCodeWhenSharing,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = onChange,
                    )
                    .semantics { contentDescription = label },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(
                    checked = draft.includeCountryCodeWhenSharing,
                    onCheckedChange = null,
                    enabled = enabled,
                )
            }
            Text(
                stringResource(R.string.phone_country_code_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val savedPhone = draft.values[ProfileField.Phone].orEmpty()
            if (savedPhone.isBlank()) {
                Text(
                    stringResource(R.string.phone_export_preview_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val preview = PhoneNumbers.exportValue(
                    savedPhone,
                    draft.phoneRegion,
                    draft.includeCountryCodeWhenSharing,
                    draft.phoneNumberE164,
                )
                Text(
                    stringResource(R.string.phone_export_preview, preview.value),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (!preview.recognized) {
                    Text(
                        stringResource(R.string.phone_export_legacy_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SecuritySettingsScreen(
    state: ProfileScreenState,
    model: ProfileViewModel,
    onImport: () -> Unit,
) {
    val draft = requireNotNull(state.draft)
    ScreenFrame(
        R.string.security_and_encryption,
        back = model::returnToEditor,
        backEnabled = !state.busy,
    ) {
        Body(R.string.security_public_key_scope)
        PublicKeyEditor(
            asset = draft.publicKey,
            status = state.draftKeyStatus,
            processing = state.processingKey,
            error = state.keyError ?: state.errors[ProfileField.PublicKey],
            enabled = !state.busy && state.unavailable == null,
            onImport = onImport,
            onRemove = model::removePublicKey,
        )
        state.keyNotice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        state.saveError?.let { ErrorMessage(it) }
        Button(
            onClick = model::returnToEditor,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.return_to_editor)) }
    }
}

@Composable
private fun HostedProfileScreen(state: ProfileScreenState, model: ProfileViewModel) {
    val firstError = state.errors.keys.firstOrNull()
    ScreenFrame(
        R.string.hosted_profile,
        back = model::returnToEditor,
        backEnabled = !state.busy,
    ) {
        Body(R.string.hosted_guidance)
        ProfileFieldEditor(ProfileField.HostedProfileUrl, state, model, firstError)
        state.saveError?.let { ErrorMessage(it) }
        Button(
            onClick = model::returnToEditor,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.return_to_editor)) }
    }
}

@Composable
private fun ProfileImagesScreen(state: ProfileScreenState, model: ProfileViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        model.imageSelectionResult(it)
    }
    ScreenFrame(
        R.string.profile_images,
        back = model::returnToEditor,
        backEnabled = !state.busy,
    ) {
        Body(R.string.image_selection_help)
        if (state.processingImage) Body(R.string.processing_image)
        state.imageError?.let { ErrorMessage(it) }
        listOf(ProfileField.ProfileImage, ProfileField.CoverImage).forEach { field ->
            val role = if (field == ProfileField.ProfileImage) ImageRole.Portrait else ImageRole.Cover
            ProfileImageEditor(
                field = field,
                role = role,
                image = state.draftImages[role],
                enabled = !state.busy && state.unavailable == null,
                onChange = {
                    model.startImageSelection(role)
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onRemove = { model.removeImage(role) },
            )
        }
        state.saveError?.let { ErrorMessage(it) }
        Button(
            onClick = model::returnToEditor,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.return_to_editor)) }
    }
}

@Composable
private fun ProfileEditor(
    state: ProfileScreenState,
    model: ProfileViewModel,
    exit: () -> Unit,
    onImportPublicKey: () -> Unit,
) {
    val draft = requireNotNull(state.draft)
    val firstError = state.errors.keys.firstOrNull()
    ScreenFrame(
        title = R.string.edit_profile,
        back = { model.requestBack(exit) },
        backEnabled = !state.busy,
        bottom = { SaveProfileAction(state, model) },
    ) {
        Body(R.string.edit_guidance)
        if (state.dirty) Body(R.string.unsaved_work)
        if (state.reviewNormalization) {
            Text(
                stringResource(R.string.normalization_review),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        state.saveError?.let { ErrorMessage(it) }
        state.unavailable?.let {
            ErrorMessage(it)
            Body(R.string.recovery_unavailable)
        }
        if (LocalDensity.current.fontScale >= 1.5f) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ProfileEditorTab.entries.forEach { tab ->
                    ProfileEditorTabButton(tab, state, model)
                }
            }
        } else {
            TabRow(selectedTabIndex = state.editorTab.ordinal) {
                ProfileEditorTab.entries.forEach { tab ->
                    ProfileEditorTabButton(tab, state, model)
                }
            }
        }
        when (state.editorTab) {
            ProfileEditorTab.Details -> {
                FullNamePreview(state)
                Body(R.string.structured_names_help)
                (structuredNameFields + listOf(
                    ProfileField.ProfessionalTitle, ProfileField.Organization,
                    ProfileField.Biography, ProfileField.Location,
                )).forEach { ProfileFieldEditor(it, state, model, firstError) }
            }
            ProfileEditorTab.Contact -> {
                PhoneFieldEditor(state, model)
                listOf(
                ProfileField.Email, ProfileField.Website,
                ).forEach { ProfileFieldEditor(it, state, model, firstError) }
                SocialProfileEditor(state, model)
            }
            ProfileEditorTab.Advanced -> {
                Body(R.string.visibility_draft_help)
                ManagementRow(R.string.visibility_and_sharing, R.string.visibility_scope, enabled = !state.busy) {
                    model.openEditorSettingsFromEditor(ProfileDestination.Visibility)
                }
                ManagementRow(R.string.security_and_encryption, R.string.public_key_settings_scope, enabled = !state.busy) {
                    model.openEditorSettingsFromEditor(ProfileDestination.Security)
                }
                ManagementRow(R.string.hosted_profile, R.string.hosted_guidance, enabled = !state.busy) {
                    model.openEditorSettingsFromEditor(ProfileDestination.HostedProfile)
                }
                ManagementRow(R.string.profile_images, R.string.profile_images_scope, enabled = !state.busy) {
                    model.openEditorSettingsFromEditor(ProfileDestination.ProfileImages)
                }
            }
        }
    }
}

@Composable
private fun SocialProfileEditor(state: ProfileScreenState, model: ProfileViewModel) {
    val draft = requireNotNull(state.draft)
    val enabled = !state.busy && state.unavailable == null
    Heading(R.string.social_profiles)
    draft.socialProfiles.forEachIndexed { index, entry ->
        key(entry.id) {
            val fieldErrors = if (state.validationAttempt > 0) ProfileValidator.socialProfileFieldErrors(entry)
            else emptyMap()
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.social_profile_number, index + 1),
                    style = MaterialTheme.typography.titleSmall)
                SocialPlatformDropdown(entry, state, model, enabled, fieldErrors[SocialEntryField.Platform])
                SocialEntryInput(R.string.social_label, entry.label, enabled,
                    error = fieldErrors[SocialEntryField.Label]) {
                    model.editSocialProfile(entry.copy(label = it))
                }
                SocialEntryInput(R.string.social_url, entry.url, enabled, uri = true,
                    error = fieldErrors[SocialEntryField.Url]) {
                    model.editSocialProfile(entry.copy(url = it))
                }
                SocialEntrySwitch(R.string.show_on_card, entry.showOnCard, enabled) {
                    model.editSocialProfile(entry.copy(showOnCard = it))
                }
                SocialEntrySwitch(R.string.include_in_export, entry.includeInContactExport, enabled) {
                    model.editSocialProfile(entry.copy(includeInContactExport = it))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { model.moveSocialProfile(entry.id, -1) },
                        enabled = enabled && index > 0) { Text(stringResource(R.string.move_up)) }
                    TextButton(onClick = { model.moveSocialProfile(entry.id, 1) },
                        enabled = enabled && index < draft.socialProfiles.lastIndex) {
                        Text(stringResource(R.string.move_down))
                    }
                    TextButton(onClick = { model.removeSocialProfile(entry.id) }, enabled = enabled) {
                        Text(stringResource(R.string.remove_social))
                    }
                }
            }
        }
    }
    OutlinedButton(onClick = model::addSocialProfile, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.add_social))
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SocialPlatformDropdown(
    entry: SocialProfile, state: ProfileScreenState, model: ProfileViewModel, enabled: Boolean,
    error: String? = null,
) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val selection = state.socialPlatformSelections[entry.id] ?: SocialPlatformSelection.fromSaved(entry.platform)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(
            expanded = expanded && enabled,
            onExpandedChange = { next ->
                if (enabled) {
                    if (next) keyboard?.hide()
                    expanded = next
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = selection.option.displayName,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                singleLine = true,
                label = { Text(stringResource(R.string.social_platform)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = enabled),
            )
            ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                SocialPlatformOption.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.displayName, maxLines = 1,
                        style = MaterialTheme.typography.bodyLarge) },
                        modifier = Modifier.semantics { selected = selection.option == option },
                        onClick = {
                            model.selectSocialPlatform(entry.id, option)
                            expanded = false
                        })
                }
            }
        }
        if (selection.option == SocialPlatformOption.Other) {
            SocialEntryInput(R.string.custom_platform_name, selection.customName, enabled, error = error) {
                model.editCustomSocialPlatform(entry.id, it)
            }
        } else {
            error?.let { ErrorMessage(it) }
        }
    }
}

@Composable
private fun SocialEntryInput(
    label: Int, value: String, enabled: Boolean, uri: Boolean = false, error: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(value = value, onValueChange = onChange, enabled = enabled,
        isError = error != null,
        supportingText = error?.let { message -> { ErrorMessage(message) } },
        modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(label)) },
        keyboardOptions = KeyboardOptions(keyboardType = if (uri) KeyboardType.Uri else KeyboardType.Text),
        shape = RoundedCornerShape(12.dp))
}

@Composable
private fun SocialEntrySwitch(label: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ProfileEditorTabButton(
    tab: ProfileEditorTab,
    state: ProfileScreenState,
    model: ProfileViewModel,
) {
    Tab(
        selected = state.editorTab == tab,
        onClick = { model.selectEditorTab(tab) },
        enabled = !state.busy,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (state.editorTab == tab) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                },
                shape = RoundedCornerShape(12.dp),
            ),
        selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        text = {
            Text(
                stringResource(when (tab) {
                    ProfileEditorTab.Details -> R.string.details_tab
                    ProfileEditorTab.Contact -> R.string.contact_tab
                    ProfileEditorTab.Advanced -> R.string.advanced_tab
                }),
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
            )
        },
    )
}

@Composable
private fun ProfileFieldEditor(
    field: ProfileField,
    state: ProfileScreenState,
    model: ProfileViewModel,
    firstError: ProfileField?,
) {
    val focus = remember(field) { FocusRequester() }
    val bringIntoView = remember(field) { BringIntoViewRequester() }
    LaunchedEffect(state.validationAttempt) {
        if (firstError == field) {
            focus.requestFocus()
            bringIntoView.bringIntoView()
        }
    }
    Column(
        Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.draft?.values?.get(field).orEmpty(),
            onValueChange = { model.edit(field, it) },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            enabled = !state.busy && state.unavailable == null,
            isError = state.errors.containsKey(field),
            supportingText = state.errors[field]?.let { message -> { ErrorMessage(message) } },
            label = { Text(stringResource(fieldLabel(field))) },
            textStyle = MaterialTheme.typography.bodyLarge,
            singleLine = false,
            minLines = if (field == ProfileField.Biography) 3 else 1,
            keyboardOptions = KeyboardOptions(keyboardType = when (field) {
                ProfileField.Phone -> KeyboardType.Phone
                ProfileField.Email -> KeyboardType.Email
                ProfileField.Website, ProfileField.LinkedIn, ProfileField.GitHub,
                ProfileField.OtherProfessional, ProfileField.HostedProfileUrl -> KeyboardType.Uri
                else -> KeyboardType.Text
            }),
            shape = RoundedCornerShape(12.dp),
        )
    }
}

@Composable
private fun ProfileImageEditor(
    field: ProfileField,
    role: ImageRole,
    image: androidx.compose.ui.graphics.ImageBitmap?,
    enabled: Boolean,
    onChange: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(fieldLabel(field)), style = MaterialTheme.typography.titleSmall)
            ProfileImagePreview(role, image)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onChange,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(stringResource(if (role == ImageRole.Portrait) R.string.change_portrait else R.string.change_cover))
                }
                if (image != null) {
                    TextButton(
                        onClick = onRemove,
                        enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(if (role == ImageRole.Portrait) R.string.remove_portrait else R.string.remove_cover))
                    }
                }
            }
        }
    }
}

@Composable
private fun PublicKeyEditor(
    asset: com.mackseoul.vcardnative.domain.profile.PublicKeyAsset?,
    status: com.mackseoul.vcardnative.domain.keys.PublicKeyStatus?,
    processing: Boolean,
    error: String?,
    enabled: Boolean,
    onImport: () -> Unit,
    onRemove: () -> Unit,
) {
    Text(stringResource(R.string.public_key), style = MaterialTheme.typography.titleSmall)
    Body(R.string.public_key_import_help)
    if (processing) Body(R.string.public_key_processing)
    error?.let { ErrorMessage(it) }
    asset?.let { PublicKeyMetadata(it, status) } ?: Body(R.string.public_key_absent)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onImport,
            enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.import_public_key)) }
        if (asset != null) {
            TextButton(
                onClick = onRemove,
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.remove_public_key)) }
        }
    }
}

@Composable
private fun SaveProfileAction(state: ProfileScreenState, model: ProfileViewModel) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Box(
            Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            ).imePadding().fillMaxWidth().padding(16.dp),
        ) {
            Button(
                onClick = model::save,
                enabled = !state.busy && state.unavailable == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(if (state.saving) R.string.saving_profile else R.string.save_profile),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
internal fun ScreenFrame(
    @StringRes title: Int,
    back: (() -> Unit)? = null,
    backEnabled: Boolean = true,
    bottom: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    ).fillMaxWidth().heightIn(min = 64.dp).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (back != null) TextButton(onClick = back, enabled = backEnabled) {
                        Text(stringResource(R.string.up))
                    }
                    Text(
                        stringResource(title), style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                }
            }
        },
        bottomBar = bottom,
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) { content() }
        }
    }
}

@Composable
internal fun Heading(@StringRes title: Int) {
    Text(
        stringResource(title), style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
internal fun Body(@StringRes text: Int) {
    Text(
        stringResource(text), style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun ErrorMessage(message: String) {
    Text(
        message, color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@StringRes
internal fun fieldLabel(field: ProfileField): Int = when (field) {
    ProfileField.FullName -> R.string.full_name
    ProfileField.FirstName -> R.string.first_name
    ProfileField.MiddleName -> R.string.middle_name
    ProfileField.LastName -> R.string.last_name
    ProfileField.ProfessionalTitle -> R.string.professional_title
    ProfileField.Organization -> R.string.organization
    ProfileField.Biography -> R.string.biography
    ProfileField.Location -> R.string.location
    ProfileField.Phone -> R.string.phone
    ProfileField.Email -> R.string.email
    ProfileField.Website -> R.string.website
    ProfileField.LinkedIn -> R.string.linkedin
    ProfileField.GitHub -> R.string.github
    ProfileField.OtherProfessional -> R.string.other_professional
    ProfileField.HostedProfileUrl -> R.string.hosted_profile_url
    ProfileField.ProfileImage -> R.string.profile_image
    ProfileField.CoverImage -> R.string.cover_image
    ProfileField.PublicKey -> R.string.public_key
}
