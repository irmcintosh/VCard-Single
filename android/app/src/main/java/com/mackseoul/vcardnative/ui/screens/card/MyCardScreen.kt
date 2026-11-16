package com.mackseoul.vcardnative.ui.screens.card

import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mackseoul.vcardnative.R
import com.mackseoul.vcardnative.domain.profile.Appearance
import com.mackseoul.vcardnative.ui.profile.ErrorMessage

@Composable
fun MyCardScreen(
    state: MyCardUiState,
    modifier: Modifier = Modifier,
    cover: ImageBitmap? = null,
    portrait: ImageBitmap? = null,
    appearance: Appearance = Appearance(),
    onCreateProfile: () -> Unit,
    onHome: () -> Unit,
    onProfile: () -> Unit,
    onQuickShare: () -> Unit,
    onContactAction: (CardContactAction) -> Unit,
    onShowOnlineQr: (() -> Unit)? = null,
    onDownloadPublicKey: () -> Unit = {},
    onSharePublicKey: () -> Unit = {},
    keyError: String? = null,
    keyNotice: String? = null,
    keyBusy: Boolean = false,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { MyCardAppBar() },
        bottomBar = {
            MyCardNavigation(
                selectedLabel = R.string.my_card,
                onHome = onHome,
                onMyCard = {},
                onProfile = onProfile,
                enabled = !keyBusy,
            )
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            val gutter = if (maxWidth < 360.dp) 16.dp else 20.dp
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = gutter, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                val profile = state.profile
                if (profile == null) {
                    FirstRunGuidance(onCreateProfile)
                } else {
                    ProfileCard(
                        appearance.cardStyle, appearance.minimalItems,
                        profile, cover, portrait,
                        onDownloadPublicKey = onDownloadPublicKey,
                        onSharePublicKey = onSharePublicKey,
                        keyBusy = keyBusy,
                        onContactAction = onContactAction,
                        onShowOnlineQr = onShowOnlineQr,
                    )
                }
                keyError?.let { ErrorMessage(it) }
                keyNotice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                QuickShareActions(
                    hasProfile = profile != null,
                    onQuickShare = onQuickShare,
                )
            }
        }
    }
}

@Composable
private fun MyCardAppBar() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            ).fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                stringResource(R.string.my_card),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}

@Composable
private fun FirstRunGuidance(onCreateProfile: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                NeutralPortrait()
                Text(
                    stringResource(R.string.no_profile_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.no_profile_body), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Button(
            onClick = onCreateProfile,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            ActionLabel(R.string.create_profile, R.drawable.ic_person)
        }
    }
}

@Composable
fun MyCardPreview(profile: MyCardProfile?, cover: ImageBitmap?, portrait: ImageBitmap?) {
    if (profile == null) {
        NeutralPortrait()
    } else {
        ClassicCard(profile, cover, portrait)
    }
}

@Composable
private fun QuickShareActions(hasProfile: Boolean, onQuickShare: () -> Unit) {
    Button(
        onClick = onQuickShare, enabled = hasProfile,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { ActionLabel(R.string.quick_share, R.drawable.ic_card) }
}

@Composable
private fun SupportingText(@StringRes text: Int) {
    Text(
        stringResource(text), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun MyCardNavigation(
    selectedLabel: Int = R.string.my_card,
    onHome: () -> Unit,
    onMyCard: () -> Unit,
    onProfile: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            ).fillMaxWidth().selectableGroup(),
        ) {
            primaryNavigationItems.forEach { item ->
                val label = item.label
                val selected = label == selectedLabel
                Column(
                    Modifier.weight(1f).selectable(
                        selected = selected, enabled = enabled, role = Role.Tab,
                        onClick = when (label) {
                            R.string.profile -> onProfile
                            R.string.share -> onHome
                            else -> onMyCard
                        },
                    ).heightIn(min = 80.dp).padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.widthIn(min = 64.dp).height(32.dp).clip(CircleShape).background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(item.icon), null, Modifier.size(24.dp),
                            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(label), style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}
