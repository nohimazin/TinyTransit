package com.nohimazin.tinytransit.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class GtfsDownloadTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    @Test
    fun contentLengthExceedsLimitRejected() {
        val dest = tmp.newFile("out.zip")
        val data = "hello".toByteArray()
        try {
            GtfsRepository.downloadToFile(
                ByteArrayInputStream(data),
                contentLength = 1000L,
                dest = dest,
                maxSizeBytes = 10
            )
            fail("Expected exception for content-length exceeding limit")
        } catch (e: Exception) {
            // expected
        }
        assertTrue("Partial file should be deleted", !dest.exists())
    }

    @Test
    fun contentLengthUnknownActualExceedsRejected() {
        val dest = tmp.newFile("out2.zip")
        val data = ByteArray(20) { it.toByte() }
        try {
            GtfsRepository.downloadToFile(
                ByteArrayInputStream(data),
                contentLength = -1L,
                dest = dest,
                maxSizeBytes = 10
            )
            fail("Expected exception for actual exceeding limit")
        } catch (e: Exception) {
            // expected
        }
        assertTrue("Partial file should be deleted", !dest.exists())
    }

    @Test
    fun contentLengthSmallerThanActualRejected() {
        val dest = tmp.newFile("out3.zip")
        val data = "hello world".toByteArray() // 11 bytes
        try {
            GtfsRepository.downloadToFile(
                ByteArrayInputStream(data),
                contentLength = 5L,
                dest = dest,
                maxSizeBytes = 100
            )
            fail("Expected exception for size mismatch (smaller)")
        } catch (e: Exception) {
            // expected
        }
        assertTrue("Partial file should be deleted", !dest.exists())
    }

    @Test
    fun contentLengthLargerThanActualRejected() {
        val dest = tmp.newFile("out4.zip")
        val data = "hi".toByteArray() // 2 bytes
        try {
            GtfsRepository.downloadToFile(
                ByteArrayInputStream(data),
                contentLength = 100L,
                dest = dest,
                maxSizeBytes = 200
            )
            fail("Expected exception for size mismatch (larger)")
        } catch (e: Exception) {
            // expected
        }
        assertTrue("Partial file should be deleted", !dest.exists())
    }

    @Test
    fun successWritesFile() {
        val dest = tmp.newFile("out5.zip")
        val data = "hello world".toByteArray()
        GtfsRepository.downloadToFile(
            ByteArrayInputStream(data),
            contentLength = data.size.toLong(),
            dest = dest,
            maxSizeBytes = 100
        )
        assertEquals(data.size.toLong(), dest.length())
        assertEquals("hello world", dest.readText())
    }

    @Test
    fun unknownContentLengthSuccess() {
        val dest = tmp.newFile("out6.zip")
        val data = "abc".toByteArray()
        GtfsRepository.downloadToFile(
            ByteArrayInputStream(data),
            contentLength = -1L,
            dest = dest,
            maxSizeBytes = 100
        )
        assertEquals(3L, dest.length())
    }

    @Test
    fun midStreamExceptionDeletesPartial() {
        // InputStream that throws mid-read
        val dest = tmp.newFile("out7.zip")
        val throwing = object : java.io.InputStream() {
            private var count = 0
            override fun read(): Int {
                if (count++ < 3) return 'a'.code
                throw java.io.IOException("simulated failure")
            }
        }
        try {
            GtfsRepository.downloadToFile(
                throwing,
                contentLength = -1L,
                dest = dest,
                maxSizeBytes = 100
            )
            fail("Expected exception")
        } catch (e: Exception) {
            // expected
        }
        assertTrue("Partial file should be deleted", !dest.exists())
    }
}
