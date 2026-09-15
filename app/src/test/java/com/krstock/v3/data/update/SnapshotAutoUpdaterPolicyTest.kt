package com.krstock.v3.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SnapshotAutoUpdaterPolicyTest {

    @Test
    fun newerLocalCacheCannotBeDowngradedByOlderRemote() {
        assertTrue(
            SnapshotAutoUpdater.isOlderVersion(
                incomingSnapshotDate = "2026-09-14",
                incomingPriceDate = "2026-09-14",
                activeSnapshotDate = "2026-09-15",
                activePriceDate = "2026-09-14",
            )
        )
        assertTrue(
            SnapshotAutoUpdater.isOlderVersion(
                incomingSnapshotDate = "2026-09-15",
                incomingPriceDate = "2026-09-11",
                activeSnapshotDate = "2026-09-15",
                activePriceDate = "2026-09-14",
            )
        )
        assertFalse(
            SnapshotAutoUpdater.isOlderVersion(
                incomingSnapshotDate = "2026-09-15",
                incomingPriceDate = "2026-09-15",
                activeSnapshotDate = "2026-09-15",
                activePriceDate = "2026-09-14",
            )
        )
        assertFalse(
            SnapshotAutoUpdater.isOlderVersion(
                incomingSnapshotDate = "2026-09-16",
                incomingPriceDate = "2026-09-15",
                activeSnapshotDate = "2026-09-15",
                activePriceDate = "2026-09-14",
            )
        )
    }

    @Test
    fun atomicPromotionPreservesPreviousCacheAsRollbackCopy() {
        val dir = Files.createTempDirectory("kr4-snapshot-test").toFile()
        try {
            val cache = File(dir, "kr4_latest_snapshot.json")
            val backup = File(dir, "kr4_previous_snapshot.json")
            cache.writeText("known-good-old", Charsets.UTF_8)

            SnapshotAutoUpdater.writeSnapshotFilesAtomically(
                directory = dir,
                body = "validated-new",
                preserveExistingCache = true,
            )

            assertEquals("validated-new", cache.readText(Charsets.UTF_8))
            assertEquals("known-good-old", backup.readText(Charsets.UTF_8))
            assertFalse(File(dir, "kr4_snapshot.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun invalidCurrentCacheCannotOverwriteExistingKnownGoodBackup() {
        val dir = Files.createTempDirectory("kr4-snapshot-test").toFile()
        try {
            val cache = File(dir, "kr4_latest_snapshot.json")
            val backup = File(dir, "kr4_previous_snapshot.json")
            cache.writeText("corrupt-current", Charsets.UTF_8)
            backup.writeText("known-good-backup", Charsets.UTF_8)

            SnapshotAutoUpdater.writeSnapshotFilesAtomically(
                directory = dir,
                body = "validated-new",
                preserveExistingCache = false,
            )

            assertEquals("validated-new", cache.readText(Charsets.UTF_8))
            assertEquals("known-good-backup", backup.readText(Charsets.UTF_8))
        } finally {
            dir.deleteRecursively()
        }
    }
}
