package com.mackseoul.vcardnative.data.profile

import com.mackseoul.vcardnative.domain.backup.BackupSnapshot
import com.mackseoul.vcardnative.domain.profile.ImageAsset
import com.mackseoul.vcardnative.domain.profile.ProfileDraft
import com.mackseoul.vcardnative.domain.profile.PublicKeyAsset
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Durable operation coordinator for the managed profile store.
 *
 * Root layout (root is the trusted Context.noBackupFilesDir; no other child is ever enumerated):
 *  - store-v1                                live A2 store, format unchanged
 *  - profile-operations\intent|confirm       checksummed, bounded operation records (never replaced)
 *  - profile-operations\evidence\<uuid>      unavailable stores preserved by recovery; no expiry
 *  - profile-operations\candidate\<uuid>     recovery candidate built by the existing restore
 *  - profile-operations\trash\<uuid>         live store atomically unpublished by deletion
 *  - backup-work\<uuid>.archive              unissued backup staging, removed by deletion only
 *
 * Every record is written new, file-synced, atomically renamed, parent-synced and read back before
 * any destructive step. Directory moves are same-filesystem atomic renames with no copy fallback.
 * Any record that is malformed or contradicts the filesystem blocks all use; nothing is reset.
 */
internal class ProfileOperationCoordinator(private val root: StoreFiles) {
    private class Blocked : Exception()
    private class Pending(val kind: String, cause: Throwable) : Exception(cause)

    private sealed interface OperationRecord { val operationId: String }
    private data class DeleteIntent(
        override val operationId: String, val generationId: String, val revision: Long, val liveSha256: String,
    ) : OperationRecord
    private data class RecoverIntent(
        override val operationId: String, val evidenceId: String, val liveSha256: String, val backupSha256: String,
    ) : OperationRecord
    private data class RecoverConfirm(
        override val operationId: String, val evidenceId: String, val liveSha256: String, val backupSha256: String,
        val candidateSha256: String, val generationId: String, val revision: Long,
    ) : OperationRecord

    private val lock: Any get() = root.coordinationLock
    private val operationsKey: Any get() = ScopedStoreFiles(root, OPS).uncertaintyKey
    private fun liveFiles() = ScopedStoreFiles(root, LIVE)
    private fun live() = ProfileStore(liveFiles())

    // ---------------------------------------------------------------- public operations

    fun load(): ProfileRead = synchronized(lock) {
        try {
            reconcile()
        } catch (_: Blocked) {
            return@synchronized ProfileRead.Unavailable(BLOCKED, null, "blocked")
        } catch (e: Pending) {
            return@synchronized ProfileRead.Unavailable(pendingMessage(e.kind), null, e.kind)
        } catch (_: Exception) {
            return@synchronized ProfileRead.Unavailable(ProfileStore.UNAVAILABLE)
        }
        val present = try { root.exists(LIVE) } catch (_: Exception) {
            return@synchronized ProfileRead.Unavailable(ProfileStore.UNAVAILABLE)
        }
        if (!present) return@synchronized ProfileRead.Empty
        when (val read = live().load()) {
            is ProfileRead.Unavailable -> ProfileRead.Unavailable(read.message, recoveryToken())
            else -> read
        }
    }

    fun <T> withFileOperation(action: () -> T): T = gated { action() }

    fun <T> withSavedSnapshot(expected: RevisionToken, action: (SavedProfile) -> T): T = gated {
        if (!livePresent()) throw StoreException("Save a profile before exporting a contact.")
        live().withSavedSnapshot(expected, action)
    }

    fun readImage(asset: ImageAsset): ByteArray = gated {
        if (!livePresent()) throw StoreException(ProfileStore.UNAVAILABLE)
        live().readImage(asset)
    }

    fun readPublicKey(asset: PublicKeyAsset): ByteArray = gated {
        if (!livePresent()) throw StoreException(ProfileStore.UNAVAILABLE)
        live().readPublicKey(asset)
    }

    fun backupSnapshot(): BackupSnapshot = gated {
        if (!livePresent()) throw StoreException("Save a profile before creating a backup.")
        live().backupSnapshot()
    }

