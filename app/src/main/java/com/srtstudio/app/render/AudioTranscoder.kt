package com.srtstudio.app.render

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * Converts any audio the phone can decode (MP3, WAV, OGG, FLAC, AMR, Opus...) into an AAC .m4a,
 * which the video muxer can then copy straight into the MP4 without re-encoding again.
 */
object AudioTranscoder {

    private const val TIMEOUT_US = 0L

    fun transcodeToAac(
        ctx: Context,
        source: Uri,
        out: File,
        ensureActive: () -> Unit,
        onProgress: (Float) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(ctx, source, null)
            val track = findAudioTrack(extractor)
            require(track >= 0) { "No audio track found in that file" }
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val totalUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            val dec = MediaCodec.createDecoderByType(mime)
            decoder = dec
            dec.configure(inFormat, null, null, 0)
            dec.start()

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()

            // Decoded PCM waiting to be handed to the encoder (its buffers are a different size).
            val pending = ArrayDeque<ByteArray>()
            var headOffset = 0

            var sampleRate = 0
            var channels = 0
            var fedBytes = 0L

            var extractorDone = false
            var decoderDone = false
            var encoderEosSent = false
            var encoderDone = false
            var muxTrack = -1
            var muxStarted = false

            fun startEncoder(format: MediaFormat) {
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                require(channels in 1..2) { "Audio with $channels channels isn't supported yet (use mono or stereo)" }
                val encFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 96_000 else 160_000)
                }
                val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                encoder = enc
                enc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                enc.start()
                muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }

            while (!encoderDone) {
                ensureActive()
                var didWork = false

                // 1. Compressed audio -> decoder
                if (!extractorDone) {
                    val idx = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        didWork = true
                        val buf = dec.getInputBuffer(idx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            dec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            if (totalUs > 0) onProgress((extractor.sampleTime.toFloat() / totalUs).coerceIn(0f, 1f))
                            extractor.advance()
                        }
                    }
                }

                // 2. Decoder -> PCM queue (bounded so memory stays small)
                if (!decoderDone && pending.size < 8) {
                    val idx = dec.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        didWork = true
                        if (encoder == null) startEncoder(dec.outputFormat)
                    } else if (idx >= 0) {
                        didWork = true
                        if (encoder == null) startEncoder(dec.outputFormat)
                        if (decInfo.size > 0) {
                            val bytes = ByteArray(decInfo.size)
                            val ob = dec.getOutputBuffer(idx)!!
                            ob.position(decInfo.offset)
                            ob.limit(decInfo.offset + decInfo.size)
                            ob.get(bytes)
                            pending.addLast(bytes)
                        }
                        dec.releaseOutputBuffer(idx, false)
                        if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                    }
                }

                // 3. PCM queue -> encoder
                val enc = encoder
                if (enc != null && !encoderEosSent && (pending.isNotEmpty() || decoderDone)) {
                    val idx = enc.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        didWork = true
                        val ib: ByteBuffer = enc.getInputBuffer(idx)!!
                        ib.clear()
                        var filled = 0
                        while (pending.isNotEmpty() && ib.remaining() > 0) {
                            val head = pending.first()
                            val n = minOf(head.size - headOffset, ib.remaining())
                            ib.put(head, headOffset, n)
                            headOffset += n
                            filled += n
                            if (headOffset >= head.size) {
                                pending.removeFirst()
                                headOffset = 0
                            }
                        }
                        val ptsUs = (fedBytes / (2L * channels)) * 1_000_000L / sampleRate
                        if (filled == 0 && decoderDone) {
                            enc.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encoderEosSent = true
                        } else {
                            enc.queueInputBuffer(idx, 0, filled, ptsUs, 0)
                            fedBytes += filled
                        }
                    }
                }

                // 4. Encoder -> muxer
                if (enc != null) {
                    val idx = enc.dequeueOutputBuffer(encInfo, TIMEOUT_US)
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        didWork = true
                        muxTrack = muxer!!.addTrack(enc.outputFormat)
                        muxer!!.start()
                        muxStarted = true
                    } else if (idx >= 0) {
                        didWork = true
                        val ob = enc.getOutputBuffer(idx)!!
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                        if (encInfo.size > 0 && muxStarted) {
                            ob.position(encInfo.offset)
                            ob.limit(encInfo.offset + encInfo.size)
                            muxer!!.writeSampleData(muxTrack, ob, encInfo)
                        }
                        enc.releaseOutputBuffer(idx, false)
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                    }
                }

                if (!didWork) Thread.sleep(2)
            }
            check(muxStarted) { "Audio conversion produced no data" }
            muxer!!.stop()
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("audio/")) return i
        }
        return -1
    }
}
