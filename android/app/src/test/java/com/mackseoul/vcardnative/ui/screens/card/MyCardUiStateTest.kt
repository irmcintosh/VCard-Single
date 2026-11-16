package com.mackseoul.vcardnative.ui.screens.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyCardUiStateTest {
    @Test fun customShareTerminologyPreservesResourceKeysAndQuickCardNames() {
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.File("src/main/res/values/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        val values = (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as org.w3c.dom.Element
            element.getAttribute("name") to element.textContent
        }
        assertEquals("Custom Share", values["quick_share"])
        assertEquals("Custom Share QR", values["quick_share_qr"])
        assertEquals("Quick Card", values["quick_card_widget_name"])
        assertEquals("Quick Card options", values["quick_card_widget_options"])
        assertEquals("Show My QR Code", values["shortcut_show_my_qr_code"])
        assertFalse(values.values.any { it.contains("Quick Share") })
        val model = java.io.File("src/main/java/com/mackseoul/vcardnative/ui/profile/ProfileViewModel.kt").readText()
        assertFalse(model.contains("Quick Share"))
        assertTrue(model.contains("fun startQuickShare()"))
        assertTrue(model.contains("QuickShareSelectInformation"))
        assertTrue(model.contains("quickShareSelectionStore"))
    }

    @Test fun myCardRetainsCustomShareAndOnlineQrButNotRedundantContactQrControl() {
        val screen = java.io.File("src/main/java/com/mackseoul/vcardnative/ui/screens/card/MyCardScreen.kt").readText()
        val card = java.io.File("src/main/java/com/mackseoul/vcardnative/ui/screens/card/ClassicCard.kt").readText()
        val renderer = java.io.File("src/main/java/com/mackseoul/vcardnative/ui/screens/card/CardRenderer.kt").readText()
        assertTrue(screen.contains("ProfileCard("))
        assertTrue(renderer.contains("ClassicCard("))
        // The card body lives in ClassicCard.kt behind the shared renderer; My Card's guarantees span all three.
        val source = screen + card + renderer
        assertFalse(source.contains("onShowQr"))
        assertFalse(source.contains("R.string.show_qr"))
        assertTrue(screen.contains("onClick = onQuickShare"))
        assertTrue(screen.contains("ActionLabel(R.string.quick_share, R.drawable.ic_card)"))
        assertTrue(screen.contains("onShowOnlineQr = onShowOnlineQr"))
        assertTrue(card.contains("onShowOnlineQr"))
        assertTrue(card.contains("R.string.view_online_card"))
        val application = java.io.File("src/main/java/com/mackseoul/vcardnative/ui/profile/ProfileScreens.kt").readText()
        assertFalse(application.contains("onShowQr ="))
        assertTrue(application.contains("onQuickShare = model::startQuickShare"))
        assertTrue(application.contains("ProfileDestination.QrPresentation -> QrPresentationScreen"))
    }

    private val profile = MyCardProfile(fullName = "Identity test fixture")
    private val field = CardContactField("test-value", displayEnabled = true, validated = true)

    @Test
    fun firstRunHasNoFabricatedProfile() {
        assertNull(MyCardUiState().profile)
    }

    @Test
    fun absentOptionalFieldsHaveNoContactDetailsOrLinks() {
        assertNull(profile.phone)
        assertNull(profile.email)
        assertNull(profile.website)
        assertTrue(profile.visibleProfessionalLinks.isEmpty())
        assertFalse(profile.canDisplayPublicKey)
    }

    @Test
    fun displayEnabledValidatedContactFieldsRemainReadableValues() {
        val populated = profile.copy(phone = field, email = field, website = field)
        assertEquals(field.value, populated.phone?.value)
        assertEquals(field.value, populated.email?.value)
        assertEquals(field.value, populated.website?.value)
        assertTrue(populated.phone?.canDisplay == true)
        assertTrue(populated.email?.canDisplay == true)
        assertTrue(populated.website?.canDisplay == true)
    }

    @Test
    fun compatibilityContactActionsIncludeOnlyValidVisibleFields() {
        val populated = profile.copy(phone = field, email = field, website = field)

        assertEquals(
            listOf(
                CardContactAction(CardContactKind.Call, field.value),
                CardContactAction(CardContactKind.Message, field.value),
                CardContactAction(CardContactKind.Email, field.value),
                CardContactAction(CardContactKind.Website, field.value),
            ),
            populated.contactActions,
        )
        assertTrue(
            profile.copy(phone = field.copy(displayEnabled = false)).contactActions.isEmpty(),
        )
        assertTrue(
            profile.copy(email = field.copy(validated = false)).contactActions.isEmpty(),
        )
    }

    @Test
    fun hiddenFieldsAreNotDisplayableOnMyCard() {
        val hidden = field.copy(displayEnabled = false)
        val hiddenProfile = profile.copy(phone = hidden, email = hidden, website = hidden)
        assertFalse(hiddenProfile.phone?.canDisplay == true)
        assertFalse(hiddenProfile.email?.canDisplay == true)
        assertFalse(hiddenProfile.website?.canDisplay == true)
    }

    @Test
    fun invalidOrBlankFieldsAreNotDisplayableOnMyCard() {
        listOf(field.copy(validated = false), field.copy(value = ""), field.copy(value = " \n")).forEach {
            val invalidProfile = profile.copy(phone = it, email = it, website = it)
            assertFalse(invalidProfile.phone?.canDisplay == true)
            assertFalse(invalidProfile.email?.canDisplay == true)
            assertFalse(invalidProfile.website?.canDisplay == true)
        }
    }

    @Test
    fun optionalProfessionalLinksRequireLabelVisibilityAndValidation() {
        val visible = CardProfessionalLink("Test link", field)
        val links = listOf(
            visible,
            visible.copy(label = " "),
            visible.copy(url = field.copy(displayEnabled = false)),
            visible.copy(url = field.copy(validated = false)),
            visible.copy(url = field.copy(value = "")),
        )
        assertEquals(listOf(visible), profile.copy(professionalLinks = links).visibleProfessionalLinks)
    }

    @Test
    fun publicKeyRowRequiresBothValidationAndDisplayPermission() {
        assertFalse(profile.copy(publicKey = CardPublicKey(false, false)).canDisplayPublicKey)
        assertFalse(profile.copy(publicKey = CardPublicKey(false, true)).canDisplayPublicKey)
        assertFalse(profile.copy(publicKey = CardPublicKey(true, false)).canDisplayPublicKey)
        assertTrue(profile.copy(publicKey = CardPublicKey(true, true)).canDisplayPublicKey)
    }

    @Test
    fun eachContactFieldRemainsIndependent() {
        val emailOnly = profile.copy(email = field)
        assertNull(emailOnly.phone)
        assertTrue(emailOnly.email?.canDisplay == true)
        assertNull(emailOnly.website)
    }
}
