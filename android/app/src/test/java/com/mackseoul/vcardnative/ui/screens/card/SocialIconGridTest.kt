package com.mackseoul.vcardnative.ui.screens.card

import com.mackseoul.vcardnative.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class SocialIconGridTest {
    private fun field(value: String, shown: Boolean = true, valid: Boolean = true) =
        CardContactField(value, displayEnabled = shown, validated = valid)

    private fun link(label: String, url: String, platform: String = "", shown: Boolean = true) =
        CardProfessionalLink(label, field(url, shown), platform)

    private fun source(path: String) = File("src/main/java/com/mackseoul/vcardnative/$path").readText()
    private fun drawable(name: String) = File("src/main/res/drawable/$name.xml")

    private val nonClassic = listOf("MinimalCard", "ProfessionalCard", "ModernCard", "ExecutiveCard", "EditorialCard", "HorizonCard")

    @Test
    fun allElevenApprovedPlatformsMapToTheirSymbols() {
        val expected = mapOf(
            "linkedin" to SocialIcon.LinkedIn,
            "github" to SocialIcon.GitHub,
            "instagram" to SocialIcon.Instagram,
            "facebook" to SocialIcon.Facebook,
            "youtube" to SocialIcon.YouTube,
            "tiktok" to SocialIcon.TikTok,
            "x" to SocialIcon.X,
            "threads" to SocialIcon.Threads,
            "bluesky" to SocialIcon.Bluesky,
            "mastodon" to SocialIcon.Mastodon,
            "other" to SocialIcon.Other,
        )
        expected.forEach { (platform, icon) -> assertEquals(platform, icon, socialIconFor(platform)) }
        assertEquals(11, SocialIcon.entries.size)
        assertEquals(SocialIcon.entries.toSet(), expected.values.toSet())
    }

    @Test
    fun symbolsMatchApprovedDesign() {
        assertEquals("in", SocialIcon.LinkedIn.glyph)
        assertEquals("f", SocialIcon.Facebook.glyph)
        assertEquals("@", SocialIcon.Threads.glyph)
        listOf(SocialIcon.LinkedIn, SocialIcon.Facebook, SocialIcon.Threads).forEach { assertEquals(null, it.drawable) }
        assertEquals(R.drawable.ic_social_code, SocialIcon.GitHub.drawable)
        assertEquals(R.drawable.ic_social_camera, SocialIcon.Instagram.drawable)
        assertEquals(R.drawable.ic_social_play, SocialIcon.YouTube.drawable)
        assertEquals(R.drawable.ic_social_note, SocialIcon.TikTok.drawable)
        assertEquals(R.drawable.ic_social_bubble, SocialIcon.X.drawable)
        assertEquals(R.drawable.ic_social_bubble, SocialIcon.Mastodon.drawable)
        assertEquals(R.drawable.ic_social_bubbles, SocialIcon.Bluesky.drawable)
        assertEquals(R.drawable.ic_external, SocialIcon.Other.drawable)
        SocialIcon.entries.forEach { assertTrue(it.name, (it.drawable == null) != (it.glyph == null)) }
    }

    @Test
    fun savedAliasesAndCaseVariantsUseEditorNormalization() {
        assertEquals(SocialIcon.X, socialIconFor("twitter"))
        assertEquals(SocialIcon.X, socialIconFor("Twitter"))
        assertEquals(SocialIcon.X, socialIconFor("X"))
        assertEquals(SocialIcon.X, socialIconFor("X (Twitter)"))
        assertEquals(SocialIcon.LinkedIn, socialIconFor("LinkedIn"))
        assertEquals(SocialIcon.GitHub, socialIconFor("GITHUB"))
        assertEquals(SocialIcon.YouTube, socialIconFor("YouTube"))
        assertEquals(SocialIcon.TikTok, socialIconFor("TikTok"))
    }

    @Test
    fun unknownBlankAndOtherPlatformsFallBackToGenericSymbol() {
        listOf("", "   ", "other", "Other", "myspace", "custom-site", "linked in").forEach {
            assertEquals("'$it'", SocialIcon.Other, socialIconFor(it))
        }
    }

    @Test
    fun gridNeverUsesUnprovenancedBrandResources() {
        val grid = source("ui/screens/card/SocialIconGrid.kt")
        val shared = source("ui/screens/card/CardSections.kt")
        listOf("ic_linkedin", "ic_github").forEach {
            assertFalse(it, grid.contains(it))
            assertFalse(it, shared.contains(it))
        }
    }

    @Test
    fun customDrawablesAreLocalOriginalVectors() {
        listOf("ic_social_code", "ic_social_camera", "ic_social_play", "ic_social_note", "ic_social_bubble", "ic_social_bubbles")
            .forEach { name ->
                val file = drawable(name)
                assertTrue(name, file.exists())
                val text = file.readText()
                assertTrue(name, text.contains("<vector"))
                assertTrue(name, text.contains("Original VCard Native"))
                assertTrue(name, text.contains("android:width=\"24dp\""))
                listOf("<bitmap", "http://www.w3.org", "<image", "android:src").forEach { assertFalse("$name $it", text.contains(it)) }
            }
    }

    @Test
    fun columnsAreResponsiveCappedAndNeverZero() {
        assertEquals(4, socialIconColumns(6, 360f, 1f))
        assertEquals(4, socialIconColumns(4, 312f, 1f))
        assertEquals(3, socialIconColumns(3, 360f, 1f))
        assertEquals(1, socialIconColumns(1, 360f, 1f))
        assertEquals(3, socialIconColumns(8, 260f, 1f))
        assertEquals(1, socialIconColumns(3, 60f, 1f))
        assertEquals(1, socialIconColumns(0, 360f, 1f))
        assertEquals(1, socialIconColumns(4, Float.POSITIVE_INFINITY, 1f))
        assertEquals(4, socialIconColumns(11, 1200f, 1f))
        for (count in 0..12) for (width in listOf(0f, 48f, 200f, 312f, 412f, 800f)) for (scale in listOf(0.85f, 1f, 1.3f, 1.5f, 2f)) {
            val columns = socialIconColumns(count, width, scale)
            assertTrue(columns in 1..SOCIAL_GRID_MAX_COLUMNS)
            if (count > 0) assertTrue(columns <= count)
        }
    }

    @Test
    fun largerFontScalesUseFewerWiderCells() {
        assertTrue(socialIconColumns(4, 312f, 1.5f) <= 2)
        assertTrue(socialIconColumns(4, 312f, 2f) <= 2)
        assertEquals(1, socialIconColumns(4, 200f, 2f))
        assertTrue(socialGridMinCellDp(2f) > socialGridMinCellDp(1f))
        assertEquals(socialGridMinCellDp(1f), socialGridMinCellDp(0.85f), 0f)
        for (width in listOf(200f, 312f, 360f, 412f)) for (scale in listOf(1f, 1.3f, 1.5f, 2f)) {
            val columns = socialIconColumns(4, width, scale)
            val cell = (width - SOCIAL_GRID_GAP_DP * (columns - 1)) / columns
            assertTrue("$width@$scale", cell >= SOCIAL_GRID_MIN_TARGET_DP)
            if (columns > 1) assertTrue("$width@$scale", cell >= socialGridMinCellDp(scale))
        }
    }

    @Test
    fun rowsPreserveSavedOrderAndDuplicates() {
        val links = listOf(
            link("Work", "https://a.test", "linkedin"),
            link("Code", "https://b.test", "github"),
            link("Work", "https://a.test", "linkedin"),
            link("Video", "https://c.test", "youtube"),
            link("Misc", "https://d.test", "other"),
        )
        for (columns in 1..4) {
            val rows = socialGridRows(links, columns)
            assertEquals(links, rows.flatten())
            assertTrue(rows.all { it.size <= columns })
        }
        assertEquals(listOf(listOf(1, 2), listOf(3)), socialGridRows(listOf(1, 2, 3), 2))
        assertEquals(listOf(listOf(1), listOf(2)), socialGridRows(listOf(1, 2), 0))
    }

    @Test
    fun gridReceivesOnlyShowOnCardLinksInOrder() {
        val profile = MyCardProfile(
            fullName = "Grid Fixture",
            professionalLinks = listOf(
                link("Shown", "https://one.test", "github"),
                link("Hidden", "https://two.test", "x", shown = false),
                CardProfessionalLink("Invalid", field("https://three.test", valid = false), "tiktok"),
                link(" ", "https://four.test", "threads"),
                link("Shown", "https://one.test", "github"),
            ),
        )
        assertEquals(listOf("Shown", "Shown"), profile.visibleProfessionalLinks.map { it.label })
        assertTrue(source("ui/screens/card/CardSections.kt").contains("links = content.profile.visibleProfessionalLinks"))
    }

    @Test
    fun cellsKeepExistingSocialLaunchAction() {
        val shared = source("ui/screens/card/CardSections.kt")
        assertTrue(shared.contains(
            "onOpen = { link -> content.actions.onContactAction(CardContactAction(CardContactKind.Social, link.url.value)) }",
        ))
        assertTrue(source("ui/screens/card/SocialIconGrid.kt").contains("onClick = { onOpen(link) }"))
        val activity = source("MainActivity.kt")
        assertTrue(activity.contains("CardContactKind.Social"))
    }

    @Test
    fun cellsHaveVisibleTwoLineLabelsAccessibleNamesAndTargets() {
        val grid = source("ui/screens/card/SocialIconGrid.kt")
        listOf(
            "maxLines = 2",
            "TextOverflow.Ellipsis",
            "R.string.social_open_description, link.label, link.url.value",
            "contentDescription = description",
            "role = Role.Button",
            "heightIn(min = 48.dp)",
            "Modifier.weight(1f)",
            "contentDescription = null",
            "MaterialTheme.colorScheme.primary",
            "SOCIAL_GRID_MAX_COLUMNS = 4",
        ).forEach { assertTrue(it, grid.contains(it)) }
        assertFalse(grid.contains("AsyncImage"))
        assertFalse(grid.contains("http"))
        assertEquals(48f, SOCIAL_GRID_MIN_TARGET_DP, 0f)
    }

    @Test
    fun professionalLinkListRendersGridForAllSixNonClassicStyles() {
        val shared = source("ui/screens/card/CardSections.kt")
        assertTrue(Regex("""fun ProfessionalLinkList\(content: CardContent, divided: Boolean = false, compact: Boolean = false\)""")
            .containsMatchIn(shared))
        assertTrue(shared.contains("SocialIconGrid("))
        assertFalse(shared.contains("professionalLinkIcon"))
        nonClassic.forEach { card ->
            val text = source("ui/screens/card/$card.kt")
            assertTrue(card, text.contains("ProfessionalLinkList(content"))
        }
    }

    @Test
    fun appearancePreviewUsesSharedRendererSoGridAppearsLive() {
        val editor = source("ui/profile/AppearanceScreen.kt")
        assertTrue(editor.contains("ProfileCard("))
        assertFalse(editor.contains("SocialIconGrid"))
    }

    @Test
    fun classicCardRemainsUnchanged() {
        val file = File("src/main/java/com/mackseoul/vcardnative/ui/screens/card/ClassicCard.kt")
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02X".format(it) }
        assertEquals("B8E7FB0EBC6D606CAEF15F79EF82643B03F15A42C8482F76A0AF185849177150", digest)
        val text = file.readText()
        assertFalse(text.contains("SocialIconGrid"))
        assertFalse(text.contains("ProfessionalLinkList"))
    }

    @Test
    fun shareHostedProfileQrAndWidgetDoNotUseGrid() {
        listOf(
            "ui/screens/share/ShareScreen.kt",
            "ui/screens/share/QuickShareScreens.kt",
            "domain/sharing/QuickShareContactExport.kt",
            "domain/sharing/QrPng.kt",
            "ui/widget/QuickCardWidgetModel.kt",
            "platform/widget/QuickCardWidgetProvider.kt",
        ).forEach { assertFalse(it, source(it).contains("SocialIconGrid")) }
    }
}
