package com.mackseoul.vcardnative.ui.screens.card

import com.mackseoul.vcardnative.R
import org.junit.Assert.assertEquals
import org.junit.Test

class PrimaryNavigationItemsTest {
    @Test
    fun primaryNavigationUsesMyCardShareProfileOrder() {
        assertEquals(
            listOf(R.string.my_card, R.string.share, R.string.profile),
            primaryNavigationItems.map { it.label },
        )
    }
}
