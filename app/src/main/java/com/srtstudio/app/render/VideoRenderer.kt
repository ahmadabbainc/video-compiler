package com.srtstudio.app.render

import android.content.Context
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import com.srtstudio.app.data.RenderRequest
import java.io.File
import java.nio.ByteBuffer

/**
 * Turns a [RenderRequest] into an H.264/AAC MP4 at [outFile].
 *
 * Blocking: call from a background thread. [ensureActive] is polled once per frame so the job can be
 * cancelled; throw from it to abort. Everything it opens is released in `finally`.
 */
class VideoRenderer(
    private val ctx: Context,
    private val req: RenderRequest,
    private val outFile: File,
    private val ensureActive: () -> Unit,
    private val onProgress: (stage: String, progress: Float) -> Unit,
) {
    private class AudioSource(
        val extractor: MediaExtractor,
        val format: MediaFormat,
        val durationUs: Long,
        val tempFile: File?,
    )

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var gl: EncoderGlSurface? = null
    private var audio: AudioSource? = null
    private var renderer: FrameRenderer? = null

    /** One-line result of reading the finished file back; shown to the user after the render. */
    var report: String? = null
        private set

    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false

    private val videoInfo = MediaCodec.BufferInfo()
    private val audioInfo = MediaCodec.BufferInfo()
    private val audioBuffer: ByteBuffer = ByteBuffer.allocate(1 shl 20)

    fun run() {
        val s = req.settings
        val w = s.size.width
        val h = s.size.height
        val fps = s.fps

        try {
            onProgress("Preparing…", 0f)
            val a = openAudio()
            audio = a

            val lastCueEnd = req.cues.maxOfOrNull { it.endMs } ?: 0L
            val durationUs = if (a != null) a.durationUs else (lastCueEnd + 600L) * 1000L
            require(durationUs > 0) { "Nothing to render: no subtitles and no audio" }
            val totalFrames = ((durationUs * fps + 999_999L) / 1_000_000L).toInt().coerceAtLeast(1)

            val typeface = req.fontPath?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
            val frames = FrameRenderer(ctx, s, w, h, req.imageUris, req.cues, durationUs / 1000L, typeface)
            renderer = frames

            // --- video encoder ---
            val bitrate = (w.toLong() * h * fps * 0.09).toInt().coerceIn(2_000_000, 16_000_000)
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = enc
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                // This is an offline render, not a live recording: ask for best-effort (non-realtime) encoding
                // so the phone's encoder never skips frames to "keep up", and keep frames in presentation order.
                setInteger(MediaFormat.KEY_PRIORITY, 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                // Constant-bitrate rate control is what makes many phone encoders drop frames on busy scenes.
                val vbr = runCatching {
                    enc.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        .encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                }.getOrDefault(false)
                if (vbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = enc.createInputSurface()
            enc.start()
            gl = EncoderGlSurface(surface, w, h)

            // --- muxer ---
            val mux = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            if (a != null) audioTrack = mux.addTrack(a.format)

            // --- frames ---
            onProgress("Rendering video…", 0f)
            for (i in 0 until totalFrames) {
                ensureActive()
                val tUs = i * 1_000_000L / fps
                val changed = frames.draw(tUs / 1000L)
                gl!!.draw(frames.bitmap, tUs * 1000L, changed)
                drain(false)
                writeAudioUpTo(tUs + 250_000L)
                if (i % 4 == 0) onProgress("Rendering video…", i.toFloat() / totalFrames)
            }

            onProgress("Finishing…", 1f)
            enc.signalEndOfInputStream()
            drain(true)
            writeAudioUpTo(durationUs)

            check(muxerStarted) { "The video encoder produced no output" }
            mux.stop()
            report = runCatching { verifyOutput(totalFrames, fps) }.getOrNull()
        } catch (t: Throwable) {
            outFile.delete()
            throw t
        } finally {
            release()
        }
    }

    // ---------------------------------------------------------------- video output

    private fun drain(endOfStream: Boolean) {
        val enc = encoder ?: return
        var idleSpins = 0
        while (true) {
            val idx = enc.dequeueOutputBuffer(videoInfo, if (endOfStream) 10_000L else 0L)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    // Waiting for the encoder to flush; give up only after ~30 s of silence.
                    ensureActive()
                    if (++idleSpins > 3_000) error("The video encoder stopped responding")
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "Encoder format changed twice" }
                    videoTrack = muxer!!.addTrack(enc.outputFormat)
                    muxer!!.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    idleSpins = 0
                    val buf = enc.getOutputBuffer(idx)!!
                    if (videoInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) videoInfo.size = 0
                    if (videoInfo.size > 0 && muxerStarted) {
                        buf.position(videoInfo.offset)
                        buf.limit(videoInfo.offset + videoInfo.size)
                        muxer!!.writeSampleData(videoTrack, buf, videoInfo)
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (videoInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    // ---------------------------------------------------------------- audio

    private fun openAudio(): AudioSource? {
        val uri = req.audioUri ?: return null

        var ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        var track = AudioTranscoder.findAudioTrack(ex)
        if (track < 0) {
            ex.release()
            error("No audio track found in the selected audio file")
        }
        var fmt = ex.getTrackFormat(track)

        if (fmt.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC) {
            ex.selectTrack(track)
            val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION)
            else probeDurationUs { it.setDataSource(ctx, uri) }
            return AudioSource(ex, fmt, dur, null)
        }

        // Not AAC: convert once, then copy the result into the video.
        ex.release()
        onProgress("Converting audio…", 0f)
        val tmp = File(ctx.cacheDir, "audio_tmp.m4a")
        tmp.delete()
        try {
            AudioTranscoder.transcodeToAac(ctx, uri, tmp, ensureActive) { onProgress("Converting audio…", it) }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }

        ex = MediaExtractor()
        ex.setDataSource(tmp.absolutePath)
        track = AudioTranscoder.findAudioTrack(ex)
        check(track >= 0) { "Converted audio has no track" }
        ex.selectTrack(track)
        fmt = ex.getTrackFormat(track)
        val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION)
        else probeDurationUs { it.setDataSource(tmp.absolutePath) }
        return AudioSource(ex, fmt, dur, tmp)
    }

    private fun probeDurationUs(configure: (MediaMetadataRetriever) -> Unit): Long {
        val r = MediaMetadataRetriever()
        return try {
            configure(r)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
        } finally {
            runCatching { r.release() }
        }
    }

    private fun writeAudioUpTo(limitUs: Long) {
        val a = audio ?: return
        val mux = muxer ?: return
        if (!muxerStarted || audioTrack < 0) return
        val ex = a.extractor
        while (true) {
            val t = ex.sampleTime
            if (t < 0 || t > limitUs) break
            audioBuffer.clear()
            val size = ex.readSampleData(audioBuffer, 0)
            if (size < 0) break
            val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            audioInfo.set(0, size, t, flags)
            mux.writeSampleData(audioTrack, audioBuffer, audioInfo)
            ex.advance()
        }
    }


    // ---------------------------------------------------------------- self check

    /**
     * Reads the finished MP4 back and compares what the phone's encoder really produced with what we
     * asked for: frame count, the worst gap between frames, and where video and audio end. If the encoder
     * dropped or re-timed frames, this is where it shows up.
     */
    private fun verifyOutput(expectedFrames: Int, fps: Int): String {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(outFile.absolutePath)
            var vTrack = -1
            var aTrack = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && vTrack < 0) vTrack = i
                if (mime.startsWith("audio/") && aTrack < 0) aTrack = i
            }
            check(vTrack >= 0) { "no video track" }

            ex.selectTrack(vTrack)
            val times = ArrayList<Long>(expectedFrames + 8)
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                times.add(t)
                ex.advance()
            }
            ex.unselectTrack(vTrack)
            times.sort()

            var maxGapUs = 0L
            var maxOffUs = 0L
            val stepUs = 1_000_000.0 / fps
            for (k in times.indices) {
                if (k > 0) maxGapUs = maxOf(maxGapUs, times[k] - times[k - 1])
                maxOffUs = maxOf(maxOffUs, Math.abs(times[k] - (k * stepUs).toLong()))
            }
            val videoEndUs = (times.lastOrNull() ?: 0L) + (stepUs).toLong()

            var audioEndUs = -1L
            if (aTrack >= 0) {
                ex.selectTrack(aTrack)
                while (true) {
                    val t = ex.sampleTime
                    if (t < 0) break
                    audioEndUs = t
                    ex.advance()
                }
            }

            val dropped = expectedFrames - times.size
            val ok = dropped <= expectedFrames / 200 && maxGapUs < 250_000L && maxOffUs < 250_000L
            val sb = StringBuilder(if (ok) "Sync check OK" else "Sync check: WARNING")
            sb.append(" · frames ${times.size}/$expectedFrames")
            sb.append(" · longest gap ${maxGapUs / 1000} ms")
            sb.append(" · timing error ${maxOffUs / 1000} ms")
            sb.append(" · video ends ${"%.2f".format(videoEndUs / 1e6)} s")
            if (audioEndUs >= 0) sb.append(" · audio ends ${"%.2f".format(audioEndUs / 1e6)} s")
            return sb.toString()
        } finally {
            runCatching { ex.release() }
        }
    }

    // ---------------------------------------------------------------- cleanup

    private fun release() {
        runCatching { renderer?.close() }
        runCatching { renderer?.bitmap?.recycle() }
        runCatching { gl?.release() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { muxer?.release() }
        audio?.let {
            runCatching { it.extractor.release() }
            it.tempFile?.delete()
        }
    }
}
