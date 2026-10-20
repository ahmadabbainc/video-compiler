package com.srtstudio.app.data

/** One stretch of the video during which a single photo is the main picture. Times in ms. */
data class ImageSegment(val imageIdx: Int, val startMs: Long, val endMs: Long, val order: Int)

/**
 * Decides which photo belongs to which moment of the video. Pure Kotlin so it can be unit tested.
 *
 * "One per caption" ties photo N to caption N by *caption index*, not by how many segments happen to
 * survive. (Counting segments instead drifts out of step with the narration as soon as two captions
 * share a start time or one starts after the video ends.)
 */
object Timeline {

    fun buildSegments(cues: List<SrtCue>, imageCount: Int, durationMs: Long, perCue: Boolean): List<ImageSegment> {
        val dur = maxOf(durationMs, 1L)
        if (imageCount == 0) return listOf(ImageSegment(-1, 0L, dur, 0))

        if (perCue && cues.isNotEmpty()) {
            val cueOf = ArrayList<Int>()   // caption index that owns each segment
            val starts = ArrayList<Long>() // where each segment begins
            for (i in cues.indices) {
                val s = cues[i].startMs
                when {
                    i == 0 -> { cueOf.add(0); starts.add(0L) }               // first photo covers any lead-in
                    s == cues[i - 1].startMs -> cueOf[cueOf.lastIndex] = i  // same start: the later caption wins
                    s < dur -> { cueOf.add(i); starts.add(s) }
                }
            }
            return starts.indices.map { k ->
                val end = if (k + 1 < starts.size) starts[k + 1] else dur
                ImageSegment(cueOf[k] % imageCount, starts[k], end, k)
            }
        }
        return (0 until imageCount).map { k ->
            ImageSegment(k, dur * k / imageCount, dur * (k + 1) / imageCount, k)
        }
    }
}
