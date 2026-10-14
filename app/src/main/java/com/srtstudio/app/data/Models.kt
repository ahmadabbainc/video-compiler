package com.srtstudio.app.data

import android.net.Uri

/** One subtitle entry. Times are in milliseconds. */
data class SrtCue(val startMs: Long, val endMs: Long, val text: String)

enum class VideoSize(val label: String, val width: Int, val height: Int) {
    HD_16_9("720p · 16:9", 1280, 720),
    FHD_16_9("1080p · 16:9", 1920, 1080),
    HD_9_16("720p · 9:16", 720, 1280),
    FHD_9_16("1080p · 9:16", 1080, 1920),
    SQUARE("1080 · 1:1", 1080, 1080);

    val portrait: Boolean get() = height > width
}

enum class CaptionPosition(val label: String) { Bottom("Bottom"), Center("Center"), Top("Top") }

enum class CaptionStyle(val label: String) { Outline("Outline"), Box("Box"), Shadow("Shadow") }

enum class ImageMapping(val label: String) {
    PerCue("One per caption"),
    Spread("Spread evenly")
}

data class Palette(val name: String, val top: Int, val bottom: Int)

val Palettes: List<Palette> = listOf(
    Palette("Midnight", 0xFF1B2A5C.toInt(), 0xFF05070F.toInt()),
    Palette("Aurora", 0xFF0F766E.toInt(), 0xFF1E1B4B.toInt()),
    Palette("Sunset", 0xFFBE3A5B.toInt(), 0xFF2A1240.toInt()),
    Palette("Graphite", 0xFF3A3F4B.toInt(), 0xFF0E1015.toInt()),
)

val CaptionColors: List<Int> = listOf(
    0xFFFFFFFF.toInt(), // white
    0xFFFFD84D.toInt(), // sun
    0xFF5CF2FF.toInt(), // cyan
    0xFF7CFFB2.toInt(), // mint
    0xFFFF8FB1.toInt(), // rose
)

data class RenderSettings(
    val size: VideoSize = VideoSize.FHD_16_9,
    val fps: Int = 30,
    /** Caption font size as a fraction of the video's shorter side. */
    val fontScale: Float = 0.06f,
    val textColor: Int = CaptionColors[0],
    val position: CaptionPosition = CaptionPosition.Bottom,
    val style: CaptionStyle = CaptionStyle.Outline,
    val kenBurns: Boolean = true,
    val crossfadeMs: Int = 500,
    val mapping: ImageMapping = ImageMapping.PerCue,
    val palette: Int = 0,
)

/** Everything the renderer needs. Built once on the UI thread, then handed to the service. */
data class RenderRequest(
    val cues: List<SrtCue>,
    val imageUris: List<Uri>,
    val audioUri: Uri?,
    val settings: RenderSettings,
    val fontPath: String?,
)
