package com.mackseoul.vcardnative.data.backup

import com.mackseoul.vcardnative.domain.backup.BackupException
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

class BackupStagingTest {
    private val root = File("build\\backup-stage-tests\\${UUID.randomUUID()}")
    private val staging = BackupStaging(root)
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun exactBytesTamperDetectionAndExplicitDiscard() {
        val bytes = byteArrayOf(1, 2, 3)
        val stage = staging.stage(bytes.inputStream())
        assertArrayEquals(bytes, staging.read(stage))
        root.resolve("${stage.id}.archive").writeBytes(byteArrayOf(1, 2, 4))
        try { staging.read(stage); fail() } catch (_: BackupException) { }
        staging.discard(stage)
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun emptyOversizedAndInterruptedReadsLeaveNoCompleteCandidate() {
        for (input in listOf(
            byteArrayOf().inputStream(),
            ByteArray(BackupStaging.MAX_BYTES.toInt() + 1).inputStream(),
            object : InputStream() { override fun read(): Int = throw IOException("Injected read failure") },
        )) {
            try { input.use { staging.stage(it) }; fail() } catch (_: BackupException) { }
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test fun abandonedOwnedFilesAreRemovedButUnrecognizedEvidenceIsPreserved() {
        staging.stage(byteArrayOf(1).inputStream())
        root.resolve("unknown-evidence").writeText("keep")
        staging.cleanupAbandoned()
        assertEquals(listOf("unknown-evidence"), root.list()!!.toList())
    }

    @Test fun capacityFailureNeverPublishesAStagedCandidate() {
        val limited = BackupStaging(root) { _, _ -> throw BackupException("Insufficient storage") }
        try { limited.stage(byteArrayOf(1).inputStream()); fail() } catch (_: BackupException) { }
        assertTrue(root.listFiles()!!.isEmpty())
    }
}
