package com.mackseoul.vcardnative.ui.screens.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class QuickSharePolishContractTest {
    private val main = File("src/main/java/com/mackseoul/vcardnative")
    private fun source(path: String) = main.resolve(path).readText().replace("\r\n", "\n")
    private fun section(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue(start, from >= 0)
        val to = text.indexOf(end, from + start.length)
        assertTrue(end, to >= 0)
        return text.substring(from, to)
    }
    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }

    private val screens get() = source("ui/screens/share/QuickShareScreens.kt")
    private val model get() = source("ui/profile/ProfileViewModel.kt")

    @Test
    fun qrScreenSectionAndFunctionBoundariesAreUnchanged() {
        val text = screens
        val qr = text.indexOf("fun QuickShareQrScreen(")
        val option = text.indexOf("private fun QuickShareFieldOption(")
        assertTrue(text.indexOf("internal fun QuickShareSelectInformationScreen(") in 0 until qr)
        assertTrue(text.indexOf("internal fun QuickShareReviewScreen(") in 0 until qr)
        assertTrue(qr in 0 until option)
        assertEquals(
            "06DD832815BCF4AA31BB99103AA4AA42F8EBBBD0AEB9C3C5123BAD69C695D0B0",
            sha256(section(text, "fun QuickShareQrScreen(", "private fun QuickShareFieldOption(")),
        )
    }

    @Test
    fun reviewRendersTheExportPreviewInsteadOfRawSelectedValues() {
        val review = section(screens, "internal fun QuickShareReviewScreen(", "internal fun QuickShareQrScreen(")
        assertTrue(review.contains("val preview = session.preview"))
        assertTrue(review.contains("preview.rows.forEach"))
        assertTrue(review.contains("QuickSharePreviewWarning(preview.selectedFields)"))
        assertTrue(review.contains("alwaysIncluded = true"))
        assertFalse(review.contains("selectedValues"))
        assertTrue(review.contains("onClick = model::doneQuickShare"))
        assertTrue(review.contains("onClick = model::initiateQuickShareContactShare"))
        assertTrue(review.contains("onClick = model::generateQuickShareQr"))
        assertTrue(review.contains("back = model::backToQuickShareSelection"))
    }

    @Test
    fun selectScreenShowsAllFourChoicesSummaryAndPreservesActions() {
        val select = section(screens, "internal fun QuickShareSelectInformationScreen(", "internal fun QuickShareReviewScreen(")
        assertTrue(select.contains("QuickShareFieldChoices.from(savedValues, session.selectedFields)"))
        assertTrue(select.contains("QuickShareSelectionSummary("))
        assertTrue(select.contains("QuickShareFullName(fullName, alwaysIncluded = true)"))
        assertFalse(select.contains("session.preview"))
        assertFalse(select.contains("QuickShareValue("))
        listOf(
            "model::selectAllQuickShareFields", "model::clearQuickShareFields", "model::cancelQuickShare",
            "model::continueQuickShareToReview", "model.toggleQuickShareField(choice.field)", "onClick = onEditProfile",
            "back = { model.requestBack {} }",
        ).forEach { assertTrue(it, select.contains(it)) }
    }

    @Test
    fun fieldRowsAreSingleAccessibleTogglesWithoutNestedTextSelection() {
        val option = section(screens, "private fun QuickShareFieldOption(", "private fun QuickShareSelectionSummary(")
        assertFalse(option.contains("SelectionContainer"))
        assertTrue(option.contains("role = Role.Checkbox"))
        assertTrue(option.contains("enabled = enabled && available"))
        assertTrue(option.contains("onCheckedChange = null"))
        assertTrue(option.contains("R.string.quick_share_field_accessibility"))
        assertTrue(option.contains("R.string.quick_share_field_unavailable_accessibility"))
        assertTrue(option.contains("R.string.quick_share_not_in_profile"))
        assertTrue(option.contains("heightIn(min = 56.dp)"))
    }

    @Test
    fun actionsKeepMinimumTouchTargetsAndStackWhenLabelsDoNotFit() {
        val text = screens
        val pair = section(text, "private fun QuickShareActionPair(", "private fun QuickShareFullName(")
        assertTrue(pair.contains("maxIntrinsicWidth"))
        assertTrue(pair.contains("Constraints.fixedWidth(width)"))
        assertEquals(3, Regex("QuickShareActionPair \\{").findAll(text).count())
        listOf(
            section(text, "internal fun QuickShareSelectInformationScreen(", "internal fun QuickShareReviewScreen("),
            section(text, "internal fun QuickShareReviewScreen(", "internal fun QuickShareQrScreen("),
        ).forEach { screen ->
            assertEquals(
                Regex("Button\\(").findAll(screen).count(),
                Regex("heightIn\\(min = 48\\.dp\\)").findAll(screen).count(),
            )
        }
    }

    @Test
    fun fullNameIsAccessiblyAlwaysIncludedOnSelectAndReviewOnly() {
        val fullName = section(screens, "private fun QuickShareFullName(", "private fun QuickShareValue(")
        assertTrue(fullName.contains("R.string.quick_share_full_name_accessibility"))
        assertTrue(fullName.contains("R.string.quick_share_always_included"))
        assertTrue(fullName.contains("alwaysIncluded: Boolean = false"))
        val qr = section(screens, "fun QuickShareQrScreen(", "private fun QuickShareFieldOption(")
        assertTrue(qr.contains("QuickShareFullName(it)"))
    }

    @Test
    fun viewModelPreviewIsDerivedSessionStateAndSelectedValuesIsUnchanged() {
        val session = section(model, "data class QuickShareSessionState(", "data class ProfileScreenState(")
        assertTrue(session.contains(
            "val selectedValues: Map<ProfileField, String>\n" +
                "        get() = QuickShareSelection.selectedValues(savedProfile.data.profile.values, selectedFields)",
        ))
        assertTrue(session.contains("val preview: QuickSharePreview by lazy(LazyThreadSafetyMode.PUBLICATION)"))
        assertTrue(session.contains("QuickSharePreview.from(savedProfile.data.profile, selectedFields)"))
        assertEquals(3, Regex("QuickSharePreview\\b").findAll(model).count())
    }

    @Test
    fun draftSelectionPathsNeverPersistAndConfirmationPointsAreUnchanged() {
        val drafts = section(model, "fun toggleQuickShareField(", "fun generateQuickShareQr(")
        assertFalse(drafts.contains("replaceConfirmedKeys"))
        assertFalse(drafts.contains("preview"))
        val qr = section(model, "fun generateQuickShareQr(", "fun initiateQuickShareContactShare(")
        assertEquals(1, Regex("replaceConfirmedKeys").findAll(qr).count())
        assertTrue(qr.contains("selectionPersistenceError = persistenceError"))
        val share = section(model, "fun initiateQuickShareContactShare(", "fun takeQuickShareContactHandoff(")
        assertEquals(1, Regex("replaceConfirmedKeys").findAll(share).count())
        assertTrue(share.contains("throw QuickShareSelectionWriteFailure(error)"))
        val helper = source("domain/sharing/QuickSharePreview.kt")
        listOf("replaceConfirmedKeys", "SelectionStore", "PhoneNumbers", "SharedPreferences").forEach {
            assertFalse(it, helper.contains(it))
        }
        assertTrue(helper.contains("QuickShareContactExport.from(profile, selection)"))
    }

    @Test
    fun newStringsAreResourcesNotHardCodedEnglish() {
        val strings = File("src/main/res/values/strings.xml").readText()
        listOf(
            "quick_share_always_included", "quick_share_full_name_accessibility", "quick_share_selected_count",
            "quick_share_not_in_profile", "quick_share_field_unavailable_accessibility", "quick_share_preview_exact",
            "quick_share_preview_unavailable_title", "quick_share_preview_unavailable", "quick_share_field_accessibility",
        ).forEach { assertTrue(it, strings.contains("name=\"$it\"")) }
        assertFalse(screens.contains("\"Always included\""))
        assertFalse(screens.contains("\"Not in saved profile\""))
    }
}
