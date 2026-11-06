package com.nohimazin.tinytransit

import com.nohimazin.tinytransit.data.gtfs.GtfsCsvReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GtfsCsvReaderTest {

    @Test
    fun `basic csv with header`() {
        val text = "a,b,c\n1,2,3\n4,5,6"
        val rows = GtfsCsvReader.parse(text)
        assertEquals(2, rows.size)
        assertEquals("1", rows[0]["a"])
        assertEquals("2", rows[0]["b"])
        assertEquals("3", rows[0]["c"])
        assertEquals("4", rows[1]["a"])
    }

    @Test
    fun `quoted field with comma`() {
        val text = "name,desc\n\"Stop, Main\",\"A, B, C\""
        val rows = GtfsCsvReader.parse(text)
        assertEquals(1, rows.size)
        assertEquals("Stop, Main", rows[0]["name"])
        assertEquals("A, B, C", rows[0]["desc"])
    }

    @Test
    fun `escaped quotes`() {
        val text = "name\n\"He said \"\"hello\"\"\""
        val rows = GtfsCsvReader.parse(text)
        assertEquals(1, rows.size)
        assertEquals("He said \"hello\"", rows[0]["name"])
    }

    @Test
    fun `empty fields`() {
        val text = "a,b,c\n1,,3\n,,"
        val rows = GtfsCsvReader.parse(text)
        assertEquals(2, rows.size)
        assertEquals("", rows[0]["b"])
        assertEquals("", rows[1]["a"])
        assertEquals("", rows[1]["b"])
        assertEquals("", rows[1]["c"])
    }

    @Test
    fun `crlf line endings`() {
        val text = "a,b\r\n1,2\r\n3,4\r\n"
        val rows = GtfsCsvReader.parse(text)
        assertEquals(2, rows.size)
        assertEquals("1", rows[0]["a"])
        assertEquals("3", rows[1]["a"])
    }

    @Test
    fun `last column empty`() {
        val text = "a,b,c\n1,2,\n3,4,"
        val rows = GtfsCsvReader.parse(text)
        assertEquals(2, rows.size)
        assertEquals("", rows[0]["c"])
        assertEquals("", rows[1]["c"])
    }

    @Test
    fun `quoted newline`() {
        val text = "name,desc\n\"Line1\nLine2\",\"desc\""
        val rows = GtfsCsvReader.parse(text)
        assertEquals(1, rows.size)
        assertEquals("Line1\nLine2", rows[0]["name"])
        assertEquals("desc", rows[0]["desc"])
    }

    @Test
    fun `utf8 bom handling`() {
        // BOM should be stripped by caller; parser itself doesn't strip BOM.
        // Here we test that BOM char doesn't break parsing of first header.
        val bom = "\uFEFF"
        val text = "${bom}a,b\n1,2"
        val rows = GtfsCsvReader.parse(text)
        // First header key includes BOM; document this behavior
        assertTrue(rows.isNotEmpty())
    }

    @Test
    fun `empty input`() {
        val rows = GtfsCsvReader.parse("")
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `header only`() {
        val rows = GtfsCsvReader.parse("a,b,c\n")
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `gtfs stop_times style with 24h+ time`() {
        val text = "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
            "T1,25:10:00,25:12:00,S1,1\n" +
            "T1,25:30:00,25:30:00,S2,2"
        val rows = GtfsCsvReader.parse(text)
        assertEquals(2, rows.size)
        assertEquals("25:10:00", rows[0]["arrival_time"])
        assertEquals("2", rows[1]["stop_sequence"])
    }
}