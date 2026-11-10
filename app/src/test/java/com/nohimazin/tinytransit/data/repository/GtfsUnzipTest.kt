package com.nohimazin.tinytransit.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GtfsUnzipTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private fun createZip(vararg entries: Pair<String, String>): File {
        val zipFile = tmp.newFile("test.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return zipFile
    }

    private fun createZipWithDirEntries(): File {
        val zipFile = tmp.newFile("dir.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("subdir/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("subdir/file.txt"))
            zos.write("hello".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return zipFile
    }

    @Test
    fun normalZipExtracts() {
        val zip = createZip("a.txt" to "hello", "b.txt" to "world")
        val dest = tmp.newFolder("dest1")
        GtfsRepository.unzip(zip, dest)
        assertEquals("hello", File(dest, "a.txt").readText())
        assertEquals("world", File(dest, "b.txt").readText())
    }

    @Test
    fun subdirectoryExtracts() {
        val zip = createZipWithDirEntries()
        val dest = tmp.newFolder("dest2")
        GtfsRepository.unzip(zip, dest)
        assertEquals("hello", File(dest, "subdir/file.txt").readText())
    }

    @Test
    fun emptyZipOk() {
        val zip = tmp.newFile("empty.zip")
        ZipOutputStream(zip.outputStream()).use { /* no entries */ }
        val dest = tmp.newFolder("dest3")
        GtfsRepository.unzip(zip, dest) // should not throw
        assertTrue(dest.exists())
    }

    @Test
    fun zipSlipParentTraversalRejected() {
        val cases = listOf(
            "../outside.txt",
            "../../outside.txt",
            "nested/../../outside.txt"
        )
        for ((i, name) in cases.withIndex()) {
            val zip = tmp.newFile("slip_$i.zip")
            ZipOutputStream(zip.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry(name))
                zos.write("evil".toByteArray())
                zos.closeEntry()
            }
            val dest = tmp.newFolder("dest_slip_$i")
            val outside = File(dest.parentFile, "outside.txt")
            outside.delete()
            try {
                GtfsRepository.unzip(zip, dest)
                fail("Expected exception for $name")
            } catch (e: Exception) {
                // expected
            }
            assertTrue("File created outside dest for $name", !outside.exists())
        }
    }

    @Test
    fun zipSlipAbsoluteRejected() {
        val zip = createZip("/absolute.txt" to "evil")
        val dest = tmp.newFolder("dest_abs")
        try {
            GtfsRepository.unzip(zip, dest)
            fail("Expected exception for absolute path")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun zipSlipDriveLetterRejected() {
        val zip = createZip("C:/outside.txt" to "evil")
        val dest = tmp.newFolder("dest_drive")
        try {
            GtfsRepository.unzip(zip, dest)
            fail("Expected exception for drive letter")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun zipSlipBackslashRejected() {
        val names = listOf("C:\\outside.txt", "..\\outside.txt", "nested\\..\\outside.txt")
        for (name in names) {
            val zip = tmp.newFile("bs_${name.hashCode()}.zip")
            ZipOutputStream(zip.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry(name))
                zos.write("evil".toByteArray())
                zos.closeEntry()
            }
            val dest = tmp.newFolder("dest_bs_$name".replace("\\", "_").replace("/", "_").replace(":", "_"))
            try {
                GtfsRepository.unzip(zip, dest)
                fail("Expected exception for $name")
            } catch (e: Exception) {
                // expected
            }
        }
    }

    @Test
    fun zipSlipUncRejected() {
        val zip = createZip("//server/share.txt" to "evil")
        val dest = tmp.newFolder("dest_unc")
        try {
            GtfsRepository.unzip(zip, dest)
            fail("Expected exception for UNC path")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun entryCountLimitEnforced() {
        val zip = createZip("a.txt" to "x", "b.txt" to "y", "c.txt" to "z")
        val dest = tmp.newFolder("dest_count")
        try {
            GtfsRepository.unzip(zip, dest, maxEntries = 2)
            fail("Expected exception for entry count")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun entrySizeLimitEnforced() {
        val zip = createZip("big.txt" to "0123456789")
        val dest = tmp.newFolder("dest_size")
        try {
            GtfsRepository.unzip(zip, dest, maxEntrySizeBytes = 5)
            fail("Expected exception for entry size")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun totalSizeLimitEnforced() {
        val zip = createZip("a.txt" to "01234", "b.txt" to "56789")
        val dest = tmp.newFolder("dest_total")
        try {
            GtfsRepository.unzip(zip, dest, maxTotalUncompressedBytes = 8)
            fail("Expected exception for total size")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun emptyEntryNameRejected() {
        try {
            GtfsRepository.validateZipEntryName("")
            fail("Expected exception for empty name")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun nulCharRejected() {
        try {
            GtfsRepository.validateZipEntryName("a\u0000b.txt")
            fail("Expected exception for NUL")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun dotSegmentRejected() {
        try {
            GtfsRepository.validateZipEntryName("a/./b.txt")
            fail("Expected exception for dot segment")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun dirFileCollisionRejected() {
        // directory "data.txt/" then file "data.txt"
        val zip = tmp.newFile("coll2.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("data.txt/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("data.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
        }
        val dest = tmp.newFolder("dest_coll2")
        try {
            GtfsRepository.unzip(zip, dest)
            fail("Expected exception for dir/file collision")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun brokenZipRejected() {
        val zip = tmp.newFile("broken.zip")
        zip.writeBytes("not a zip file".toByteArray())
        val dest = tmp.newFolder("dest_broken")
        try {
            GtfsRepository.unzip(zip, dest)
            fail("Expected exception for broken zip")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun pathTraversalViaCanonicalRejected() {
        // nested/../../outside.txt is already covered, but test canonical check too
        val zip = createZip("a/b/../../../outside.txt" to "evil")
        val dest = tmp.newFolder("dest_canonical")
        val outside = File(dest.parentFile, "outside.txt")
        outside.delete()
        try {
            GtfsRepository.unzip(zip, dest)
            fail("Expected exception")
        } catch (e: Exception) {
            // expected
        }
        assertTrue(!outside.exists())
    }
}
