package com.mackseoul.vcardnative.ui.widget

import com.mackseoul.vcardnative.MainActivity
import com.mackseoul.vcardnative.ui.profile.ProfileDestination
import org.junit.Assert.*
import org.junit.Test

class QuickCardWidgetMenuTest {
    @Test fun widgetLayoutsReserveIndependent48DpMenuTargetWithoutShrinkingText() {
        val namespace = "http://schemas.android.com/apk/res/android"
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for (variant in listOf("compact", "standard", "expanded")) {
            val document = factory.newDocumentBuilder().parse(java.io.File(
                "src/main/res/layout/quick_card_widget_$variant.xml"))
            assertEquals("FrameLayout", document.documentElement.tagName)
            val menu = document.getElementsByTagName("ImageButton").item(0) as org.w3c.dom.Element
            assertEquals("@+id/quick_card_menu", menu.getAttributeNS(namespace, "id"))
            assertEquals("48dp", menu.getAttributeNS(namespace, "layout_width"))
            assertEquals("48dp", menu.getAttributeNS(namespace, "layout_height"))
            assertEquals("@string/quick_card_widget_options", menu.getAttributeNS(namespace, "contentDescription"))
            val body = document.getElementsByTagName("LinearLayout").item(0) as org.w3c.dom.Element
            if (variant == "standard") {
                // Approved refinement: Standard centers its column across the full width; the menu sits top-right.
                assertEquals("", body.getAttributeNS(namespace, "layout_marginEnd"))
                assertEquals("top|end", menu.getAttributeNS(namespace, "layout_gravity"))
            } else {
                assertEquals("48dp", body.getAttributeNS(namespace, "layout_marginEnd"))
            }
            if (variant == "compact") {
                val name = document.getElementsByTagName("TextView").item(1) as org.w3c.dom.Element
                val title = document.getElementsByTagName("TextView").item(2) as org.w3c.dom.Element
                assertEquals("14sp", name.getAttributeNS(namespace, "textSize"))
                assertEquals("12sp", title.getAttributeNS(namespace, "textSize"))
                assertEquals("end", name.getAttributeNS(namespace, "ellipsize"))
                val avatar = document.getElementsByTagName("FrameLayout").item(1) as org.w3c.dom.Element
                assertEquals("24dp", avatar.getAttributeNS(namespace, "layout_width"))
                assertEquals("8dp", body.getAttributeNS(namespace, "paddingStart"))
                assertEquals(32, 120 - 8 - 24 - 8 - 48)
            }
        }
    }

    @Test fun onlyThreeExplicitTabRequestsAreAccepted() {
        assertEquals(ProfileDestination.MyCard, QuickCardWidgetMenuDestination.fromRequestKey("my-card")?.destination)
        assertEquals(ProfileDestination.Home, QuickCardWidgetMenuDestination.fromRequestKey("share")?.destination)
        assertEquals(ProfileDestination.Profile, QuickCardWidgetMenuDestination.fromRequestKey("profile")?.destination)
        for (key in listOf(null, "", "QrPresentation", "quick-share", "editor")) {
            assertNull(QuickCardWidgetMenuDestination.fromRequestKey(key))
        }
    }

    @Test fun bodyMenuAndShortcutIdentitiesAreDistinctForEveryWidget() {
        val identities = listOf(10, 20).flatMap { id ->
            listOf(QuickCardWidgetClickIdentity.body(id), QuickCardWidgetClickIdentity.menu(id))
        }
        assertEquals(4, identities.toSet().size)
        assertEquals("vcardnative://quick-card-widget/10", QuickCardWidgetClickIdentity.body(10))
        assertNotEquals(MainActivity.ACTION_WIDGET_MENU_SELECTION, MainActivity.ACTION_SHOW_MY_QR_CODE)
        assertNotEquals(MainActivity.ACTION_WIDGET_MENU_SELECTION, MainActivity.ACTION_OPEN_MY_CARD_FROM_WIDGET)
    }
}