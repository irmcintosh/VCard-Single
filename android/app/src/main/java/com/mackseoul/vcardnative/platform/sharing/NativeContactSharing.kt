package com.mackseoul.vcardnative.platform.sharing

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.Context
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mackseoul.vcardnative.R
import com.mackseoul.vcardnative.data.sharing.TemporaryContactFiles
import com.mackseoul.vcardnative.domain.sharing.ContactExport
import com.mackseoul.vcardnative.domain.sharing.ContactExportException
import com.mackseoul.vcardnative.domain.sharing.PublicKeyExport
import com.mackseoul.vcardnative.domain.sharing.QrPng
import com.mackseoul.vcardnative.domain.keys.PublicKeyException
import com.mackseoul.vcardnative.data.keys.PublicKeyValidator
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class NativeContactSharing(
    private val activity: Activity,
    private val launch: (Intent) -> Unit = activity::startActivity,
) {
    fun share(export: ContactExport) {
        shareBytes(export.bytes, ContactExport.FILE_NAME, ContactExport.MIME_TYPE,
            R.string.share_contact_file, "contact-share")
    }

    fun sharePublicKey(export: PublicKeyExport) {
        val status = PublicKeyValidator.validateStored(export.bytes, export.asset)
        if (!status.usable) throw PublicKeyException(status.explanation ?: "The public key is not currently usable.")
        try {
            shareBytes(export.bytes, PublicKeyExport.FILE_NAME, PublicKeyExport.MIME_TYPE,
                R.string.share_public_key, "public-key-share")
        } catch (error: ContactExportException) {
            throw PublicKeyException("Public-key handoff did not complete. ${error.message}")
        }
    }

    private fun shareBytes(bytes: ByteArray, fileName: String, mime: String, title: Int, cacheName: String) {
        val boot = try {
            Settings.Global.getInt(activity.contentResolver, Settings.Global.BOOT_COUNT).toLong()
        } catch (_: Settings.SettingNotFoundException) {
            throw ContactExportException("Android handoff lifetime could not be verified. No file was shared.")
        }
        val files = TemporaryContactFiles(File(activity.cacheDir, cacheName), boot, fileName)
        var file: File? = null
        try {
            if (activity.isFinishing || activity.isDestroyed) {
                throw ContactExportException("The screen changed before sharing. Review the saved data again.")
            }
            file = files.create(bytes)
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.contact-files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(activity.contentResolver, fileName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, activity.getString(title)).apply {
                clipData = send.clipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (activity.isFinishing || activity.isDestroyed) {
                files.discard(file)
                throw ContactExportException("The screen changed before sharing. Review the saved data again.")
            }
            launch(chooser)
        } catch (_: ActivityNotFoundException) {
            file?.let { discard(files, it) }
            throw ContactExportException("No Android sharing handler is available. The file was not handed off.")
        } catch (_: SecurityException) {
            file?.let { discard(files, it) }
            throw ContactExportException("Android denied file sharing. Review the saved data and try again.")
        } catch (_: IOException) {
            throw ContactExportException("The temporary sharing file could not be prepared or cleaned up. No delivery is confirmed.")
        } catch (_: IllegalArgumentException) {
            file?.let { discard(files, it) }
            throw ContactExportException("The sharing URI could not be prepared. No file was handed off.")
        }
    }

    private fun discard(files: TemporaryContactFiles, file: File) {
        try {
            files.discard(file)
        } catch (_: IOException) {
            throw ContactExportException("Sharing did not complete and temporary-file cleanup failed. No delivery is confirmed.")
        }
    }

    /**
     * Shares an already encoded QR PNG through the existing read-only contact FileProvider path.
     * The temporary file is written off the main thread; the chooser is launched from the caller's (main) context.
     */
    suspend fun shareQrImage(png: ByteArray) {
        if (!QrPng.isEncodedPng(png)) throw ContactExportException("The QR image could not be prepared. Nothing was shared.")
        if (activity.isFinishing || activity.isDestroyed) {
            throw ContactExportException("The screen changed before sharing. Review the saved data again.")
        }
        val file = withContext(Dispatchers.IO + NonCancellable) {
            val boot = try {
                Settings.Global.getInt(activity.contentResolver, Settings.Global.BOOT_COUNT).toLong()
            } catch (_: Settings.SettingNotFoundException) {
                throw ContactExportException("Android handoff lifetime could not be verified. No file was shared.")
            }
            val files = TemporaryContactFiles(File(activity.cacheDir, CONTACT_CACHE), boot, QrPng.FILE_NAME)
            try {
                files to files.create(png)
            } catch (_: IOException) {
                throw ContactExportException("The temporary sharing file could not be prepared or cleaned up. No delivery is confirmed.")
            } catch (_: SecurityException) {
                throw ContactExportException("Android denied file sharing. Review the saved data and try again.")
            }
        }
        try {
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            withContext(Dispatchers.IO + NonCancellable) { runCatching { file.first.discard(file.second) } }
            throw cancelled
        }
        handOff(file.first, file.second, QrPng.FILE_NAME, QrPng.MIME_TYPE, R.string.share_qr_image_title)
    }

    private fun handOff(files: TemporaryContactFiles, file: File, fileName: String, mime: String, title: Int) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.contact-files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(activity.contentResolver, fileName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, activity.getString(title)).apply {
                clipData = send.clipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (activity.isFinishing || activity.isDestroyed) {
                files.discard(file)
                throw ContactExportException("The screen changed before sharing. Review the saved data again.")
            }
            launch(chooser)
        } catch (_: ActivityNotFoundException) {
            discard(files, file)
            throw ContactExportException("No Android sharing handler is available. The file was not handed off.")
        } catch (_: SecurityException) {
            discard(files, file)
            throw ContactExportException("Android denied file sharing. Review the saved data and try again.")
        } catch (_: IOException) {
            throw ContactExportException("The temporary sharing file could not be prepared or cleaned up. No delivery is confirmed.")
        } catch (_: IllegalArgumentException) {
            discard(files, file)
            throw ContactExportException("The sharing URI could not be prepared. No file was handed off.")
        }
    }

    companion object {
        private const val CONTACT_CACHE = "contact-share"

        fun cleanup(context: Context) {
            try {
                val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong()
                TemporaryContactFiles(File(context.cacheDir, "contact-share"), boot).cleanup()
                TemporaryContactFiles(File(context.cacheDir, CONTACT_CACHE), boot, QrPng.FILE_NAME).cleanup()
                TemporaryContactFiles(File(context.cacheDir, "public-key-share"), boot, PublicKeyExport.FILE_NAME).cleanup()
            } catch (_: Settings.SettingNotFoundException) {
                throw ContactExportException("Temporary sharing-file cleanup is deferred because Android handoff lifetime could not be verified.")
            } catch (_: IOException) {
                throw ContactExportException("Unused temporary sharing files could not be cleaned up. Saved profile data is unchanged.")
            } catch (_: SecurityException) {
                throw ContactExportException("Android denied temporary sharing-file cleanup. Saved profile data is unchanged.")
            }
        }
    }
}
