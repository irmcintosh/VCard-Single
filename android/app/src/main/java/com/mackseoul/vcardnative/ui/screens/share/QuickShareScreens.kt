package com.mackseoul.vcardnative.ui.screens.share

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.mackseoul.vcardnative.R
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.sharing.QuickShareFieldChoices
import com.mackseoul.vcardnative.domain.sharing.QuickSharePreview
import com.mackseoul.vcardnative.domain.sharing.QuickShareSelection
import com.mackseoul.vcardnative.domain.sharing.QrImageContext
import com.mackseoul.vcardnative.domain.sharing.QrImageExport
import com.mackseoul.vcardnative.data.sharing.QuickShareQrResult
import com.mackseoul.vcardnative.ui.profile.Body
import com.mackseoul.vcardnative.ui.profile.ErrorMessage
import com.mackseoul.vcardnative.ui.profile.ProfileDestination
import com.mackseoul.vcardnative.ui.profile.ProfileScreenState
import com.mackseoul.vcardnative.ui.profile.ProfileViewModel
import com.mackseoul.vcardnative.ui.profile.ScreenFrame
import com.mackseoul.vcardnative.ui.profile.fieldLabel

@Composable
internal fun QuickShareSelectInformationScreen(
    state: ProfileScreenState,
    model: ProfileViewModel,
    onEditProfile: () -> Unit,
) {
    val session = state.quickShareSession
    ScreenFrame(
        R.string.quick_share_select_information,
        back = { model.requestBack {} },
        backEnabled = !state.busy,
    ) {
        if (session == null) {
            ErrorMessage(state.quickShareError ?: stringResource(R.string.saved_profile_unavailable))
            OutlinedButton(
                onClick = { model.select(ProfileDestination.MyCard) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.cancel)) }
            return@ScreenFrame
        }

        session.fullName?.let { fullName ->
            QuickShareFullName(fullName, alwaysIncluded = true)
            Body(R.string.quick_share_full_name_automatic)
        }
        session.error?.let { ErrorMessage(it) }
        state.quickShareError?.let { ErrorMessage(it) }

        val savedValues = session.savedProfile.data.profile.values
        if (session.availableFields.isEmpty()) Body(R.string.quick_share_empty_state)
        if (!session.fullName.isNullOrBlank()) {
            if (session.availableFields.isNotEmpty()) {
                QuickShareActionPair {
                    OutlinedButton(
                        onClick = model::selectAllQuickShareFields,
                        enabled = !state.busy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.quick_share_select_all)) }
                    OutlinedButton(
                        onClick = model::clearQuickShareFields,
                        enabled = !state.busy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.quick_share_clear_all)) }
                }
            }
            val choices = remember(savedValues, session.selectedFields) {
                QuickShareFieldChoices.from(savedValues, session.selectedFields)
            }
            QuickShareSelectionSummary(
                selected = choices.count { it.selected },
                total = QuickShareFieldChoices.optionalFieldCount,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { choice ->
                    QuickShareFieldOption(
                        field = choice.field,
                        value = choice.value,
                        checked = choice.selected,
                        enabled = !state.busy,
                        onToggle = { model.toggleQuickShareField(choice.field) },
                    )
                }
            }
            if (session.availableFields.isNotEmpty() &&
                !QuickShareSelection.canContinue(savedValues, session.selectedFields)
            ) {
                Body(R.string.quick_share_selection_required)
            }
        }
        if (session.availableFields.isEmpty() || session.fullName.isNullOrBlank()) {
            OutlinedButton(
                onClick = onEditProfile,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.edit_profile)) }
        }

        QuickShareActionPair {
            OutlinedButton(
                onClick = model::cancelQuickShare,
                enabled = !state.busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.cancel)) }
            Button(
                onClick = model::continueQuickShareToReview,
                enabled = session.availableFields.isNotEmpty() &&
                    session.fullName?.isNotBlank() == true &&
                    QuickShareSelection.canContinue(savedValues, session.selectedFields) &&
                    !state.busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.quick_share_continue)) }
        }
    }
}

@Composable
internal fun QuickShareReviewScreen(state: ProfileScreenState, model: ProfileViewModel) {
    val session = state.quickShareSession
    ScreenFrame(
        R.string.quick_share_review_share,
        back = model::backToQuickShareSelection,
        backEnabled = !state.busy,
    ) {
        if (session == null) {
            ErrorMessage(state.quickShareError ?: stringResource(R.string.saved_profile_unavailable))
            OutlinedButton(
                onClick = { model.select(ProfileDestination.MyCard) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.done)) }
            return@ScreenFrame
        }

        val preview = session.preview
        if (session.fullName.isNullOrBlank()) {
            ErrorMessage(stringResource(R.string.quick_share_full_name_missing))
        } else {
            QuickShareFullName(
                (preview as? QuickSharePreview.Ready)?.fullName ?: session.fullName,
                alwaysIncluded = true,
            )
        }
        when (preview) {
            is QuickSharePreview.Ready -> {
                Body(R.string.quick_share_preview_exact)
                preview.rows.forEach { row ->
                    QuickShareValue(label = stringResource(fieldLabel(row.field)), value = row.value)
                }
            }
            is QuickSharePreview.Unavailable -> QuickSharePreviewWarning(preview.selectedFields)
            QuickSharePreview.MissingFullName, QuickSharePreview.NoSelection -> Unit
        }
        session.error?.let { ErrorMessage(it) }
        session.selectionPersistenceError?.let { ErrorMessage(it) }
        (session.qrResult as? QuickShareQrResult.QrUnavailable)?.let {
            ErrorMessage(stringResource(R.string.quick_share_qr_unavailable))
        }
        (session.qrResult as? QuickShareQrResult.ExportUnavailable)?.let {
            ErrorMessage(it.error.message ?: stringResource(R.string.contact_action_failed))
        }

        QuickShareActionPair {
            OutlinedButton(
                onClick = model::doneQuickShare,
                enabled = !state.busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.done)) }
            OutlinedButton(
                onClick = model::initiateQuickShareContactShare,
                enabled = !state.busy && session.pendingContactHandoff == null &&
                    session.fullName?.isNotBlank() == true &&
                    QuickShareSelection.canContinue(session.savedProfile.data.profile.values, session.selectedFields),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.quick_share_share_contact)) }
        }
        Button(
            onClick = model::generateQuickShareQr,
            enabled = !state.busy && session.fullName?.isNotBlank() == true &&
                QuickShareSelection.canContinue(session.savedProfile.data.profile.values, session.selectedFields),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.quick_share_generate_qr)) }
    }
}

