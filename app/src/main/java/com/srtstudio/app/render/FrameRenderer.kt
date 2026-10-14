package com.srtstudio.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.graphics.ColorUtils
import com.srtstudio.app.data.CaptionPosition
import com.srtstudio.app.data.CaptionStyle
import com.srtstudio.app.data.ImageMapping
import com.srtstudio.app.data.Palettes
import com.srtstudio.app.data.RenderSettings
import com.srtstudio.app.data.SrtCue
import kotlin.math.max
import kotlin.math.min

/**
 * Draws one video frame at a time onto [bitmap]: background (photo with optional Ken Burns
 * motion and crossfades, or a gradient), a readability scrim, and the current caption.
 *
 * The same class powers the live preview and the real render, so what you see is what you get.
 * The caller owns [bitmap]; [close] only frees the cached photos.
 */
class FrameRenderer(
    private val ctx: Context,
    private val settings: RenderSettings,
    val width: Int,
    val height: Int,
    private val imageUris: List<Uri>,
    private val cues: List<SrtCue>,
    private val durationMs: Long,
    font: Typeface?,
) {
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    private data class Segment(val imageIdx: Int, val startMs: Long, val endMs: Long, val order: Int)

    private val base = min(width, height).toFloat()
    private val portrait = height > width
    private val palette = Palettes[settings.palette.coerceIn(0, Palettes.lastIndex)]
    private val segments: List<Segment> = buildSegments()
    private val photos = HashMap<Int, Bitmap?>()

    private val bgPaint = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, height.toFloat(), palette.top, palette.bottom, Shader.TileMode.CLAMP)
    }
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint().apply {
        when (settings.position) {
            CaptionPosition.Bottom -> shader = LinearGradient(
                0f, height * 0.45f, 0f, height.toFloat(), 0x00000000, 0xC0000000.toInt(), Shader.TileMode.CLAMP
            )
            CaptionPosition.Top -> shader = LinearGradient(
                0f, height * 0.55f, 0f, 0f, 0x00000000, 0xC0000000.toInt(), Shader.TileMode.CLAMP
            )
            CaptionPosition.Center -> color = 0x59000000
        }
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = font ?: Typeface.DEFAULT_BOLD
    }
    private val matrix = Matrix()
    private val maxTextWidth = (width * (if (portrait) 0.86f else 0.84f)).toInt()

    private var segPtr = 0
    private var cuePtr = 0
    private var layoutCue = -1
    private var layout: StaticLayout? = null

    private var drawnOnce = false
    private var lastDynamic = false
    private var lastSeg = -1
    private var lastCue = -2

    private fun buildSegments(): List<Segment> {
        val n = imageUris.size
        val dur = max(durationMs, 1L)
        if (n == 0) return listOf(Segment(-1, 0L, dur, 0))

        if (settings.mapping == ImageMapping.PerCue && cues.isNotEmpty()) {
            val starts = ArrayList<Long>()
            starts.add(0L)
            for (c in cues.drop(1)) {
                if (c.startMs > starts.last() && c.startMs < dur) starts.add(c.startMs)
            }
            return starts.mapIndexed { k, s ->
                val end = if (k + 1 < starts.size) starts[k + 1] else dur
                Segment(k % n, s, end, k)
            }
        }
        return (0 until n).map { k ->
            Segment(k, dur * k / n, dur * (k + 1) / n, k)
        }
    }

    private fun segmentIndexAt(t: Long): Int {
        while (segPtr + 1 < segments.size && t >= segments[segPtr + 1].startMs) segPtr++
        while (segPtr > 0 && t < segments[segPtr].startMs) segPtr--
        return segPtr
    }

    private fun cueIndexAt(t: Long): Int {
        if (cues.isEmpty()) return -1
        while (cuePtr + 1 < cues.size && cues[cuePtr + 1].startMs <= t) cuePtr++
        while (cuePtr > 0 && cues[cuePtr].startMs > t) cuePtr--
        // Overlapping cues: look back a few for one that is still on screen.
        var j = cuePtr
        val stop = max(0, cuePtr - 3)
        while (j >= stop) {
            if (t >= cues[j].startMs && t < cues[j].endMs) return j
            j--
        }
        return -1
    }

    private fun captionAlpha(t: Long, cueIdx: Int): Float {
        if (cueIdx < 0) return 0f
        val c = cues[cueIdx]
        val fadeIn = (t - c.startMs) / FADE_MS
        val fadeOut = (c.endMs - t) / FADE_MS
        return min(1f, min(fadeIn, fadeOut)).coerceIn(0f, 1f)
    }

    /** Draws the frame for time [tMs]. Returns false if it is identical to the previous one. */
    fun draw(tMs: Long): Boolean {
        val segIdx = segmentIndexAt(tMs)
        val seg = segments[segIdx]
        val cueIdx = cueIndexAt(tMs)
        val hasPhoto = seg.imageIdx >= 0

        val fadeMs = settings.crossfadeMs
        val inFade = hasPhoto && segIdx > 0 && fadeMs > 0 && (tMs - seg.startMs) < fadeMs
        val capAlpha = captionAlpha(tMs, cueIdx)
        val dynamic = (settings.kenBurns && hasPhoto) || inFade || (cueIdx >= 0 && capAlpha < 1f)

        if (drawnOnce && !dynamic && !lastDynamic && segIdx == lastSeg && cueIdx == lastCue) return false
        drawnOnce = true
        lastDynamic = dynamic
        lastSeg = segIdx
        lastCue = cueIdx

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (hasPhoto) {
            val progress = ((tMs - seg.startMs).toFloat() / max(1L, seg.endMs - seg.startMs)).coerceIn(0f, 1f)
            if (inFade) {
                val prev = segments[segIdx - 1]
                if (prev.imageIdx >= 0) drawPhoto(prev.imageIdx, prev.order, 1f, 255)
            }
            val alpha = if (inFade) (255f * (tMs - seg.startMs) / fadeMs).toInt().coerceIn(0, 255) else 255
            drawPhoto(seg.imageIdx, seg.order, progress, alpha)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
            prunePhotos(seg, segIdx)
        }

        if (cueIdx >= 0) drawCaption(cueIdx, capAlpha)
        return true
    }

    private fun photo(idx: Int): Bitmap? {
        if (!photos.containsKey(idx)) {
            photos[idx] = BitmapLoader.decode(ctx, imageUris[idx], width, height)
        }
        return photos[idx]
    }

    private fun prunePhotos(seg: Segment, segIdx: Int) {
        val keep = HashSet<Int>()
        keep.add(seg.imageIdx)
        if (segIdx > 0) keep.add(segments[segIdx - 1].imageIdx)
        val it = photos.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in keep) {
                e.value?.recycle()
                it.remove()
            }
        }
    }

    private fun drawPhoto(idx: Int, order: Int, progress: Float, alpha: Int) {
        val bmp = photo(idx) ?: return
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val cover = max(width / bw, height / bh)

        var zoom = 1f
        var panX = 0f
        var panY = 0f
        if (settings.kenBurns) {
            val e = progress * progress * (3f - 2f * progress) // smoothstep
            val inward = order % 2 == 0
            zoom = if (inward) 1f + 0.12f * e else 1.12f - 0.12f * e
            val dir = if (inward) 1f else -1f
            panX = dir * (progress - 0.5f) * 2f * 0.7f
            panY = -dir * (progress - 0.5f) * 2f * 0.35f
        }

        val s = cover * zoom
        val sw = bw * s
        val sh = bh * s
        val dx = (width - sw) / 2f + panX * (sw - width) / 2f
        val dy = (height - sh) / 2f + panY * (sh - height) / 2f

        matrix.reset()
        matrix.setScale(s, s)
        matrix.postTranslate(dx, dy)
        photoPaint.alpha = alpha
        canvas.drawBitmap(bmp, matrix, photoPaint)
    }

    private fun layoutFor(cueIdx: Int): StaticLayout {
        if (cueIdx == layoutCue) layout?.let { return it }
        val text = cues[cueIdx].text
        var size = base * settings.fontScale
        var tries = 0
        var l: StaticLayout
        do {
            textPaint.textSize = size
            l = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, maxTextWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.12f)
                .setIncludePad(false)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
            size *= 0.88f
            tries++
        } while (l.height > height * 0.42f && tries < 6)
        layout = l
        layoutCue = cueIdx
        return l
    }

    private fun drawCaption(cueIdx: Int, alpha: Float) {
        val l = layoutFor(cueIdx)
        val lh = l.height.toFloat()
        val paint = l.paint
        val textSize = paint.textSize

        val x = (width - maxTextWidth) / 2f
        val y = when (settings.position) {
            CaptionPosition.Bottom -> height - height * (if (portrait) 0.16f else 0.08f) - lh
            CaptionPosition.Center -> (height - lh) / 2f
            CaptionPosition.Top -> height * (if (portrait) 0.12f else 0.08f)
        }

        canvas.save()
        canvas.translate(x, y)
        when (settings.style) {
            CaptionStyle.Box -> {
                var widest = 0f
                for (i in 0 until l.lineCount) widest = max(widest, l.getLineWidth(i))
                val padX = textSize * 0.55f
                val padY = textSize * 0.30f
                val left = (maxTextWidth - widest) / 2f - padX
                boxPaint.color = ColorUtils.setAlphaComponent(0x000000, (0xB8 * alpha).toInt())
                canvas.drawRoundRect(left, -padY, left + widest + 2 * padX, lh + padY, textSize * 0.4f, textSize * 0.4f, boxPaint)
                paint.style = Paint.Style.FILL
                paint.color = ColorUtils.setAlphaComponent(settings.textColor, (255 * alpha).toInt())
                l.draw(canvas)
            }
            CaptionStyle.Outline -> {
                paint.style = Paint.Style.STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = textSize * 0.16f
                paint.color = ColorUtils.setAlphaComponent(0x000000, (255 * alpha).toInt())
                l.draw(canvas)
                paint.style = Paint.Style.FILL
                paint.color = ColorUtils.setAlphaComponent(settings.textColor, (255 * alpha).toInt())
                l.draw(canvas)
            }
            CaptionStyle.Shadow -> {
                paint.style = Paint.Style.FILL
                paint.setShadowLayer(textSize * 0.18f, 0f, textSize * 0.06f, ColorUtils.setAlphaComponent(0x000000, (230 * alpha).toInt()))
                paint.color = ColorUtils.setAlphaComponent(settings.textColor, (255 * alpha).toInt())
                l.draw(canvas)
                paint.clearShadowLayer()
            }
        }
        canvas.restore()
    }

    /** Frees cached photos. The frame [bitmap] stays valid and is owned by the caller. */
    fun close() {
        photos.values.forEach { it?.recycle() }
        photos.clear()
    }

    private companion object {
        const val FADE_MS = 140f
    }
}
