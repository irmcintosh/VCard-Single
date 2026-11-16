package com.mackseoul.vcardnative.data.profile

import com.mackseoul.vcardnative.domain.backup.BackupSnapshot
import com.mackseoul.vcardnative.domain.profile.ImageAsset
import com.mackseoul.vcardnative.domain.profile.ProfileDraft
import com.mackseoul.vcardnative.domain.profile.PublicKeyAsset

/**
 * Public profile repository. The bare constructor operates directly on one A2 store directory and
 * is retained for candidates and unit tests. Production must use [managed], which routes every
 * method through [ProfileOperationCoordinator] under the root's shared lock.
 */
class ProfileRepository private constructor(
    private val bare: ProfileStore?,
    private val bareLock: Any?,
    private val coordinator: ProfileOperationCoordinator?,
    private val onCommittedChange: () -> Unit,
) {
    constructor(files: StoreFiles, onCommittedChange: () -> Unit = {}) :
        this(ProfileStore(files), files.coordinationLock, null, onCommittedChange)

    fun load(): ProfileRead = coordinator?.load() ?: requireNotNull(bare).load()

    fun <T> withSavedSnapshot(expected: RevisionToken, action: (SavedProfile) -> T): T =
        if (coordinator != null) coordinator.withSavedSnapshot(expected, action)
        else requireNotNull(bare).withSavedSnapshot(expected, action)

    /**
     * Runs [action] under the shared, reentrant repository lock after pending operations have been
     * reconciled. Ordinary unavailable live data does not prevent it; a pending or blocked
     * operation record throws [StoreException].
     */
    fun <T> withFileOperation(action: () -> T): T =
        if (coordinator != null) coordinator.withFileOperation(action)
        else synchronized(requireNotNull(bareLock)) { action() }

    fun readImage(asset: ImageAsset): ByteArray = coordinator?.readImage(asset) ?: requireNotNull(bare).readImage(asset)

    fun readPublicKey(asset: PublicKeyAsset): ByteArray =
        coordinator?.readPublicKey(asset) ?: requireNotNull(bare).readPublicKey(asset)

    fun backupSnapshot(): BackupSnapshot = coordinator?.backupSnapshot() ?: requireNotNull(bare).backupSnapshot()

    fun restore(snapshot: BackupSnapshot, expected: RevisionToken?): SavedProfile =
        committed { coordinator?.restore(snapshot, expected) ?: requireNotNull(bare).restore(snapshot, expected) }

    fun save(draft: ProfileDraft, expected: RevisionToken?): SavedProfile =
        committed { coordinator?.save(draft, expected) ?: requireNotNull(bare).save(draft, expected) }

    fun delete(expected: RevisionToken) {
        committed {
            if (coordinator != null) coordinator.delete(expected) else requireNotNull(bare).delete(expected)
        }
    }

    /**
     * Replaces an unavailable live store, identified by the [recoveryToken] from
     * [ProfileRead.Unavailable], with a separately built and validated candidate. The unavailable
     * store is retained as private evidence. Only managed repositories support recovery.
     */
    fun recover(snapshot: BackupSnapshot, recoveryToken: String, backupSha256: String): SavedProfile =
        committed {
            coordinator?.recover(snapshot, recoveryToken, backupSha256)
                ?: throw StoreException("Recovery is not available for this storage.")
        }

    private fun <T> committed(operation: () -> T): T {
        val result = operation()
        try {
            onCommittedChange()
        } catch (_: Exception) {
        }
        return result
    }

    companion object {
        /** [root] must be the trusted Context.noBackupFilesDir; unrelated children are never enumerated. */
        fun managed(root: StoreFiles, onCommittedChange: () -> Unit = {}): ProfileRepository =
            ProfileRepository(null, null, ProfileOperationCoordinator(root), onCommittedChange)
    }
}
