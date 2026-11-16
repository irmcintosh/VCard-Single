package com.mackseoul.vcardnative.ui.screens.card

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.mackseoul.vcardnative.R
import com.mackseoul.vcardnative.domain.profile.PublicKeyAsset
import com.mackseoul.vcardnative.domain.keys.PublicKeyStatus

data class MyCardUiState(val profile: MyCardProfile? = null)

internal data class PrimaryNavigationItem(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
)

internal val primaryNavigationItems = listOf(
    PrimaryNavigationItem(R.string.my_card, R.drawable.ic_card),
    PrimaryNavigationItem(R.string.share, R.drawable.ic_qr),
    PrimaryNavigationItem(R.string.profile, R.drawable.ic_person),
)

// Display-only projection; optional identity text is already filtered by saved visibility.
// This is not a persistence model or a replacement for domain validation.
data class MyCardProfile(
    val fullName: String,
    val professionalTitle: String? = null,
    val organization: String? = null,
    val biography: String? = null,
    val location: String? = null,
    val phone: CardContactField? = null,
    val email: CardContactField? = null,
    val website: CardContactField? = null,
    val professionalLinks: List<CardProfessionalLink> = emptyList(),
    val publicKey: CardPublicKey? = null,
    val hostedProfileUrl: String? = null,
) {
    val contactActions: List<CardContactAction>
        get() = buildList {
            phone?.takeIf { it.canDisplay }?.let {
                add(CardContactAction(CardContactKind.Call, it.actionValue))
                add(CardContactAction(CardContactKind.Message, it.actionValue))
            }
            email?.takeIf { it.canDisplay }?.let {
                add(CardContactAction(CardContactKind.Email, it.value))
            }
            website?.takeIf { it.canDisplay }?.let {
                add(CardContactAction(CardContactKind.Website, it.value))
            }
        }

    val visibleProfessionalLinks: List<CardProfessionalLink>
        get() = professionalLinks.filter { it.label.isNotBlank() && it.url.canDisplay }

    val canDisplayPublicKey: Boolean
        get() = publicKey?.let { it.displayEnabled && it.validated } == true
}

data class CardContactField(
    val value: String,
    val displayEnabled: Boolean,
    val validated: Boolean,
    val actionValue: String = value,
) {
    val canDisplay: Boolean
        get() = displayEnabled && validated && value.isNotBlank()
}

enum class CardContactKind { Call, Message, Email, Website, Social, HostedProfile }

data class CardContactAction(val kind: CardContactKind, val destination: String)

data class CardProfessionalLink(val label: String, val url: CardContactField, val platform: String = "")

data class CardPublicKey(
    val displayEnabled: Boolean,
    val validated: Boolean,
    val asset: PublicKeyAsset? = null,
    val status: PublicKeyStatus? = null,
)
