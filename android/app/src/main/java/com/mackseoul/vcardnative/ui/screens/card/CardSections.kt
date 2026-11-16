package com.mackseoul.vcardnative.ui.screens.card

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mackseoul.vcardnative.R
import com.mackseoul.vcardnative.ui.profile.PublicKeyMetadata
import java.util.Locale

// Shared building blocks for the non-Classic styles. ClassicCard.kt keeps its own V1.1 private helpers unchanged.

@Composable
internal fun CardSurface(
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    color: Color = MaterialTheme.colorScheme.surface,
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    content: @Composable () -> Unit,
) {
    Surface(modifier = modifier, shape = shape, color = color, border = border, content = content)
}

@Composable
internal fun CardCover(cover: ImageBitmap?, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (cover != null) {
            Image(cover, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/** Decorative portrait (the name is announced separately); shows a neutral placeholder when no photo is saved. */
@Composable
internal fun CardPortrait(
    portrait: ImageBitmap?,
    size: Dp,
    shape: Shape = CircleShape,
    ring: Color? = null,
) {
    val ringModifier = if (ring != null) {
        Modifier.clip(shape).background(ring).padding(3.dp)
    } else {
        Modifier
    }
    Box(
        Modifier.size(size).then(ringModifier).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (portrait == null) {
            Icon(
                painterResource(R.drawable.ic_person), contentDescription = null,
                modifier = Modifier.size(size * 0.55f), tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Image(portrait, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
internal fun CardName(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(text, style = style, color = color, modifier = modifier.semantics { heading() })
}

@Composable
internal fun SectionLabel(@StringRes label: Int, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        stringResource(label).uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
internal fun CardDetailRow(
    @StringRes label: Int,
    @DrawableRes icon: Int,
    value: String,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    showLabel: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.padding(top = 3.dp).size(20.dp), tint = iconTint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (showLabel) {
                Text(
                    stringResource(label), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SelectionContainer {
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
internal fun CardLinkRow(
    label: String,
    onClick: () -> Unit,
    @DrawableRes icon: Int = R.drawable.ic_external,
    modifier: Modifier = Modifier,
    description: String? = null,
    verticalPadding: Dp = 12.dp,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        color = Color.Transparent,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            Modifier.padding(vertical = verticalPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline, style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** Phone and email stay selectable text and website stays a link, matching Classic's behavior. */
@Composable
internal fun CardContactDetails(
    content: CardContent,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    compact: Boolean = false,
) {
    val profile = content.profile
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (content.has(CardSection.Phone)) {
            CardDetailRow(R.string.phone, R.drawable.ic_call, requireNotNull(profile.phone).value, iconTint)
        }
        if (content.has(CardSection.Email)) {
            CardDetailRow(R.string.email, R.drawable.ic_email, requireNotNull(profile.email).value, iconTint)
        }
        if (content.has(CardSection.Website)) {
            WebsiteLink(content, compact)
        }
    }
}

@Composable
internal fun WebsiteLink(content: CardContent, compact: Boolean = false) {
    val url = requireNotNull(content.profile.website).value
    CardLinkRow(stringResource(R.string.visit_website), onClick = {
        content.actions.onContactAction(CardContactAction(CardContactKind.Website, url))
    }, verticalPadding = if (compact) 6.dp else 12.dp)
}

/** V1.2 Social Icon Grid for the six non-Classic styles; every cell opens the saved URL exactly as Classic does. */
@Composable
internal fun ProfessionalLinkList(content: CardContent, divided: Boolean = false, compact: Boolean = false) {
    SocialIconGrid(
        links = content.profile.visibleProfessionalLinks,
        onOpen = { link -> content.actions.onContactAction(CardContactAction(CardContactKind.Social, link.url.value)) },
        divided = divided,
        compact = compact,
    )
}

/** Hosted Profile link plus its online QR button, with Classic's enablement rules. */
@Composable
internal fun HostedProfileRow(content: CardContent, compact: Boolean = false) {
    val url = requireNotNull(content.profile.hostedProfileUrl)
    val actions = content.actions
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            CardLinkRow(stringResource(R.string.view_online_card), onClick = {
                actions.onContactAction(CardContactAction(CardContactKind.HostedProfile, url))
            }, verticalPadding = if (compact) 6.dp else 12.dp)
        }
        actions.onShowOnlineQr?.let { showQr ->
            IconButton(onClick = showQr, enabled = !actions.keyBusy, modifier = Modifier.size(48.dp)) {
                Icon(
                    painterResource(R.drawable.ic_qr),
                    contentDescription = stringResource(R.string.show_online_card_qr),
                    modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Same metadata and actions as Classic's public-key row. */
@Composable
internal fun PublicKeySection(content: CardContent) {
    val key = requireNotNull(content.profile.publicKey)
    val actions = content.actions
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        key.asset?.let { PublicKeyMetadata(it, key.status) }
        if (key.status?.usable == true) {
            OutlinedButton(
                onClick = actions.onDownloadPublicKey, enabled = !actions.keyBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                ActionLabel(R.string.download_public_key, R.drawable.ic_download)
            }
            OutlinedButton(
                onClick = actions.onSharePublicKey, enabled = !actions.keyBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.share_public_key)) }
        }
    }
}

@Composable
internal fun OptionalText(text: String?, style: TextStyle, color: Color = Color.Unspecified, maxLines: Int = Int.MAX_VALUE) {
    text?.takeIf { it.isNotBlank() }?.let {
        Text(it, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}