@Composable
internal fun QuickShareQrScreen(
    state: ProfileScreenState,
    model: ProfileViewModel,
    qrImageExport: QrImageExport? = null,
) {
    val session = state.quickShareSession
    ScreenFrame(
        R.string.quick_share_qr,
        back = model::backToQuickShareReview,
        backEnabled = !state.busy,
        bottom = {
            Button(
                onClick = model::doneQuickShare,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.done)) }
        },
    ) {
        when (val result = session?.qrResult) {
            is QuickShareQrResult.QrAvailable -> {
                session.fullName?.let { QuickShareFullName(it) }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    ContactQrPanel(result.qr)
                }
                qrImageExport?.let {
                    QrImageActions(result.qr, QrImageContext.CustomShare, it, enabled = !state.busy)
                }
            }
            is QuickShareQrResult.QrUnavailable -> {
                ErrorMessage(stringResource(R.string.quick_share_qr_unavailable))
                TextButton(onClick = model::backToQuickShareReview, enabled = !state.busy) {
                    Text(stringResource(R.string.quick_share_share_contact))
                }
            }
            is QuickShareQrResult.ExportUnavailable ->
                ErrorMessage(result.error.message ?: stringResource(R.string.contact_action_failed))
            null -> ErrorMessage(
                session?.error ?: state.quickShareError ?: stringResource(R.string.quick_share_qr_unavailable),
            )
        }
    }
}

@Composable
private fun QuickShareFieldOption(
    field: ProfileField,
    value: String?,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val label = stringResource(fieldLabel(field))
    val available = value != null
    val description = if (available) {
        stringResource(R.string.quick_share_field_accessibility, label, value.orEmpty())
    } else {
        stringResource(R.string.quick_share_field_unavailable_accessibility, label)
    }
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (available) colors.surface else colors.surfaceVariant,
        border = if (checked) BorderStroke(2.dp, colors.primary) else BorderStroke(1.dp, colors.outlineVariant),
    ) {
        // The whole row is one toggle; it carries the label/value description, checked state and disabled state.
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(
                    value = checked,
                    enabled = enabled && available,
                    role = Role.Checkbox,
                    onValueChange = { onToggle() },
                )
                .semantics { contentDescription = description }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (available) colors.onSurface else colors.onSurfaceVariant,
                )
                Text(
                    value ?: stringResource(R.string.quick_share_not_in_profile),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (available) colors.onSurface else colors.onSurfaceVariant,
                    fontStyle = if (available) null else FontStyle.Italic,
                )
            }
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled && available)
        }
    }
}

@Composable
private fun QuickShareSelectionSummary(selected: Int, total: Int) {
    Text(
        stringResource(R.string.quick_share_selected_count, selected, total),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun QuickSharePreviewWarning(selectedFields: List<ProfileField>) {
    val fields = selectedFields.map { stringResource(fieldLabel(it)) }.joinToString(", ")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.quick_share_preview_unavailable_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.quick_share_preview_unavailable, fields),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * Places two actions side by side when both labels fit on one line at equal widths;
 * otherwise (narrow screens, large font scales) stacks them full width in source order.
 */
@Composable
private fun QuickShareActionPair(content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val spacing = 12.dp.roundToPx()
        val width = constraints.maxWidth
        val half = ((width - spacing) / 2).coerceAtLeast(0)
        val sideBySide = measurables.size == 2 &&
            measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= half }
        if (sideBySide) {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(half)) }
            val height = placeables.maxOf { it.height }
            layout(width, height) {
                placeables[0].placeRelative(0, (height - placeables[0].height) / 2)
                placeables[1].placeRelative(width - half, (height - placeables[1].height) / 2)
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }
            val height = placeables.sumOf { it.height } + spacing * (placeables.size - 1).coerceAtLeast(0)
            layout(width, height) {
                var y = 0
                placeables.forEach {
                    it.placeRelative(0, y)
                    y += it.height + spacing
                }
            }
        }
    }
}

@Composable
private fun QuickShareFullName(value: String, alwaysIncluded: Boolean = false) {
    val label = stringResource(R.string.full_name)
    val description = stringResource(R.string.quick_share_full_name_accessibility, label, value)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        if (alwaysIncluded) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp)
                    .clearAndSetSemantics { contentDescription = description },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    stringResource(R.string.quick_share_always_included),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

@Composable
private fun QuickShareValue(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}