    fun save(draft: ProfileDraft, expected: RevisionToken?): SavedProfile = gated {
        prepareLive(expected, "The profile could not be saved. Your draft has been kept.")
        live().save(draft, expected)
    }

    fun restore(snapshot: BackupSnapshot, expected: RevisionToken?): SavedProfile = gated {
        prepareLive(expected, "Restore did not commit. Your previously saved data is preserved.")
        live().restore(snapshot, expected)
    }

    fun delete(expected: RevisionToken): Unit = gated {
        if (!livePresent()) throw StoreException("There is no saved profile to delete.")
        val saved = when (val read = live().load()) {
            is ProfileRead.Ready -> read.saved
            ProfileRead.Empty -> throw StoreException("There is no saved profile to delete.")
            is ProfileRead.Unavailable -> throw StoreException(read.message)
        }
        if (saved.token != expected) throw StaleRevisionException()
        val intent = try {
            DeleteIntent(randomId(), expected.generationId, expected.revision, fingerprint(LIVE))
        } catch (_: Exception) {
            throw StoreException(ProfileStore.UNAVAILABLE)
        }
        try {
            ensureOperationDirectories()
            writeRecord(INTENT, INTENT_TMP, encode(intent))
        } catch (_: Exception) {
            throw StoreException(if (recordMayExist(INTENT)) PENDING_DELETE
            else "Profile deletion could not start. The saved profile is unchanged.")
        }
        try {
            resumeDelete(intent)
        } catch (_: Blocked) {
            throw StoreException(BLOCKED)
        } catch (_: Exception) {
            throw StoreException(PENDING_DELETE)
        }
    }

    fun recover(snapshot: BackupSnapshot, recoveryToken: String, backupSha256: String): SavedProfile = gated {
        if (!HEX64.matches(backupSha256)) throw StoreException("The backup identity is invalid. Saved data is unchanged.")
        if (!livePresent() || live().load() !is ProfileRead.Unavailable) throw StoreException(STALE_RECOVERY)
        val liveSha256 = try { fingerprint(LIVE) } catch (_: Exception) { throw StoreException(ProfileStore.UNAVAILABLE) }
        if (!MessageDigest.isEqual(tokenFor(liveSha256).toByteArray(), recoveryToken.toByteArray()))
            throw StoreException(STALE_RECOVERY)
        val operationId = randomId()
        val intent = RecoverIntent(operationId, randomId(), liveSha256, backupSha256)
        try {
            ensureOperationDirectories()
            writeRecord(INTENT, INTENT_TMP, encode(intent))
        } catch (_: Exception) {
            throw StoreException(if (recordMayExist(INTENT)) PENDING_RECOVER
            else "Recovery could not start. Saved data is unchanged.")
        }
        val candidate = "$CANDIDATE\\$operationId"
        val confirm = try {
            root.createDirectory(candidate)
            root.syncDirectory(CANDIDATE)
            val saved = ProfileStore(ScopedStoreFiles(root, candidate)).restore(snapshot, null)
            val verified = ProfileStore(ScopedStoreFiles(root, candidate)).load()
            if (verified !is ProfileRead.Ready || verified.saved != saved) throw IOException("Candidate did not verify.")
            RecoverConfirm(operationId, intent.evidenceId, liveSha256, backupSha256, fingerprint(candidate),
                saved.token.generationId, saved.token.revision).also { writeRecord(CONFIRM, CONFIRM_TMP, encode(it)) }
        } catch (error: Exception) {
            if (recordMayExist(CONFIRM)) throw StoreException(PENDING_RECOVER)
            try { abortRecover(intent) } catch (_: Blocked) { throw StoreException(BLOCKED) } catch (_: Exception) {
                throw StoreException(PENDING_RECOVER)
            }
            throw StoreException(when {
                error is ValidationException || error !is StoreException -> CANDIDATE_FAILED
                else -> error.message ?: CANDIDATE_FAILED
            })
        }
        try {
            completeRecover(confirm, hasIntent = true)
        } catch (_: Blocked) {
            throw StoreException(BLOCKED)
        } catch (_: Exception) {
            throw StoreException(PENDING_RECOVER)
        }
    }

