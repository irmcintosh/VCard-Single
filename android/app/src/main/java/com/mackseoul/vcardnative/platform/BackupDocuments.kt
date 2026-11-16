package com.mackseoul.vcardnative.platform

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.mackseoul.vcardnative.data.backup.BackupStaging
import com.mackseoul.vcardnative.data.backup.StagedBackup
import com.mackseoul.vcardnative.domain.backup.BackupException
import java.io.IOException

class BackupDocuments(private val resolver: ContentResolver, private val staging: BackupStaging) {
    fun read(uri: Uri): StagedBackup {
        var stage: StagedBackup? = null
        try {
            return (resolver.openInputStream(uri)
                ?: throw BackupException("Android could not open the backup document.")).use {
                staging.stage(it).also { result -> stage = result }
            }
        } catch (_: IOException) {
            failedRead(stage, "The backup document could not be read. Saved local data is unchanged.")
        } catch (_: SecurityException) {
            failedRead(stage, "Android denied backup document access. Saved local data is unchanged.")
        }
    }

    private fun failedRead(stage: StagedBackup?, message: String): Nothing {
        if (stage != null) {
            try { staging.discard(stage) }
            catch (error: BackupException) { throw BackupException("$message ${error.message}") }
        }
        throw BackupException(message)
    }
    fun write(uri: Uri, stage: StagedBackup) {
        val bytes = staging.read(stage)
        try {
            (resolver.openOutputStream(uri, "wt")
                ?: throw IOException("Destination unavailable.")).use {
                it.write(bytes)
                it.flush()
            }
        } catch (_: IOException) {
            failedWrite(uri)
        } catch (_: SecurityException) {
            failedWrite(uri)
        }
    }

    private fun failedWrite(uri: Uri): Nothing {
        val removed = try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
        throw BackupException(if (removed)
            "Backup writing failed. The incomplete output was removed; saved local data is unchanged."
        else "Backup writing failed. The destination may contain an incomplete file; saved local data is unchanged.")
    }
}
