package com.mackseoul.vcardnative.ui.screens.card

import com.mackseoul.vcardnative.domain.profile.CardStyle
import com.mackseoul.vcardnative.domain.profile.MinimalItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CardRendererTest {
    private fun field(value: String, shown: Boolean = true) = CardContactField(value, displayEnabled = shown, validated = true)

    private val full = MyCardProfile(
        fullName = "Renderer Fixture",
        professionalTitle = "Title", organization = "Org", biography = "Bio", location = "City",
        phone = field("+15550100"), email = field("fixture@example.test"), website = field("https://example.test"),
        professionalLinks = listOf(CardProfessionalLink("Code", field("https://example.test/code"), "github")),
        publicKey = CardPublicKey(displayEnabled = true, validated = true),
        hostedProfileUrl = "https://example.test/card",
    )
    private val sparse = MyCardProfile(fullName = "Sparse Fixture", phone = field("+15550100", shown = false))
    private val nonMinimal = CardStyle.entries.filter { it != CardStyle.Minimal }

    private fun source(name: String) = File("src/main/java/com/mackseoul/vcardnative/ui/$name").readText()

    @Test
    fun allElevenMinimalItemsMapToDistinctSections() {
        val expected = mapOf(
            MinimalItem.ProfilePhoto to CardSection.ProfilePhoto,
            MinimalItem.CoverImage to CardSection.CoverImage,
            MinimalItem.ProfessionalTitle to CardSection.ProfessionalTitle,
            MinimalItem.Organization to CardSection.Organization,
            MinimalItem.Biography to CardSection.Biography,
            MinimalItem.Phone to CardSection.Phone,
            MinimalItem.Email to CardSection.Email,
            MinimalItem.Location to CardSection.Location,
            MinimalItem.Website to CardSection.Website,
            MinimalItem.ProfessionalLinks to CardSection.ProfessionalLinks,
            MinimalItem.HostedProfile to CardSection.HostedProfile,
        )
        assertEquals(11, MinimalItem.entries.size)
        MinimalItem.entries.forEach { assertEquals(it.name, expected.getValue(it), it.section) }
        assertEquals(11, MinimalItem.entries.map { it.section }.toSet().size)
        MinimalItem.entries.forEach {
            assertEquals(listOf(it.section), cardSections(CardStyle.Minimal, setOf(it), full, hasCover = true, hasPortrait = true))
        }
    }

    @Test
    fun everyNonMinimalStyleKeepsAllEligibleClassicContent() {
        val classic = cardSections(CardStyle.Classic, emptySet(), full, hasCover = true, hasPortrait = true)
        assertEquals(CardSection.entries, classic)
        nonMinimal.forEach { style ->
            assertEquals(style.name, classic, cardSections(style, setOf(MinimalItem.Phone), full, true, true))
        }
    }

    @Test
    fun hiddenAndEmptyValuesAreNeverEligible() {
        nonMinimal.forEach { style ->
            assertEquals(style.name, emptyList<CardSection>(), cardSections(style, emptySet(), sparse, false, false))
        }
        val hiddenLink = full.copy(professionalLinks = listOf(CardProfessionalLink("x", field("https://x.test", shown = false))))
        assertFalse(hiddenLink.hasContent(CardSection.ProfessionalLinks, false, false))
        assertFalse(full.copy(publicKey = null).hasContent(CardSection.PublicKey, false, false))
    }

    @Test
    fun minimalShowsNothingOptionalByDefaultAndKeepsCanonicalOrder() {
        assertEquals(emptyList<CardSection>(), cardSections(CardStyle.Minimal, emptySet(), full, true, true))
        val selected = setOf(MinimalItem.HostedProfile, MinimalItem.Phone, MinimalItem.ProfilePhoto, MinimalItem.Biography)
        assertEquals(
            listOf(CardSection.ProfilePhoto, CardSection.Biography, CardSection.Phone, CardSection.HostedProfile),
            cardSections(CardStyle.Minimal, selected, full, true, true),
        )
        assertFalse(CardSection.PublicKey in cardSections(CardStyle.Minimal, MinimalItem.entries.take(4).toSet(), full, true, true))
    }

    @Test
    fun selectedButEmptyMinimalItemsAddNoRows() {
        val selected = setOf(MinimalItem.Phone, MinimalItem.Email, MinimalItem.CoverImage, MinimalItem.Website)
        assertEquals(emptyList<CardSection>(), cardSections(CardStyle.Minimal, selected, sparse, hasCover = false, hasPortrait = false))
        val partial = sparse.copy(email = field("fixture@example.test"))
        assertEquals(listOf(CardSection.Email), cardSections(CardStyle.Minimal, selected, partial, false, false))
    }

    @Test
    fun minimalAlwaysRendersFullNameAndOnlyItsSections() {
        val minimal = source("screens/card/MinimalCard.kt")
        val body = minimal.substringAfter("internal fun MinimalCard(")
        val nameCall = body.indexOf("CardName(profile.fullName")
        assertTrue(nameCall > 0)
        assertFalse(body.substring(0, nameCall).contains("CardSection.ProfessionalTitle"))
        assertFalse(minimal.contains("PublicKeySection"))
        assertFalse(minimal.contains("contactActions"))
    }

    @Test
    fun rendererDispatchesEverySevenStylesExhaustively() {
        val renderer = source("screens/card/CardRenderer.kt")
        assertTrue(renderer.contains("if (style == CardStyle.Classic) {\r\n        ClassicCard(") ||
            renderer.contains("if (style == CardStyle.Classic) {\n        ClassicCard("))
        mapOf(
            CardStyle.Minimal to "MinimalCard", CardStyle.Professional to "ProfessionalCard",
            CardStyle.Modern to "ModernCard", CardStyle.Executive to "ExecutiveCard",
            CardStyle.Editorial to "EditorialCard", CardStyle.Horizon to "HorizonCard",
        ).forEach { (style, card) ->
            assertTrue(style.name, renderer.contains("CardStyle.${style.name} -> $card(content, modifier)"))
            assertTrue(card, File("src/main/java/com/mackseoul/vcardnative/ui/screens/card/$card.kt").exists())
        }
        assertEquals(7, CardStyle.entries.size)
    }

    @Test
    fun nonMinimalStylesKeepClassicActions() {
        listOf("ProfessionalCard", "ModernCard", "ExecutiveCard", "EditorialCard", "HorizonCard").forEach { card ->
            val text = source("screens/card/$card.kt")
            assertTrue(card, Regex("""CardName\(\s*profile\.fullName""").containsMatchIn(text))
            assertTrue(card, Regex("""HostedProfileRow\(content(, compact = true)?\)""").containsMatchIn(text))
            assertTrue(card, text.contains("PublicKeySection(content)"))
            assertTrue(card, text.contains("ProfessionalLinkList(content"))
            assertTrue(card, text.contains("CardSection.CoverImage"))
            assertTrue(card, text.contains("content.portrait"))
            listOf("ProfessionalTitle", "Organization", "Biography", "Location").forEach {
                assertTrue("$card $it", text.contains("CardSection.$it"))
            }
            val website = text.contains("WebsiteLink(content)") || text.contains("CardContactDetails(content") ||
                text.contains("profile.contactActions")
            assertTrue("$card website", website)
            val phoneEmail = text.contains("CardContactDetails(content") ||
                (text.contains("CardSection.Phone") && text.contains("CardSection.Email"))
            assertTrue("$card phone/email", phoneEmail)
        }
        val shared = source("screens/card/CardSections.kt")
        assertTrue(shared.contains("CardContactKind.HostedProfile"))
        assertTrue(shared.contains("CardContactKind.Social"))
        assertTrue(shared.contains("CardContactKind.Website"))
        assertTrue(shared.contains("enabled = !actions.keyBusy"))
    }

    @Test
    fun modernTilesFormBalancedRows() {
        assertEquals(3, modernTilesPerRow(3, 312f, 1f))
        assertEquals(4, modernTilesPerRow(4, 360f, 1f))
        assertEquals(2, modernTilesPerRow(4, 280f, 1f))
        assertEquals(1, modernTilesPerRow(3, 200f, 1f))
        assertEquals(1, modernTilesPerRow(3, 312f, 1.5f))
        assertEquals(1, modernTilesPerRow(4, 312f, 1.5f))
        assertEquals(1, modernTilesPerRow(1, 312f, 1f))
        for (count in 1..4) for (width in listOf(200f, 280f, 312f, 360f, 600f)) for (scale in listOf(1f, 1.3f, 1.5f, 2f)) {
            val perRow = modernTilesPerRow(count, width, scale)
            assertTrue("$count $width $scale", perRow == 1 || perRow == count || (perRow == 2 && count % 2 == 0))
        }
    }

    @Test
    fun modernThreeActionsNeverFormTwoPlusOne() {
        for (width in 120..900 step 4) for (scale in listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 1.8f, 2f)) {
            val perRow = modernTilesPerRow(3, width.toFloat(), scale)
            assertTrue("3 tiles at $width dp x$scale -> $perRow", perRow == 3 || perRow == 1)
            assertEquals(listOf(perRow), (1..3).toList().chunked(perRow).map { it.size }.distinct())
        }
    }

    @Test
    fun modernFourActionsUseBalancedRows() {
        for (width in 120..900 step 4) for (scale in listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 1.8f, 2f)) {
            val perRow = modernTilesPerRow(4, width.toFloat(), scale)
            assertTrue("4 tiles at $width dp x$scale -> $perRow", perRow in setOf(1, 2, 4))
            assertEquals(listOf(perRow), (1..4).toList().chunked(perRow).map { it.size }.distinct())
        }
        assertEquals(4, modernTilesPerRow(4, 360f, 1f))
        assertEquals(2, modernTilesPerRow(4, 280f, 1f))
        assertEquals(1, modernTilesPerRow(4, 312f, 1.5f))
    }

    @Test
    fun modernTileRowsAreBuiltOnlyFromTheBalancedRowSize() {
        val text = source("screens/card/ModernCard.kt")
        assertTrue(text.contains("val perRow = modernTilesPerRow(tiles.size, maxWidth.value, fontScale)"))
        assertTrue(text.contains("tiles.chunked(perRow).forEach { row ->"))
        assertTrue(text.contains("ActionTile(action, perRow == 1, Modifier.weight(1f))"))
        assertFalse(text.contains("repeat(perRow - row.size)"))
        assertFalse(text.contains("val perRow = if (stacked) 1 else 2"))
    }
    @Test
    fun modernShowsEachLabelledContactValueOnceWithAllActions() {
        val text = source("screens/card/ModernCard.kt")
        assertTrue(text.contains("profile.contactActions"))
        assertEquals(1, Regex("""CardDetailRow\(R\.string\.phone,""").findAll(text).count())
        assertEquals(1, Regex("""CardDetailRow\(R\.string\.email,""").findAll(text).count())
        assertTrue(text.contains("CardDetailRow(R.string.phone, R.drawable.ic_call, requireNotNull(profile.phone).value)"))
        assertTrue(text.contains("CardDetailRow(R.string.email, R.drawable.ic_email, requireNotNull(profile.email).value)"))
        assertFalse(Regex("""CardDetailRow\(R\.string\.(phone|email),[^\n]*showLabel = false""").containsMatchIn(text))
        assertTrue(text.contains("content.actions.onContactAction(action)"))
        assertTrue(text.contains("heightIn(min = if (fullWidth) 56.dp else 64.dp)"))
    }

    @Test
    fun horizonPhoneWidthsAlwaysUseOneColumn() {
        // Panel = card width minus 40 dp padding; covers compact through large phones (card widths up to ~500 dp).
        for (panel in listOf(240f, 288f, 320f, 340f, 372f, 400f, 440f, 479f)) {
            assertFalse("$panel", horizonUsesTwoColumns(panel, 1f, listOf(40f, 60f)))
            assertFalse("$panel email", horizonUsesTwoColumns(panel, 1f, listOf(120f, 135f)))
        }
    }

    @Test
    fun horizonWideCardsUseTwoColumnsOnlyWhenMeasuredValuesFit() {
        // 560 dp panel: each column leaves (560 - 12) / 2 - 32 = 242 dp for text.
        assertTrue(horizonUsesTwoColumns(560f, 1f, listOf(70f, 130f, 240f)))
        assertTrue(horizonUsesTwoColumns(HORIZON_TWO_COLUMN_MIN_PANEL_DP, 1f, listOf(100f, 150f)))
        assertFalse(horizonUsesTwoColumns(560f, 1f, listOf(70f, 130f, 260f)))
        assertFalse(horizonUsesTwoColumns(800f, 1f, listOf(150f)))
        assertFalse(horizonUsesTwoColumns(560f, 1.3f, listOf(70f, 130f)))
        assertFalse(horizonUsesTwoColumns(800f, 2f, listOf(70f, 130f)))
    }

    @Test
    fun horizonMeasuresRealTextAndNeverTruncatesContactValues() {
        val horizon = source("screens/card/HorizonCard.kt")
        assertTrue(horizon.contains("rememberTextMeasurer()"))
        assertTrue(horizon.contains("measurer.measure(detail.value, style, softWrap = false, maxLines = 1)"))
        assertTrue(horizon.contains("MaterialTheme.typography.bodyLarge"))
        assertTrue(horizon.contains("CardDetailRow(it.label, it.icon, it.value, MaterialTheme.colorScheme.primary)"))
        assertFalse(horizon.contains("TextOverflow"))
        assertFalse(horizon.contains("Ellipsis"))
        assertFalse(horizon.contains("* 9f"))
        val detailRow = source("screens/card/CardSections.kt")
            .substringAfter("internal fun CardDetailRow(").substringBefore("internal fun CardLinkRow(")
        assertTrue(detailRow.contains("SelectionContainer"))
        assertTrue(detailRow.contains("Text(value, style = MaterialTheme.typography.bodyLarge)"))
        assertFalse(detailRow.contains("maxLines"))
        assertFalse(detailRow.contains("TextOverflow"))
    }
    @Test
    fun editorialScalesLongNamesAndStacksBylineAtLargeText() {
        assertEquals(36f, editorialNameSizeSp("Ian Mack", 312f, 1f))
        val long = editorialNameSizeSp("Maximilianus Wolfeschlegelsteinhausen", 312f, 1f)
        assertTrue(long < 36f)
        assertTrue(editorialNameSizeSp("Maximilianus Wolfeschlegelsteinhausenbergerdorff", 200f, 2f) >= 24f)
        assertTrue(editorialNameSizeSp("Ian Mack", 200f, 2f) <= editorialNameSizeSp("Ian Mack", 200f, 1f))
        assertTrue(editorialStacksByline(312f, 1.5f))
        assertTrue(editorialStacksByline(220f, 1f))
        assertFalse(editorialStacksByline(312f, 1f))
        val editorial = source("screens/card/EditorialCard.kt")
        assertTrue(editorial.contains("fontFamily = FontFamily.Serif"))
        assertTrue(editorial.contains("editorialNameSizeSp(profile.fullName"))
        assertFalse(editorial.contains("maxLines"))
        assertFalse(editorial.contains("TextOverflow"))
    }

    @Test
    fun executiveAndProfessionalUseCompactRowsWithoutShrinkingTouchTargets() {
        listOf("ExecutiveCard", "ProfessionalCard").forEach { card ->
            val text = source("screens/card/$card.kt")
            assertTrue(card, text.contains("HostedProfileRow(content, compact = true)"))
            assertTrue(card, text.contains("compact = true)"))
        }
        val shared = source("screens/card/CardSections.kt")
        assertTrue(shared.contains("heightIn(min = 48.dp)"))
        assertTrue(shared.contains("if (compact) 6.dp else 12.dp"))
    }
    @Test
    fun myCardAndLivePreviewShareTheRendererAndImageFilter() {
        val myCard = source("screens/card/MyCardScreen.kt")
        val editor = source("profile/AppearanceScreen.kt")
        val app = source("profile/ProfileScreens.kt")
        assertTrue(myCard.contains("ProfileCard(\r\n                        appearance.cardStyle") ||
            myCard.contains("ProfileCard(\n                        appearance.cardStyle"))
        assertTrue(editor.contains("ProfileCard(") && editor.contains("draft.cardStyle, draft.minimalItems"))
        assertTrue(editor.contains("state.visibleCardImages()"))
        assertTrue(app.contains("state.visibleCardImages().portrait"))
        assertTrue(app.contains("appearance = requireNotNull(state.saved).data.appearance"))
    }

    @Test
    fun previewAndThumbnailsCannotTriggerRealActions() {
        val editor = source("profile/AppearanceScreen.kt")
        val preview = editor.substringAfter("private fun AppearancePreview(").substringBefore("private fun MinimalItemSelector(")
        listOf("onContactAction", "onDownloadPublicKey", "onSharePublicKey", "model.").forEach {
            assertFalse(it, preview.contains(it))
        }
        assertTrue(preview.contains("onShowOnlineQr = {}"))
        assertTrue(preview.contains("clearAndSetSemantics"))
        assertTrue(preview.contains("awaitPointerEvent()"))
        val thumbnails = source("screens/card/CardStyleThumbnails.kt")
        listOf("onClick", "onContactAction", "ImageBitmap", "MyCardProfile").forEach {
            assertFalse(it, thumbnails.contains(it))
        }
        assertTrue(thumbnails.contains("clearAndSetSemantics {}"))
    }

    @Test
    fun classicRendererRemainsTheV11Implementation() {
        val classic = source("screens/card/ClassicCard.kt")
        assertTrue(classic.contains("internal fun ClassicCard("))
        assertFalse(classic.contains("CardStyle"))
        assertFalse(classic.contains("CardContent"))
    }
}
