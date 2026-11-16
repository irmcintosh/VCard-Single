package com.mackseoul.vcardnative.ui.screens.card

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mackseoul.vcardnative.R
import com.mackseoul.vcardnative.ui.profile.PublicKeyMetadata

/**
 * The V1.1 My Card design ("Classic"). Driven only by explicit data and callbacks so it can be reused
 * outside My Card. `keyBusy` defaults to true so a callback-less card never offers public-key actions.
 */
@Composable
internal fun ClassicCard(
    profile: MyCardProfile, cover: ImageBitmap?, portrait: ImageBitmap?,
    modifier: Modifier = Modifier,
    onDownloadPublicKey: () -> Unit = {}, onSharePublicKey: () -> Unit = {}, keyBusy: Boolean = true,
    onContactAction: (CardContactAction) -> Unit = {},
    onShowOnlineQr: (() -> Unit)? = null,
) {
    val portraitInFlow = LocalConfiguration.current.fontScale >= 1.5f
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            if (portraitInFlow) {
                CoverViewport(cover)
            } else {
                Box {
                    Column {
                        CoverViewport(cover)
                        Spacer(Modifier.height(44.dp))
                    }
                    Box(Modifier.align(Alignment.BottomStart).padding(start = 20.dp)) {
                        NeutralPortrait(portrait)
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                ProfileIdentity(profile, portrait, portraitInFlow)
                profile.biography?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge)
                }
                profile.location?.takeIf { it.isNotBlank() }?.let {
                    ContactDetailRow(R.string.location, R.drawable.ic_location, it)
                }
                if (profile.phone?.canDisplay == true || profile.email?.canDisplay == true ||
                    profile.website?.canDisplay == true
                ) {
                    ContactInformation(profile, onContactAction)
                }
                if (profile.visibleProfessionalLinks.isNotEmpty()) {
                    ProfessionalLinks(profile.visibleProfessionalLinks, onContactAction)
                }
                profile.hostedProfileUrl?.let { url ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            OnlineLinkRow(R.string.view_online_card) {
                                onContactAction(CardContactAction(CardContactKind.HostedProfile, url))
                            }
                        }
                        onShowOnlineQr?.let { showQr ->
                            IconButton(onClick = showQr, enabled = !keyBusy, modifier = Modifier.size(48.dp)) {
                                Icon(painterResource(R.drawable.ic_qr),
                                    contentDescription = stringResource(R.string.show_online_card_qr),
                                    modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                if (profile.canDisplayPublicKey) {
                    PublicKeyRow(requireNotNull(profile.publicKey), onDownloadPublicKey, onSharePublicKey, keyBusy)
                }
            }
        }
    }
}

@Composable
private fun CoverViewport(cover: ImageBitmap?) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(3f)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (cover != null) {
            Image(cover, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun ProfileIdentity(profile: MyCardProfile, portrait: ImageBitmap?, portraitInFlow: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (portraitInFlow) {
            NeutralPortrait(portrait)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                profile.fullName,
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
            profile.professionalTitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
            profile.organization?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
internal fun NeutralPortrait(portrait: ImageBitmap? = null) {
    Box(
        Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface)
            .padding(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (portrait == null) {
            Icon(
                painterResource(R.drawable.ic_person), contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Image(portrait, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun ContactInformation(profile: MyCardProfile, onAction: (CardContactAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        profile.phone?.takeIf { it.canDisplay }?.let {
            ContactDetailRow(R.string.phone, R.drawable.ic_call, it.value)
        }
        profile.email?.takeIf { it.canDisplay }?.let {
            ContactDetailRow(R.string.email, R.drawable.ic_email, it.value)
        }
        profile.website?.takeIf { it.canDisplay }?.let {
            OnlineLinkRow(R.string.visit_website) {
                onAction(CardContactAction(CardContactKind.Website, it.value))
            }
        }
    }
}

@Composable
private fun ContactDetailRow(label: Int, icon: Int, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.padding(top = 3.dp).size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun ProfessionalLinks(links: List<CardProfessionalLink>, onAction: (CardContactAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.professional_links), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val itemWidth = (maxWidth / 3.5f).coerceAtLeast(48.dp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(links) { link ->
                    val icon = when (link.platform.lowercase(java.util.Locale.ROOT)) {
                        "linkedin" -> R.drawable.ic_linkedin
                        "github" -> R.drawable.ic_github
                        else -> null
                    }
                    val description = stringResource(R.string.social_open_description, link.label, link.url.value)
                    Surface(
                        onClick = { onAction(CardContactAction(CardContactKind.Social, link.url.value)) },
                        modifier = Modifier.width(itemWidth).heightIn(min = 48.dp)
                            .semantics { contentDescription = description },
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                            if (icon != null) {
                                Icon(painterResource(icon), null, Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                            } else {
                                Text(link.label, style = MaterialTheme.typography.bodyMedium,
                                    textDecoration = TextDecoration.Underline, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineLinkRow(label: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_external), null, Modifier.size(20.dp))
            Text(stringResource(label), modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
        }
    }
}

@Composable
private fun PublicKeyRow(key: CardPublicKey, download: () -> Unit, share: () -> Unit, busy: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        key.asset?.let { PublicKeyMetadata(it, key.status) }
        if (key.status?.usable == true) {
            OutlinedButton(
                onClick = download, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                ActionLabel(R.string.download_public_key, R.drawable.ic_download)
            }
            OutlinedButton(
                onClick = share, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.share_public_key)) }
        }
    }
}

@Composable
internal fun ActionLabel(@StringRes label: Int, @DrawableRes icon: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(24.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
    }
}
