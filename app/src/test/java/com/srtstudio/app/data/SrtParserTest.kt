package com.srtstudio.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtParserTest {

    @Test
    fun parsesBasicFile() {
        val srt = "1\n00:00:01,000 --> 00:00:03,500\nHello\nworld\n\n2\n00:00:04,000 --> 00:00:06,000\nBye\n"
        val cues = SrtParser.parse(srt)
        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(3500L, cues[0].endMs)
        assertEquals("Hello\nworld", cues[0].text)
        assertEquals("Bye", cues[1].text)
    }

    @Test
    fun handlesBomWindowsLineEndingsAndDotSeparator() {
        val srt = "﻿1\r\n00:00:01.250 --> 00:00:02.000\r\nHi\r\n\r\n"
        val cues = SrtParser.parse(srt)
        assertEquals(1, cues.size)
        assertEquals(1250L, cues[0].startMs)
        assertEquals("Hi", cues[0].text)
    }

    @Test
    fun stripsTagsAndAssOverrides() {
        val srt = "1\n00:00:00,000 --> 00:00:02,000\n{\\an8}<i>Italic</i> <font color=\"red\">red</font>\n"
        assertEquals("Italic red", SrtParser.parse(srt).single().text)
    }

    @Test
    fun toleratesMissingIndexAndShortMillis() {
        val srt = "00:00:01,5 --> 00:00:02,25\nNo index\n"
        val cue = SrtParser.parse(srt).single()
        assertEquals(1500L, cue.startMs)
        assertEquals(2250L, cue.endMs)
    }

    @Test
    fun sortsOutOfOrderCuesAndFixesBadEnds() {
        val srt = "2\n00:00:10,000 --> 00:00:09,000\nLate\n\n1\n00:00:01,000 --> 00:00:02,000\nEarly\n"
        val cues = SrtParser.parse(srt)
        assertEquals(listOf("Early", "Late"), cues.map { it.text })
        assertTrue(cues[1].endMs > cues[1].startMs)
    }

    @Test
    fun skipsEmptyCuesAndGarbage() {
        val srt = "garbage\n\n1\n00:00:01,000 --> 00:00:02,000\n\n\n2\n00:00:03,000 --> 00:00:04,000\nOk\n"
        assertEquals(listOf("Ok"), SrtParser.parse(srt).map { it.text })
    }

    @Test
    fun keepsRightToLeftText() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nمرحبا بالعالم\n"
        assertEquals("مرحبا بالعالم", SrtParser.parse(srt).single().text)
    }

    @Test
    fun naturalSortOrdersNumbersNumerically() {
        val sorted = listOf("img10.png", "img2.png", "img1.png").sortedWith(FileUtils.naturalOrder)
        assertEquals(listOf("img1.png", "img2.png", "img10.png"), sorted)
    }

    @Test
    fun decodesLegacyArabicCodePage() {
        val bytes = "مرحبا".toByteArray(charset("windows-1256"))
        assertEquals("مرحبا", FileUtils.decodeText(bytes))
    }

    @Test
    fun splitsCuesThatAreMissingTheBlankLineBetweenThem() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nOne\n2\n00:00:03,000 --> 00:00:04,000\nTwo\n3\n00:00:05,000 --> 00:00:06,000\nThree\n"
        val cues = SrtParser.parse(srt)
        assertEquals(3, cues.size)
        assertEquals(listOf("One", "Two", "Three"), cues.map { it.text })
        assertEquals(listOf(1000L, 3000L, 5000L), cues.map { it.startMs })
    }
}