    // ---------------------------------------------------------------- gate and reconciliation

    private inline fun <T> gated(action: () -> T): T = synchronized(lock) {
        try {
            reconcile()
        } catch (_: Blocked) {
            throw StoreException(BLOCKED)
        } catch (e: Pending) {
            throw StoreException(pendingMessage(e.kind))
        } catch (_: Exception) {
            throw StoreException(ProfileStore.UNAVAILABLE)
        }
        action()
    }

    private fun livePresent(): Boolean = try { root.exists(LIVE) } catch (_: Exception) {
        throw StoreException(ProfileStore.UNAVAILABLE)
    }

    private fun prepareLive(expected: RevisionToken?, message: String) {
        if (livePresent()) return
        if (expected != null) throw StaleRevisionException()
        try {
            root.createDirectory(LIVE)
            root.syncDirectory("")
        } catch (_: Exception) {
            throw StoreException(message)
        }
    }

    /** Runs before any live creation, load, cleanup or export. Never resets anything. */
    private fun reconcile() {
        if (StoreLocks.isRemovalUnsynced(operationsKey)) {
            root.syncDirectory(OPS)
            StoreLocks.clearRemovalUnsynced(operationsKey)
        }
        if (!root.exists(OPS)) return
        ensure(root.isDirectory(OPS))
        val entries = root.list(OPS)
        ensure(entries.all { it in OPS_ENTRIES })
        fun children(path: String): Set<String> {
            if (path.substringAfterLast('\\') !in entries) return emptySet()
            ensure(root.isDirectory(path))
            return root.list(path).also { names -> ensure(names.all { isUuid(it) }) }
        }
        children(EVIDENCE)
        val candidates = children(CANDIDATE)
        val trash = children(TRASH)
        val intent = if ("intent" in entries) readRecord(INTENT) else null
        val confirm = if ("confirm" in entries) readRecord(CONFIRM) else null
        ensure(intent !is RecoverConfirm && (confirm == null || confirm is RecoverConfirm))
        when {
            intent == null && confirm == null -> ensure(candidates.isEmpty() && trash.isEmpty())
            intent is DeleteIntent && confirm == null ->
                ensure(candidates.isEmpty() && trash.all { it == intent.operationId })
            intent is RecoverIntent -> {
                ensure(trash.isEmpty() && candidates.all { it == intent.operationId })
                if (confirm != null) {
                    confirm as RecoverConfirm
                    ensure(confirm.operationId == intent.operationId && confirm.evidenceId == intent.evidenceId &&
                        confirm.liveSha256 == intent.liveSha256 && confirm.backupSha256 == intent.backupSha256)
                }
            }
            intent == null && confirm is RecoverConfirm -> ensure(candidates.isEmpty() && trash.isEmpty())
            else -> throw Blocked()
        }
        for (name in listOf("intent.tmp", "confirm.tmp")) if (name in entries) {
            // An unrenamed record was never authoritative.
            ensure(!root.isDirectory("$OPS\\$name"))
            root.delete("$OPS\\$name")
            root.syncDirectory(OPS)
        }
        when {
            intent is DeleteIntent -> pending("delete") { resumeDelete(intent) }
            intent is RecoverIntent && confirm == null -> pending("recover") { abortRecover(intent) }
            confirm is RecoverConfirm -> pending("recover") { completeRecover(confirm, hasIntent = intent != null) }
        }
    }

