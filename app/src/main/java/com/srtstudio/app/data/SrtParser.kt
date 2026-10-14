package com.srtstudio.app.data

/**
 * Forgiving SRT parser.
 *
 * Handles: BOM, Windows/Mac/Unix line endings, missing or wrong index lines, '.' or ',' as the
 * millisecond separator, short millisecond fields, HTML-ish tags (<i>, <font ...>), ASS-style
 * {\an8} overrides, out-of-order cues, and cues whose end is not after their start.
 */
object SrtParser {

    private val TIME = Regex(
        """(\d{1,3}):(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(\d{1,3}):(\d{1,2}):(\d{1,2})[,.](\d{1,3})"""
    )
    private val BLOCK_SPLIT = Regex("\n[ \t]*\n")
    private val HTML_TAG = Regex("<[^>]*>")
    private val ASS_TAG = Regex("\\{[^}]*\\}")

    fun parse(raw: String): List<SrtCue> {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val cues = ArrayList<SrtCue>()

        for (block in text.split(BLOCK_SPLIT)) {
            val lines = block.trim('\n', ' ', '\t').split('\n')
            val timeLine = lines.indexOfFirst { TIME.containsMatchIn(it) }
            if (timeLine < 0) continue

            val m = TIME.find(lines[timeLine])!!.groupValues
            val start = toMs(m[1], m[2], m[3], m[4])
            var end = toMs(m[5], m[6], m[7], m[8])

            val body = lines.drop(timeLine + 1)
                .joinToString("\n") { it.trim() }
                .replace(HTML_TAG, "")
                .replace(ASS_TAG, "")
                .trim()
            if (body.isEmpty()) continue

            if (end <= start) end = start + 1500L
            cues.add(SrtCue(start, end, body))
        }
        return cues.sortedBy { it.startMs }
    }

    private fun toMs(h: String, m: String, s: String, frac: String): Long {
        val millis = frac.padEnd(3, '0').take(3).toLong()
        return ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000L + millis
    }
}
