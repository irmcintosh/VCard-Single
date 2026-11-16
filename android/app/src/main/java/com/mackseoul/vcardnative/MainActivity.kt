package com.mackseoul.vcardnative

import android.graphics.Color
import android.os.Bundle
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.SideEffect
import com.mackseoul.vcardnative.data.profile.ProfileRepository
import com.mackseoul.vcardnative.platform.storage.AndroidStoreFiles
import com.mackseoul.vcardnative.platform.storage.SharedPreferencesQuickShareSelectionStore
import com.mackseoul.vcardnative.platform.ImageSelectionReader
import com.mackseoul.vcardnative.platform.PublicKeyDocuments
import com.mackseoul.vcardnative.platform.BackupDocuments
import com.mackseoul.vcardnative.data.backup.BackupStaging
import com.mackseoul.vcardnative.platform.sharing.NativeContactSharing
import com.mackseoul.vcardnative.platform.sharing.NativeQrImageExport
import com.mackseoul.vcardnative.platform.sharing.QrImageGallery
import com.mackseoul.vcardnative.ui.screens.card.CardContactAction
import com.mackseoul.vcardnative.ui.screens.card.CardContactKind
import androidx.compose.runtime.remember
import com.mackseoul.vcardnative.domain.profile.Appearance
import com.mackseoul.vcardnative.domain.profile.Theme
import com.mackseoul.vcardnative.ui.profile.ProfileApplication
import com.mackseoul.vcardnative.ui.profile.ProfileViewModel
import java.io.File
import com.mackseoul.vcardnative.ui.theme.VCardTheme

class MainActivity : ComponentActivity() {
    private var profileViewModel: ProfileViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val backupStaging = BackupStaging(File(applicationContext.noBackupFilesDir, "backup-work")) { directory, count ->
            val stats = android.os.StatFs(directory.path)
            val unit = stats.blockSizeLong
            val allocation = Math.multiplyExact((count.toLong() + unit - 1) / unit, unit)
            if (stats.availableBytes < allocation)
                throw com.mackseoul.vcardnative.domain.backup.BackupException("Not enough internal storage to stage the backup.")
        }
        val backupDocuments = BackupDocuments(applicationContext.contentResolver, backupStaging)
        val model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == ProfileViewModel::class.java)
                return modelClass.cast(
                    ProfileViewModel(
                        createRepository = {
                            ProfileRepository.managed(AndroidStoreFiles(applicationContext.noBackupFilesDir),
                                onCommittedChange = {
                                    com.mackseoul.vcardnative.platform.widget.QuickCardWidgetProvider
                                        .requestRefresh(applicationContext)
                                })
                        },
                        readSelectedImage = ImageSelectionReader(applicationContext.contentResolver)::read,
                        cleanupContactFiles = { NativeContactSharing.cleanup(applicationContext) },
                        readSelectedPublicKey = PublicKeyDocuments(applicationContext.contentResolver)::read,
                        backupStaging = backupStaging,
                        readSelectedBackup = backupDocuments::read,
                        writeSelectedBackup = backupDocuments::write,
                            quickShareSelectionStore = SharedPreferencesQuickShareSelectionStore(applicationContext),
                    ),
                )!!
            }
        })[ProfileViewModel::class.java]
        profileViewModel = model
        consumeShortcutIntent(intent, model)
        setContent {
            val state = model.state.collectAsStateWithLifecycle().value
            val appearance = state.saved?.data?.appearance ?: Appearance()
            SideEffect {
                val style = if (appearance.theme == Theme.dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            VCardTheme(appearance = appearance) {
                val sharing = remember { NativeContactSharing(this@MainActivity) }
                val keyDocuments = remember { PublicKeyDocuments(applicationContext.contentResolver) }
                val qrImageExport = remember {
                    NativeQrImageExport(QrImageGallery(applicationContext.contentResolver), sharing)
                }
                ProfileApplication(
                    state, model, ::finish, sharing::share, sharing::sharePublicKey, keyDocuments::write,
                    ::openContactAction, qrImageExport,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        profileViewModel?.let { consumeShortcutIntent(intent, it) }
    }

    private fun consumeShortcutIntent(intent: Intent, model: ProfileViewModel) {
        if (intent.action == ACTION_WIDGET_MENU_SELECTION) {
            val destination = com.mackseoul.vcardnative.ui.widget.QuickCardWidgetMenuDestination
                .fromRequestKey(intent.getStringExtra(EXTRA_WIDGET_MENU_DESTINATION))
            if (destination != null) model.requestDestinationFromWidget(destination)
            setIntent(Intent(intent).apply { action = null; removeExtra(EXTRA_WIDGET_MENU_DESTINATION) })
            return
        }
        if (intent.action == ACTION_OPEN_MY_CARD_FROM_WIDGET) {
            model.requestMyCardFromWidget()
            setIntent(Intent(intent).apply { action = null })
            return
        }
        if (intent.action != ACTION_SHOW_MY_QR_CODE) return
        model.requestPrimaryQrFromShortcut()
        setIntent(Intent(intent).apply { action = null })
    }

    private fun openContactAction(action: CardContactAction) {
        val browserAction = action.kind in setOf(CardContactKind.Website, CardContactKind.Social,
            CardContactKind.HostedProfile)
        val candidate = if (browserAction) {
            com.mackseoul.vcardnative.domain.profile.ProfileValidator.browserUrlCandidate(
                action.destination, action.kind != CardContactKind.Website,
            ) ?: run {
                Toast.makeText(this, R.string.contact_action_failed, Toast.LENGTH_SHORT).show()
                return
            }
        } else action.destination
        val intent = when (action.kind) {
            CardContactKind.Call -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", action.destination, null))
            CardContactKind.Message -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", action.destination, null))
            CardContactKind.Email -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", action.destination, null))
            CardContactKind.Website, CardContactKind.Social, CardContactKind.HostedProfile ->
                Intent(Intent.ACTION_VIEW, Uri.parse(candidate)).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.contact_action_no_handler, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.contact_action_failed, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_SHOW_MY_QR_CODE = "com.mackseoul.vcardnative.action.SHOW_MY_QR_CODE"
        const val ACTION_OPEN_MY_CARD_FROM_WIDGET = "com.mackseoul.vcardnative.action.OPEN_MY_CARD_FROM_WIDGET"
        const val ACTION_WIDGET_MENU_SELECTION = "com.mackseoul.vcardnative.action.WIDGET_MENU_SELECTION"
        const val EXTRA_WIDGET_MENU_DESTINATION = "widget-menu-destination"
    }
}
