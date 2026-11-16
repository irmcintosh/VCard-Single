package com.mackseoul.vcardnative.ui.screens.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QrImageExportContractTest {
    private val main = File("src/main/java/com/mackseoul/vcardnative")
    private fun source(path: String) = main.resolve(path).readText()
    private fun section(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue(start, from >= 0)
        val to = text.indexOf(end, from + start.length)
        return if (to < 0) text.substring(from) else text.substring(from, to)
    }

    @Test fun eachApprovedScreenExportsTheExactQrItDisplaysAndTheInlineShareTabHasNoControls() {
        val share = source("ui/screens/share/ShareScreen.kt")
        val home = section(share, "fun HomeScreen(", "fun QrPresentationScreen(")
        assertTrue(home.contains("ContactQrPanel(state.contactQr)"))
        assertFalse(home.contains("QrImageActions"))

        val primary = section(share, "fun QrPresentationScreen(", "fun OnlineCardQrScreen(")
        assertTrue(primary.contains("ContactQrPanel(state.contactQr)"))
        assertTrue(primary.contains("QrImageActions(state.contactQr, QrImageContext.PrimaryContact"))

        val hosted = section(share, "fun OnlineCardQrScreen(", "private fun ContactRows(")
        assertTrue(hosted.contains("key(url)"))
        assertTrue(hosted.contains("ContactQrPanel(qr,"))
        assertTrue(hosted.contains("QrImageActions(qr, QrImageContext.HostedProfile"))
        assertEquals(1, Regex("ContactQrEncoder\\.").findAll(hosted).count())

        val quick = section(source("ui/screens/share/QuickShareScreens.kt"), "fun QuickShareQrScreen(", "private fun QuickShareFieldOption(")
        assertTrue(quick.contains("ContactQrPanel(result.qr)"))
        assertTrue(quick.contains("QrImageActions(result.qr, QrImageContext.CustomShare"))
        assertFalse(quick.contains("QuickShareQrGenerator"))

        val application = source("ui/profile/ProfileScreens.kt")
        assertTrue(application.contains("QrPresentationScreen(state, model, qrImageExport)"))
        assertTrue(application.contains("OnlineCardQrScreen(state, qrImageExport)"))
        assertTrue(application.contains("QuickShareQrScreen(state, model, qrImageExport)"))
        assertTrue(application.contains("HomeScreen(state, model, shareContact)"))
    }

    @Test fun shareTabOpensTheDedicatedContactQrAboveExistingControlsWithoutInlineExport() {
        val home = section(source("ui/screens/share/ShareScreen.kt"), "fun HomeScreen(", "fun QrPresentationScreen(")
        val qr = home.indexOf("ContactQrPanel(state.contactQr)")
        val open = home.indexOf("onClick = model::showShareQr")
        val preview = home.indexOf("onClick = model::reviewContact")
        val send = home.indexOf("model.shareContact(shareContact)")
        assertTrue(listOf(qr, open, preview, send).all { it >= 0 })
        assertTrue(qr < open && open < preview && preview < send)
        val button = home.substring(open, preview)
        assertTrue(button.contains("R.string.open_contact_qr"))
        assertTrue(button.contains("heightIn(min = 48.dp)"))
        assertTrue(button.contains("enabled = !state.busy"))
        assertFalse(home.contains("QrImageActions"))
        assertFalse(home.contains("showCardQr"))
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"open_contact_qr\">Open Contact QR</string>"))
        assertFalse(source("ui/screens/card/MyCardScreen.kt").contains("showShareQr"))
    }

    @Test fun contactQrReturnsToItsOriginAndShortcutExitTakesPrecedence() {
        val model = source("ui/profile/ProfileViewModel.kt")
        val open = section(model, "fun showShareQr()", "fun showOnlineCardQr()")
        assertTrue(open.contains("current.destination != ProfileDestination.Home"))
        assertTrue(open.contains("current.busy"))
        assertTrue(open.contains("current.saved == null"))
        assertTrue(open.contains("current.unavailable != null"))
        assertTrue(open.contains("current.contactQr == null"))
        assertTrue(open.contains("shareQrOrigin = true, shortcutQrOrigin = false"))
        assertTrue(open.contains("select(ProfileDestination.QrPresentation)"))
        assertTrue(section(model, "fun showCardQr()", "fun showShareQr()").contains("copy(shareQrOrigin = false)"))

        val close = section(model, "fun closeQrPresentation(", "private fun openShortcutQr(")
        val closeShortcut = close.indexOf("current.shortcutQrOrigin -> exit()")
        val closeShare = close.indexOf("current.shareQrOrigin -> select(ProfileDestination.Home)")
        val closeCard = close.indexOf("else -> select(ProfileDestination.MyCard)")
        assertTrue(closeShortcut in 0 until closeShare && closeShare < closeCard)

        val back = section(model, "ProfileDestination.QrPresentation -> when {", "ProfileDestination.MyCard -> ")
        val backShortcut = back.indexOf("current.shortcutQrOrigin -> exit()")
        val backShare = back.indexOf("current.shareQrOrigin ->")
        val backCard = back.indexOf("else -> mutableState.value = current.copy(destination = ProfileDestination.MyCard)")
        assertTrue(backShortcut in 0 until backShare && backShare < backCard)
        assertTrue(back.contains("destination = ProfileDestination.Home, shareQrOrigin = false"))

        assertTrue(section(model, "private fun openShortcutQr(", "fun startQuickShare()")
            .contains("shortcutQrOrigin = true, shareQrOrigin = false"))
        assertTrue(model.contains("if (destination != ProfileDestination.QrPresentation && current.shareQrOrigin)"))
        assertTrue(model.contains("shortcutQrOrigin = false, shareQrOrigin = false"))
    }

    @Test fun exportPathNeverReencodesPayloadsAndRunsBlockingWorkOffTheMainThread() {
        val actions = source("ui/screens/share/QrImageActions.kt")
        val gallery = source("platform/sharing/QrImageGallery.kt")
        val png = source("domain/sharing/QrPng.kt")
        for (text in listOf(actions, gallery, png)) {
            assertFalse(text.contains("ContactQrEncoder"))
            assertFalse(text.contains("QuickShareQrGenerator"))
            assertFalse(text.contains("ContactExport.from"))
        }
        assertTrue(actions.contains("val target = qr"))
        assertTrue(actions.contains("enabled = enabled && working == null"))
        assertTrue(actions.contains("heightIn(min = 48.dp)"))
        assertTrue(actions.contains("liveRegion = LiveRegionMode.Polite"))
        assertEquals(2, Regex("withContext\\(Dispatchers\\.Default\\) \\{ QrPng\\.encode\\(qr\\) }").findAll(gallery).count())
        assertTrue(gallery.contains("withContext(Dispatchers.IO)"))
    }

    @Test fun galleryPublishesBeforeReportingSuccessAndRemovesFailedEntries() {
        val gallery = source("platform/sharing/QrImageGallery.kt")
        val pending = gallery.indexOf("put(MediaStore.MediaColumns.IS_PENDING, 1)")
        val write = gallery.indexOf("it.write(png)")
        val publish = gallery.indexOf("put(MediaStore.MediaColumns.IS_PENDING, 0)")
        val unpublished = gallery.indexOf("!= 1) throw IOException")
        val delete = gallery.indexOf("resolver.delete(uri, null, null)")
        val success = gallery.indexOf("storedName(uri) ?: displayName")
        assertTrue(listOf(pending, write, publish, unpublished, delete, success).all { it >= 0 })
        assertTrue(pending < write && write < publish && publish < unpublished && unpublished < success)
        assertTrue(delete in unpublished..success)
        assertTrue(gallery.contains("\"Pictures/VCard Native\""))
        assertTrue(gallery.contains("MediaStore.MediaColumns.RELATIVE_PATH"))
        assertTrue(gallery.contains("QrPng.MIME_TYPE"))
    }

    @Test fun sharingUsesReadOnlyPngGrantsAndNoNewPermissionsOrProviderPaths() {
        val sharing = source("platform/sharing/NativeContactSharing.kt")
        val qrShare = section(sharing, "suspend fun shareQrImage(", "companion object")
        assertTrue(qrShare.contains("QrPng.FILE_NAME, QrPng.MIME_TYPE"))
        assertTrue(qrShare.contains("File(activity.cacheDir, CONTACT_CACHE)"))
        assertTrue(qrShare.contains("Intent.ACTION_SEND"))
        assertTrue(qrShare.contains("Intent.EXTRA_STREAM"))
        assertTrue(qrShare.contains("ClipData.newUri"))
        assertEquals(2, Regex("addFlags\\(Intent\\.FLAG_GRANT_READ_URI_PERMISSION\\)").findAll(qrShare).count())
        assertFalse(sharing.contains("FLAG_GRANT_WRITE_URI_PERMISSION"))
        assertFalse(sharing.contains("FLAG_GRANT_PERSISTABLE_URI_PERMISSION"))
        assertTrue(sharing.contains("TemporaryContactFiles(File(context.cacheDir, CONTACT_CACHE), boot, QrPng.FILE_NAME).cleanup()"))

        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (permission in listOf("WRITE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES", "MANAGE_EXTERNAL_STORAGE")) {
            assertFalse(permission, manifest.contains(permission))
        }
        val paths = File("src/main/res/xml/contact_file_paths.xml").readText()
        assertEquals(2, Regex("<cache-path ").findAll(paths).count())
        assertTrue(paths.contains("path=\"contact-share/payload/\""))
    }
}