    private inline fun pending(kind: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Blocked) {
            throw e
        } catch (e: Exception) {
            throw Pending(kind, e)
        }
    }

    // ---------------------------------------------------------------- deletion

    private fun resumeDelete(intent: DeleteIntent) {
        val trash = "$TRASH\\${intent.operationId}"
        if (root.exists(LIVE)) {
            ensure(!root.exists(trash))
            // Recheck before publication: the exact bytes and revision the user confirmed.
            ensure(fingerprint(LIVE) == intent.liveSha256)
            when (val read = live().load()) {
                is ProfileRead.Ready ->
                    ensure(read.saved.token == RevisionToken(intent.generationId, intent.revision))
                else -> throw IOException("The confirmed profile could not be re-read.")
            }
            root.moveNew(LIVE, trash)
            root.syncDirectory(TRASH)
            root.syncDirectory("")
        }
        if (root.exists(trash)) deleteTree(trash, TRASH, 0)
        if (root.exists(EVIDENCE)) for (id in root.list(EVIDENCE)) {
            ensure(isUuid(id))
            deleteTree("$EVIDENCE\\$id", EVIDENCE, 0)
        }
        if (root.exists(BACKUP_WORK)) {
            ensure(root.isDirectory(BACKUP_WORK))
            for (name in root.list(BACKUP_WORK)) {
                val path = "$BACKUP_WORK\\$name"
                if (ARCHIVE.matches(name) && !root.isDirectory(path)) root.delete(path)
            }
            root.syncDirectory(BACKUP_WORK)
        }
        // Re-establish durability of an unpublication rename made by an earlier attempt.
        root.syncDirectory(TRASH)
        root.syncDirectory("")
        retire(INTENT)
    }

    private fun deleteTree(path: String, parent: String, depth: Int) {
        ensure(depth <= MAX_DEPTH)
        if (root.isDirectory(path)) {
            for (name in root.list(path)) {
                ensure(isSafeName(name))
                deleteTree("$path\\$name", path, depth + 1)
            }
            root.syncDirectory(path)
        }
        root.delete(path)
        root.syncDirectory(parent)
    }

    // ---------------------------------------------------------------- recovery

    private fun abortRecover(intent: RecoverIntent) {
        ensure(!root.exists("$EVIDENCE\\${intent.evidenceId}"))
        val candidate = "$CANDIDATE\\${intent.operationId}"
        if (root.exists(candidate)) deleteTree(candidate, CANDIDATE, 0)
        retire(INTENT)
    }

    private fun completeRecover(confirm: RecoverConfirm, hasIntent: Boolean): SavedProfile {
        val candidate = "$CANDIDATE\\${confirm.operationId}"
        val evidence = "$EVIDENCE\\${confirm.evidenceId}"
        var livePresent = root.exists(LIVE)
        var candidatePresent = root.exists(candidate)
        var evidencePresent = root.exists(evidence)
        if (livePresent && candidatePresent && !evidencePresent) {
            ensure(hasIntent)
            // Revalidate the recovery context: still the exact unavailable store that was confirmed.
            ensure(fingerprint(LIVE) == confirm.liveSha256)
            ensure(fingerprint(candidate) == confirm.candidateSha256)
            root.moveNew(LIVE, evidence)
            root.syncDirectory(EVIDENCE)
            root.syncDirectory("")
            livePresent = false
            evidencePresent = true
        }
        if (!livePresent && candidatePresent && evidencePresent) {
            ensure(hasIntent)
            ensure(fingerprint(evidence) == confirm.liveSha256)
            ensure(fingerprint(candidate) == confirm.candidateSha256)
            root.moveNew(candidate, LIVE)
            root.syncDirectory("")
            root.syncDirectory(CANDIDATE)
            livePresent = true
            candidatePresent = false
        }
        ensure(livePresent && !candidatePresent && evidencePresent)
        // A resumed attempt cannot know whether an earlier rename's parent syncs completed;
        // re-establish them before the records that describe the renames are retired.
        root.syncDirectory(EVIDENCE)
        root.syncDirectory(CANDIDATE)
        root.syncDirectory("")
        ensure(fingerprint(evidence) == confirm.liveSha256)
        ensure(fingerprint(LIVE) == confirm.candidateSha256)
        // Only the verified replacement directory clears in-memory reference uncertainty.
        StoreLocks.clearUncertain(liveFiles().uncertaintyKey)
        val saved = when (val read = live().load()) {
            is ProfileRead.Ready -> read.saved
            else -> throw IOException("The recovered profile could not be re-read.")
        }
        ensure(saved.token == RevisionToken(confirm.generationId, confirm.revision))
        if (hasIntent) retire(INTENT)
        retire(CONFIRM)
        return saved
    }

    private fun recoveryToken(): String? = try { tokenFor(fingerprint(LIVE)) } catch (_: Exception) { null }

    private fun tokenFor(liveSha256: String) = sha256("VCARD-RECOVERY-1\n$liveSha256".toByteArray(Charsets.US_ASCII))

    // ---------------------------------------------------------------- records and files

    private fun ensureOperationDirectories() {
        if (!root.exists(OPS)) {
            root.createDirectory(OPS)
            root.syncDirectory("")
        }
        for (directory in listOf(EVIDENCE, CANDIDATE, TRASH)) if (!root.exists(directory)) {
            root.createDirectory(directory)
            root.syncDirectory(OPS)
        }
    }

    private fun writeRecord(path: String, temporary: String, bytes: ByteArray) {
        require(bytes.size <= RECORD_LIMIT)
        root.writeNew(temporary, bytes)
        root.syncFile(temporary)
        root.moveNew(temporary, path)
        root.syncDirectory(OPS)
        if (!root.read(path, RECORD_LIMIT).contentEquals(bytes)) throw IOException("Record verification failed.")
    }

    /** Unlinks a record; until its directory sync is confirmed every later operation re-syncs first. */
    private fun retire(path: String) {
        StoreLocks.markRemovalUnsynced(operationsKey)
        root.delete(path)
        root.syncDirectory(OPS)
        StoreLocks.clearRemovalUnsynced(operationsKey)
    }

    private fun recordMayExist(path: String): Boolean = try { root.exists(path) } catch (_: Exception) { true }

    private fun encode(record: OperationRecord): ByteArray {
        val payload: LinkedHashMap<String, Any> = when (record) {
            is DeleteIntent -> linkedMapOf("magic" to MAGIC, "record" to "intent", "kind" to "delete",
                "operationId" to record.operationId, "generationId" to record.generationId,
                "revision" to record.revision, "liveSha256" to record.liveSha256)
            is RecoverIntent -> linkedMapOf("magic" to MAGIC, "record" to "intent", "kind" to "recover",
                "operationId" to record.operationId, "evidenceId" to record.evidenceId,
                "liveSha256" to record.liveSha256, "backupSha256" to record.backupSha256)
            is RecoverConfirm -> linkedMapOf("magic" to MAGIC, "record" to "confirm", "kind" to "recover",
                "operationId" to record.operationId, "evidenceId" to record.evidenceId,
                "liveSha256" to record.liveSha256, "backupSha256" to record.backupSha256,
                "candidateSha256" to record.candidateSha256, "generationId" to record.generationId,
                "revision" to record.revision)
        }
        return StrictJson.encode(linkedMapOf("payload" to payload, "sha256" to sha256(StrictJson.encode(payload))))
    }

    private fun readRecord(path: String): OperationRecord {
        ensure(!root.isDirectory(path))
        val bytes = root.read(path, RECORD_LIMIT)
        try {
            val envelope = StrictJson.decode(bytes, maxDepth = 2).jsonObject("payload", "sha256")
            val raw = envelope["payload"] as? Map<*, *> ?: throw Blocked()
            val record = raw["record"] as? String
            val kind = raw["kind"] as? String
            val payload = when {
                record == "intent" && kind == "delete" -> raw.jsonObject("magic", "record", "kind", "operationId",
                    "generationId", "revision", "liveSha256")
                record == "intent" && kind == "recover" -> raw.jsonObject("magic", "record", "kind", "operationId",
                    "evidenceId", "liveSha256", "backupSha256")
                record == "confirm" && kind == "recover" -> raw.jsonObject("magic", "record", "kind", "operationId",
                    "evidenceId", "liveSha256", "backupSha256", "candidateSha256", "generationId", "revision")
                else -> throw Blocked()
            }
            ensure(envelope.string("sha256") == sha256(StrictJson.encode(payload)))
            ensure(payload.string("magic") == MAGIC)
            val operationId = uuid(payload.string("operationId"))
            fun hex(key: String) = payload.string(key).also { ensure(HEX64.matches(it)) }
            val decoded = when {
                kind == "delete" -> DeleteIntent(operationId, uuid(payload.string("generationId")),
                    payload.long("revision").also { ensure(it > 0) }, hex("liveSha256"))
                record == "intent" -> RecoverIntent(operationId, uuid(payload.string("evidenceId")),
                    hex("liveSha256"), hex("backupSha256"))
                else -> RecoverConfirm(operationId, uuid(payload.string("evidenceId")), hex("liveSha256"),
                    hex("backupSha256"), hex("candidateSha256"), uuid(payload.string("generationId")),
                    payload.long("revision").also { ensure(it == 1L) })
            }
            ensure(encode(decoded).contentEquals(bytes))
            if (decoded is RecoverIntent) ensure(decoded.evidenceId != decoded.operationId)
            if (decoded is RecoverConfirm) ensure(decoded.evidenceId != decoded.operationId)
            return decoded
        } catch (e: Blocked) {
            throw e
        } catch (_: IllegalArgumentException) {
            throw Blocked()
        } catch (_: ArithmeticException) {
            throw Blocked()
        }
    }

    /** Bounded content identity of a file or directory tree. Never follows outside [path]. */
    private fun fingerprint(path: String): String {
        val description = StringBuilder()
        var count = 0
        fun walk(relative: String, depth: Int) {
            ensure(depth <= MAX_DEPTH && ++count <= MAX_ENTRIES)
            val full = if (relative.isEmpty()) path else "$path\\$relative"
            if (root.isDirectory(full)) {
                description.append("D ").append(relative).append('\n')
                for (name in root.list(full).sorted()) {
                    ensure(isSafeName(name))
                    walk(if (relative.isEmpty()) name else "$relative\\$name", depth + 1)
                }
            } else {
                val bytes = root.read(full, MAX_FILE_BYTES)
                description.append("F ").append(relative).append(' ').append(bytes.size).append(' ')
                    .append(sha256(bytes)).append('\n')
            }
        }
        walk("", 0)
        return sha256(description.toString().toByteArray(Charsets.UTF_8))
    }

    private fun ensure(condition: Boolean) { if (!condition) throw Blocked() }
    private fun isUuid(name: String) = try { uuid(name); true } catch (_: IllegalArgumentException) { false }
    private fun isSafeName(name: String) = name.isNotEmpty() && name != "." && name != ".." && name.length <= 128 &&
        name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '.' }
    private fun randomId() = UUID.randomUUID().toString()

    private fun pendingMessage(kind: String) = if (kind == "delete") PENDING_DELETE else PENDING_RECOVER

    companion object {
        const val LIVE = "store-v1"
        const val OPS = "profile-operations"
        const val BACKUP_WORK = "backup-work"
        private const val INTENT = "$OPS\\intent"
        private const val INTENT_TMP = "$OPS\\intent.tmp"
        private const val CONFIRM = "$OPS\\confirm"
        private const val CONFIRM_TMP = "$OPS\\confirm.tmp"
        private const val EVIDENCE = "$OPS\\evidence"
        private const val CANDIDATE = "$OPS\\candidate"
        private const val TRASH = "$OPS\\trash"
        private val OPS_ENTRIES = setOf("intent", "confirm", "intent.tmp", "confirm.tmp", "evidence", "candidate", "trash")
        private const val MAGIC = "VCARD-PROFILE-OPERATION-1"
        private const val RECORD_LIMIT = 4096
        private const val MAX_DEPTH = 8
        private const val MAX_ENTRIES = 8192
        private const val MAX_FILE_BYTES = 33_554_432
        private val HEX64 = Regex("[0-9a-f]{64}")
        private val ARCHIVE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.archive")

        const val BLOCKED = "Saved profile storage needs attention. An interrupted operation record could not be " +
            "verified, so nothing was changed or reset and all data is preserved."
        const val PENDING_DELETE = "Profile deletion has not finished. It will be retried; the profile will not be restored."
        const val PENDING_RECOVER = "Profile recovery has not finished. Saved data and preserved evidence are kept; " +
            "it will be retried."
        private const val STALE_RECOVERY = "The recovery request no longer matches the saved data. Reload and try " +
            "again; nothing was changed."
        private const val CANDIDATE_FAILED = "The backup could not be recovered. The unavailable saved data is unchanged."
    }
}
