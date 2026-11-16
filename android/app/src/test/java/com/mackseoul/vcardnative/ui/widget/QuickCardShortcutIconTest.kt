package com.mackseoul.vcardnative.ui.widget

import com.mackseoul.vcardnative.MainActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class QuickCardShortcutIconTest {
    @Test fun existingSingleShortcutKeepsIdentityLabelAndContactQrAction() {
        val namespace = "http://schemas.android.com/apk/res/android"
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(File("src/main/res/xml/shortcuts.xml"))
        val shortcuts = document.getElementsByTagName("shortcut")
        assertEquals(1, shortcuts.length)
        val shortcut = shortcuts.item(0) as org.w3c.dom.Element
        assertEquals("show_my_qr_code", shortcut.getAttributeNS(namespace, "shortcutId"))
        assertEquals("@string/shortcut_show_my_qr_code", shortcut.getAttributeNS(namespace, "shortcutShortLabel"))
        assertEquals("@drawable/ic_shortcut_show_qr", shortcut.getAttributeNS(namespace, "icon"))
        val intent = document.getElementsByTagName("intent").item(0) as org.w3c.dom.Element
        assertEquals(MainActivity.ACTION_SHOW_MY_QR_CODE, intent.getAttributeNS(namespace, "action"))
        assertEquals("com.mackseoul.vcardnative.MainActivity", intent.getAttributeNS(namespace, "targetClass"))
        assertTrue(File("src/main/res/drawable/ic_qr.xml").exists())
        val vector = factory.newDocumentBuilder().parse(File("src/main/res/drawable/ic_shortcut_show_qr.xml"))
        assertEquals("vector", vector.documentElement.tagName)
        val paths = vector.getElementsByTagName("path")
        assertTrue((0 until paths.length).any {
            (paths.item(it) as org.w3c.dom.Element).getAttributeNS(namespace, "name") == "central_v"
        })
        val groups = vector.getElementsByTagName("group")
        assertTrue((0 until groups.length).any {
            (groups.item(it) as org.w3c.dom.Element).getAttributeNS(namespace, "name") == "finder_markers"
        })
    }
}