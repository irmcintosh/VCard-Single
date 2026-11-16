package com.mackseoul.vcardnative.ui.profile

import com.mackseoul.vcardnative.domain.profile.AppData
import com.mackseoul.vcardnative.domain.profile.Metadata
import com.mackseoul.vcardnative.domain.profile.Profile
import com.mackseoul.vcardnative.domain.profile.ProfileDraft
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.profile.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MyCardProjectionTest {
    @Test
    fun orderedCollectionOverridesLegacyPresentationAndKeepsDuplicatePlatforms() {
        val first = com.mackseoul.vcardnative.domain.profile.SocialProfile(
            UUID.randomUUID(), "linkedin", "Work", "HTTPS://EXAMPLE.COM/Work", true, false,
        )
        val second = first.copy(id = UUID.randomUUID(), label = "Personal")
        val hidden = first.copy(id = UUID.randomUUID(), label = "Hidden", showOnCard = false)
        val unsafe = first.copy(id = UUID.randomUUID(), label = "Unsafe", url = "http://example.com/social")
        val base = data(emptyMap())
        val card = base.copy(profile = base.profile.copy(
            socialProfiles = listOf(first, hidden, second, unsafe),
        )).toMyCard().profile!!
        assertEquals(listOf("Work", "Personal"), card.visibleProfessionalLinks.map { it.label })
        assertEquals(listOf("linkedin", "linkedin"), card.visibleProfessionalLinks.map { it.platform })
        assertEquals(first.url, card.visibleProfessionalLinks.first().url.value)
        assertEquals("https://example.com/hosted", card.hostedProfileUrl)
        assertEquals(first.url, base.copy(profile = base.profile.copy(
            socialProfiles = listOf(first),
        )).toMyCard().profile!!.professionalLinks.first().url.value)
    }

    @Test
    fun unsafeHostedProfileIsNotProjected() {
        val base = data(emptyMap())
        val card = base.copy(profile = base.profile.copy(values = base.profile.values +
            (ProfileField.HostedProfileUrl to "http://example.com/hosted"))).toMyCard().profile!!
        assertNull(card.hostedProfileUrl)
    }

    private fun data(visibility: Map<ProfileField, Visibility>) = AppData(
        profile = Profile(
            profileId = UUID.randomUUID(),
            values = mapOf(
                ProfileField.FullName to "Identity test fixture",
                ProfileField.Biography to "Biography fixture",
                ProfileField.Organization to "Organization fixture",
                ProfileField.Email to "test@example.com",
                ProfileField.LinkedIn to "https://example.com/",
                ProfileField.HostedProfileUrl to "https://example.com/hosted",
            ),
            visibility = ProfileDraft.empty().visibility + visibility,
        ),
        metadata = Metadata("2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
    )

    @Test
    fun hiddenFieldsAreNotDisplayedEvenWhenExportEnabled() {
        val profile = requireNotNull(data(mapOf(
            ProfileField.Biography to Visibility(false, true),
            ProfileField.Organization to Visibility(false, false),
            ProfileField.Email to Visibility(false, true),
            ProfileField.LinkedIn to Visibility(false, true),
        )).toMyCard().profile)
        assertEquals("Identity test fixture", profile.fullName)
        assertNull(profile.biography)
        assertNull(profile.organization)
        assertFalse(profile.email?.canDisplay == true)
        assertTrue(profile.visibleProfessionalLinks.isEmpty())
    }

    @Test
    fun cardDisplayDoesNotDependOnContactExportPermission() {
        val profile = requireNotNull(data(emptyMap()).toMyCard().profile)
        assertEquals("Biography fixture", profile.biography)
        assertEquals("Organization fixture", profile.organization)
        assertEquals("test@example.com", profile.email?.value)
        assertTrue(profile.email?.canDisplay == true)
        assertEquals("https://example.com/", profile.visibleProfessionalLinks.single().url.value)
    }

    @Test
    fun visibleContactInformationRequiresValidValuesButNotExportPermission() {
        val source = data(mapOf(
            ProfileField.Phone to Visibility(showOnCard = true, includeInContactExport = true),
            ProfileField.Email to Visibility(showOnCard = true, includeInContactExport = true),
            ProfileField.Website to Visibility(showOnCard = true, includeInContactExport = false),
        ))
        val projection = source.copy(
            profile = source.profile.copy(
                values = source.profile.values + mapOf(
                    ProfileField.Phone to "not a phone",
                    ProfileField.Email to "not an email",
                    ProfileField.Website to "https://example.com/contact",
                ),
            ),
        ).toMyCard().profile!!

        assertFalse(projection.phone?.canDisplay == true)
        assertFalse(projection.email?.canDisplay == true)
        assertEquals("https://example.com/contact", projection.website?.value)
        assertTrue(projection.website?.canDisplay == true)
    }

    @Test
    fun contactRowsHideEmptyAndVisibilityDisabledValuesWithoutChangingExportChoice() {
        val base = data(emptyMap())
        val source = base.copy(
            profile = base.profile.copy(
                values = base.profile.values + mapOf(
                    ProfileField.Phone to "+1 202 555 0123",
                    ProfileField.Email to "",
                    ProfileField.Website to "https://example.com/a/very/long/contact/path",
                ),
                visibility = ProfileDraft.empty().visibility + mapOf(
                    ProfileField.Phone to Visibility(false, true),
                    ProfileField.Email to Visibility(true, false),
                    ProfileField.Website to Visibility(true, true),
                ),
            ),
        ).toMyCard().profile!!

        assertFalse(source.phone?.canDisplay == true)
        assertFalse(source.email?.canDisplay == true)
        assertEquals("https://example.com/a/very/long/contact/path", source.website?.value)
        assertTrue(source.website?.canDisplay == true)
    }
}
