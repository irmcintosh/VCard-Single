package com.mackseoul.vcardnative.ui.profile

import com.mackseoul.vcardnative.domain.profile.ProfileField
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileEditorTabTest {
    @Test
    fun allProfileFieldsBelongToTheirEditorTab() {
        val details = setOf(
            ProfileField.FullName, ProfileField.FirstName, ProfileField.MiddleName, ProfileField.LastName,
            ProfileField.ProfessionalTitle, ProfileField.Organization,
            ProfileField.Biography, ProfileField.Location,
        )
        val contact = setOf(
            ProfileField.Phone, ProfileField.Email, ProfileField.Website,
            ProfileField.LinkedIn, ProfileField.GitHub, ProfileField.OtherProfessional,
        )
        val advanced = setOf(
            ProfileField.HostedProfileUrl, ProfileField.PublicKey,
            ProfileField.ProfileImage, ProfileField.CoverImage,
        )

        assertEquals(ProfileField.entries.toSet(), details + contact + advanced)
        details.forEach { assertEquals(ProfileEditorTab.Details, it.editorTab()) }
        contact.forEach { assertEquals(ProfileEditorTab.Contact, it.editorTab()) }
        advanced.forEach { assertEquals(ProfileEditorTab.Advanced, it.editorTab()) }
    }
}
