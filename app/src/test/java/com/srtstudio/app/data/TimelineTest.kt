package com.srtstudio.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineTest {

    private fun cue(start: Long, end: Long) = SrtCue(start, end, "x")

    @Test
    fun perCuePhotoChangesExactlyAtCaptionStart() {
        val cues = listOf(cue(1000, 2500), cue(3000, 4500), cue(5000, 6500))
        val seg = Timeline.buildSegments(cues, 3, 7000, perCue = true)
        assertEquals(listOf(0L, 3000L, 5000L), seg.map { it.startMs })
        assertEquals(listOf(0, 1, 2), seg.map { it.imageIdx })
        assertEquals(7000L, seg.last().endMs)
    }

    @Test
    fun captionsSharingAStartTimeDoNotShiftLaterPhotos() {
        // Cue 1 and cue 2 start together. Photo 3 must still belong to cue 3, not cue 2.
        val cues = listOf(cue(0, 1000), cue(2000, 3000), cue(2000, 3000), cue(4000, 5000))
        val seg = Timeline.buildSegments(cues, 4, 6000, perCue = true)
        assertEquals(3, seg.size)
        assertEquals(listOf(0, 2, 3), seg.map { it.imageIdx })
        assertEquals(4000L, seg[2].startMs)
    }

    @Test
    fun captionPastTheEndOfTheVideoDoesNotShiftEarlierPhotos() {
        val cues = listOf(cue(0, 1000), cue(2000, 3000), cue(9000, 9500))
        val seg = Timeline.buildSegments(cues, 3, 5000, perCue = true)
        assertEquals(listOf(0, 1), seg.map { it.imageIdx })
    }

    @Test
    fun firstPhotoCoversLeadInBeforeFirstCaption() {
        val seg = Timeline.buildSegments(listOf(cue(3000, 4000), cue(5000, 6000)), 2, 7000, perCue = true)
        assertEquals(0L, seg[0].startMs)
        assertEquals(5000L, seg[1].startMs)
    }

    @Test
    fun photosCycleWhenThereAreFewerPhotosThanCaptions() {
        val cues = (0 until 5).map { cue(it * 1000L, it * 1000L + 800) }
        val seg = Timeline.buildSegments(cues, 2, 6000, perCue = true)
        assertEquals(listOf(0, 1, 0, 1, 0), seg.map { it.imageIdx })
    }

    @Test
    fun spreadSplitsTheVideoEvenly() {
        val seg = Timeline.buildSegments(emptyList(), 4, 8000, perCue = false)
        assertEquals(listOf(0L, 2000L, 4000L, 6000L), seg.map { it.startMs })
    }

    @Test
    fun noPhotosGivesOneBackgroundSegment() {
        val seg = Timeline.buildSegments(listOf(cue(0, 1000)), 0, 4000, perCue = true)
        assertEquals(1, seg.size)
        assertEquals(-1, seg[0].imageIdx)
    }
}
