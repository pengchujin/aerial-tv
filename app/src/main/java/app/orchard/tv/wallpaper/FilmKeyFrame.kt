package app.orchard.tv.wallpaper

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser

/**
 * One key frame of a film, read and decoded in this process: Media3's MP4 extractor finds it in the
 * file, and the platform's software decoder decodes it to memory. Neither goes through the media
 * server's retriever, whose frames stuck inside TCL's own MP4 extractor (TclMPEG4Extractor) on TCL
 * MT5879 and jammed the media server until the TV restarted (FrameReader in LiveWallpaper). Only
 * key frames: one sample, nothing before it to decode. Only SDR files (the 1080p H.264 ones).
 */
@OptIn(UnstableApi::class)
internal object FilmKeyFrame {
    /** A decoded frame, with what it cost, for the diagnostics log. */
    class Frame(val bitmap: Bitmap, val timeMs: Long, val codec: String, val readMs: Long, val decodeMs: Long, val convertMs: Long, val sampleBytes: Int, val indexKept: Boolean)

    /**
     * The key frame of [uri] nearest [atMs] at [w]×[h], read through [source] under cache [key].
     * Blocking; throws on failure, and on interrupt between steps.
     */
    fun decode(source: DataSource.Factory, uri: Uri, key: String, atMs: Long, w: Int, h: Int): Frame {
        val started = SystemClock.elapsedRealtime()
        val kept = index?.takeIf { it.uri == uri && it.key == key }
        val sample = readKeySample(source, uri, key, atMs * 1000)
        val read = SystemClock.elapsedRealtime()
        val decoded = decodeSample(sample, w, h)
        return Frame(decoded.bitmap, sample.timeUs / 1000, decoded.codec, read - started, decoded.decodeMs, decoded.convertMs, sample.bytes.size, kept != null)
    }

    /**
     * The last film's index, read once: Mp4Extractor with its sample tables, so the next key frame
     * of the same film is one read of the frame alone, not three (the index at the end of the file
     * first, 0.6-0.9 s of the 0.65-1.2 s each read took on TCL MT5879). One film, some hundred KB.
     */
    private class Index(val uri: Uri, val key: String, val extractor: Mp4Extractor, val video: VideoOutput, val map: SeekMap)

    @Volatile private var index: Index? = null

    private class Sample(val format: Format, val bytes: ByteArray, val timeUs: Long)

    /** Collects the video track's samples; [taken] is the first key frame once it is whole. */
    private class VideoOutput : TrackOutput {
        var format: Format? = null
        var taken: Sample? = null
        private var buffer = ByteArray(512 * 1024)
        private var length = 0

        /** Ready for another key frame after a seek: nothing collected before it counts. */
        fun reset() {
            taken = null
            length = 0
        }

        override fun format(format: Format) {
            this.format = format
        }

        private fun room(more: Int) {
            if (length + more <= buffer.size) return
            // A key frame of Apple's 1080p files is 0.2-0.6 MB; a broken file must not take the heap.
            if (length + more > MAX_SAMPLE_BYTES) error("sample over ${MAX_SAMPLE_BYTES / 1024} KB")
            buffer = buffer.copyOf(maxOf(buffer.size * 2, length + more))
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            room(length)
            val read = input.read(buffer, this.length, length)
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw java.io.EOFException()
            }
            this.length += read
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            room(length)
            data.readBytes(buffer, this.length, length)
            this.length += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val end = length - offset
            val start = end - size
            if (taken == null && flags and C.BUFFER_FLAG_KEY_FRAME != 0 && start >= 0) {
                format?.let { taken = Sample(it, buffer.copyOfRange(start, end), timeUs) }
            }
            // What follows this sample belongs to the next one.
            System.arraycopy(buffer, end, buffer, 0, offset)
            length = offset
        }
    }

    /**
     * The film's index (at the end of Apple's files), then the key frame nearest [atUs]. The same
     * reads as ExoPlayer's: through the cache, so a film played from disk is read from disk.
     */
    private fun readKeySample(factory: DataSource.Factory, uri: Uri, key: String, atUs: Long): Sample {
        index?.takeIf { it.uri == uri && it.key == key }?.let { kept ->
            return runCatching { readFrom(factory, kept, atUs) }.getOrElse { e ->
                // A kept index that no longer reads (the extractor in an odd state): read afresh.
                if (e is InterruptedException) throw e
                index = null
                readKeySample(factory, uri, key, atUs)
            }
        }
        var map: SeekMap? = null
        val video = VideoOutput()
        var videoTaken = false
        val extractor = Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput =
                if (type == C.TRACK_TYPE_VIDEO && !videoTaken) video.also { videoTaken = true } else DiscardingTrackOutput()
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) { map = seekMap }
        })
        val holder = PositionHolder()
        var position = 0L
        repeat(MAX_OPENS) {
            if (Thread.interrupted()) throw InterruptedException()
            val source = factory.createDataSource()
            var kept: Index? = null
            try {
                val length = source.open(DataSpec.Builder().setUri(uri).setKey(key).setPosition(position).build())
                val input = DefaultExtractorInput(source, position, if (length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else position + length)
                while (true) {
                    val result = extractor.read(input, holder)
                    val found = map
                    if (found != null) {
                        val mime = video.format?.sampleMimeType
                        if (mime != MimeTypes.VIDEO_H264 && mime != MimeTypes.VIDEO_H265) error("video is $mime")
                        kept = Index(uri, key, extractor, video, found)
                        break
                    }
                    if (result == Extractor.RESULT_SEEK) {
                        position = holder.position
                        break
                    }
                    if (result == Extractor.RESULT_END_OF_INPUT) error("no index")
                }
            } finally {
                source.close()
            }
            // The index is read; the reads at the end of the file are closed.
            kept?.let { index = it; return readFrom(factory, it, atUs) }
        }
        error("no index after $MAX_OPENS reads")
    }

    /** With the index read: the key frame nearest [atUs], which may be anywhere in the file. */
    private fun readFrom(factory: DataSource.Factory, kept: Index, atUs: Long): Sample {
        val map = kept.map
        val points = map.getSeekPoints(atUs.coerceIn(0, maxOf(0, map.durationUs - 1)))
        val point = if (atUs - points.first.timeUs <= points.second.timeUs - atUs) points.first else points.second
        kept.extractor.seek(point.position, point.timeUs)
        kept.video.reset()
        val holder = PositionHolder()
        var position = point.position
        repeat(MAX_OPENS) {
            if (Thread.interrupted()) throw InterruptedException()
            val source = factory.createDataSource()
            try {
                val length = source.open(DataSpec.Builder().setUri(kept.uri).setKey(kept.key).setPosition(position).build())
                val input = DefaultExtractorInput(source, position, if (length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else position + length)
                while (true) {
                    val result = kept.extractor.read(input, holder)
                    kept.video.taken?.let { return it }
                    if (result == Extractor.RESULT_SEEK) {
                        position = holder.position
                        break
                    }
                    if (result == Extractor.RESULT_END_OF_INPUT) error("no key frame")
                }
            } finally {
                source.close()
            }
        }
        error("no key frame after $MAX_OPENS reads")
    }

    private class Decoded(val bitmap: Bitmap, val codec: String, val decodeMs: Long, val convertMs: Long)

    /** The names of the TV's decoders, or null where the list cannot be read (then all are tried). */
    private val installedCodecs: Set<String>? by lazy {
        runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos
                .filter { !it.isEncoder }.map { it.name }.toSet()
        }.getOrNull()
    }

    /** [sample] decoded by a software decoder, at [w]×[h], with that decoder's name and the time each step took. */
    private fun decodeSample(sample: Sample, w: Int, h: Int): Decoded {
        val started = SystemClock.elapsedRealtime()
        val mime = sample.format.sampleMimeType!!
        val names = if (mime == MimeTypes.VIDEO_H264) listOf("c2.android.avc.decoder", "OMX.google.h264.decoder")
        else listOf("c2.android.hevc.decoder", "OMX.google.hevc.decoder")
        var codec: MediaCodec? = null
        var name = ""
        // Only decoders the TV lists: asked for one it lacks, TCL tcl_m7642 (Android 9, which has no
        // c2 decoders) crashed the process in JMediaCodec's constructor instead of throwing, every
        // time a frame was read (issue #1267, three dropbox records, SIGSEGV on a null pointer).
        val listed = installedCodecs
        for (n in names.filter { listed == null || it in listed }) {
            codec = runCatching { MediaCodec.createByCodecName(n) }.getOrNull()
            if (codec != null) { name = n; break }
        }
        codec ?: error("no software decoder for $mime")
        try {
            val format = MediaFormat.createVideoFormat(mime, sample.format.width, sample.format.height)
            sample.format.initializationData.forEachIndexed { i, csd -> format.setByteBuffer("csd-$i", java.nio.ByteBuffer.wrap(csd)) }
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, sample.bytes.size + 1024)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + DECODE_MS
            var queued = false
            var ended = false
            while (SystemClock.elapsedRealtime() < deadline) {
                if (Thread.interrupted()) throw InterruptedException()
                if (!ended) {
                    val index = codec.dequeueInputBuffer(WAIT_US)
                    if (index >= 0) {
                        if (!queued) {
                            codec.getInputBuffer(index)!!.apply { clear(); put(sample.bytes) }
                            codec.queueInputBuffer(index, 0, sample.bytes.size, sample.timeUs, 0)
                            queued = true
                        } else {
                            // Nothing more: a decoder that holds frames for reordering lets this one out.
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            ended = true
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, WAIT_US)
                if (out < 0) continue
                if (info.size > 0) {
                    val decoded = SystemClock.elapsedRealtime()
                    val bitmap = codec.getOutputImage(out)?.use { toBitmap(it, w, h, sample.format.rotationDegrees, sample.format.pixelWidthHeightRatio) }
                    codec.releaseOutputBuffer(out, false)
                    val converted = SystemClock.elapsedRealtime()
                    return Decoded(bitmap ?: error("no image from $name"), name, decoded - started, converted - decoded)
                }
                codec.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) error("$name gave no frame")
            }
            error("$name took over $DECODE_MS ms")
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    /**
     * A YUV 4:2:0 image as an ARGB bitmap of [wanted]×[wantedH] at most its own size, scaled by picking the nearest pixel. BT.709 in
     * studio range, as Apple's SDR films are. Turned by [rotation] (a phone's upright film is kept
     * on its side with the turn in the file) and centre-cropped to the screen's shape, as the
     * player shows it (SCALE_TO_FIT_WITH_CROPPING): a portrait video was squashed into 16:9.
     */
    private fun toBitmap(image: android.media.Image, wanted: Int, wantedH: Int, rotation: Int = 0, pixelRatio: Float = 1f): Bitmap {
        if (image.format != ImageFormat.YUV_420_888) error("image format ${image.format}")
        val crop = image.cropRect
        val (yP, uP, vP) = image.planes.let { Triple(it[0], it[1], it[2]) }
        val y = yP.buffer.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
        val u = uP.buffer.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
        val v = vP.buffer.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
        val yRow = yP.rowStride; val yPix = yP.pixelStride
        val uRow = uP.rowStride; val uPix = uP.pixelStride
        val vRow = vP.rowStride; val vPix = vP.pixelStride
        val cw = crop.width(); val ch = crop.height()
        val turn = ((rotation % 360) + 360) % 360
        val side = turn == 90 || turn == 270
        // The film's width as shown, upright, in the stored frame's pixels.
        val shownW = if (side) ch else cw
        val par = if (pixelRatio > 0f) pixelRatio.toDouble() else 1.0
        val shownAspect = if (side) ch / (cw * par) else cw * par / ch
        val screenAspect = wanted.toDouble() / wantedH
        // The part of the shown film the screen holds, as a fraction of each side, centred.
        val fracW = if (shownAspect > screenAspect) screenAspect / shownAspect else 1.0
        val fracH = if (shownAspect > screenAspect) 1.0 else shownAspect / screenAspect
        // No bigger than the film's own pixels across that part: on a 4K screen the picture is
        // scaled when drawn, rather than made four times as large here by repeating pixels.
        val across = maxOf(1, (shownW * fracW).toInt())
        val w = minOf(wanted, across)
        val h = if (wanted > across) maxOf(1, (wantedH.toLong() * across / wanted).toInt()) else wantedH
        // Each output column and row as a place along the shown film's width and height (0 until 1).
        val colAt = DoubleArray(w) { (0.5 - fracW / 2) + (it + 0.5) / w * fracW }
        val rowAt = DoubleArray(h) { (0.5 - fracH / 2) + (it + 0.5) / h * fracH }
        // A place along the stored frame's width (x) or height (y) as a pixel, absolute in the image.
        fun px(f: Double) = crop.left + (f * cw).toInt().coerceIn(0, cw - 1)
        fun py(f: Double) = crop.top + (f * ch).toInt().coerceIn(0, ch - 1)
        // Shown (col, row) to stored (x, y): 90 is turned clockwise to show, so the shown top runs
        // down the stored left side's far end; 270 the other way.
        val xOfCol: IntArray; val yOfRow: IntArray; val xOfRow: IntArray; val yOfCol: IntArray
        when (turn) {
            90 -> { xOfRow = IntArray(h) { px(rowAt[it]) }; yOfCol = IntArray(w) { py(1 - colAt[it]) }; xOfCol = IntArray(0); yOfRow = IntArray(0) }
            180 -> { xOfCol = IntArray(w) { px(1 - colAt[it]) }; yOfRow = IntArray(h) { py(1 - rowAt[it]) }; xOfRow = IntArray(0); yOfCol = IntArray(0) }
            270 -> { xOfRow = IntArray(h) { px(1 - rowAt[it]) }; yOfCol = IntArray(w) { py(colAt[it]) }; xOfCol = IntArray(0); yOfRow = IntArray(0) }
            else -> { xOfCol = IntArray(w) { px(colAt[it]) }; yOfRow = IntArray(h) { py(rowAt[it]) }; xOfRow = IntArray(0); yOfCol = IntArray(0) }
        }
        val pixels = IntArray(w * h)
        // The chroma worked out once for the two pixels that share it: 1.793, 0.213, 0.533 and
        // 2.112, times 256 (BT.709); the luma's 1.164 and offset from [luma], the 0-255 range from
        // [clamp]. Worked out for each pixel with coerceIn, 1080p took 0.18-0.36 s on TCL MT5879.
        val luma = IntArray(256) { ((it - 16) * 298 + 128) }
        if (!side) {
            for (row in 0 until h) {
                val sy = yOfRow[row]
                val yBase = sy * yRow
                val uBase = (sy / 2) * uRow
                val vBase = (sy / 2) * vRow
                val outBase = row * w
                var lastCx = -1
                var rv = 0; var gv = 0; var bv = 0
                for (col in 0 until w) {
                    val sx = xOfCol[col]
                    val cx = sx / 2
                    if (cx != lastCx) {
                        val d = (u[uBase + cx * uPix].toInt() and 0xFF) - 128
                        val e = (v[vBase + cx * vPix].toInt() and 0xFF) - 128
                        rv = 459 * e
                        gv = -55 * d - 136 * e
                        bv = 541 * d
                        lastCx = cx
                    }
                    val c = luma[y[yBase + sx * yPix].toInt() and 0xFF]
                    val r = clamp[((c + rv) shr 8) + CLAMP_OFFSET]
                    val g = clamp[((c + gv) shr 8) + CLAMP_OFFSET]
                    val bb = clamp[((c + bv) shr 8) + CLAMP_OFFSET]
                    pixels[outBase + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bb
                }
            }
        } else {
            // On its side: a row of the picture is a column of the stored frame.
            for (row in 0 until h) {
                val sx = xOfRow[row]
                val cx = sx / 2
                val outBase = row * w
                for (col in 0 until w) {
                    val sy = yOfCol[col]
                    val d = (u[(sy / 2) * uRow + cx * uPix].toInt() and 0xFF) - 128
                    val e = (v[(sy / 2) * vRow + cx * vPix].toInt() and 0xFF) - 128
                    val c = luma[y[sy * yRow + sx * yPix].toInt() and 0xFF]
                    val r = clamp[((c + 459 * e) shr 8) + CLAMP_OFFSET]
                    val g = clamp[((c - 55 * d - 136 * e) shr 8) + CLAMP_OFFSET]
                    val bb = clamp[((c + 541 * d) shr 8) + CLAMP_OFFSET]
                    pixels[outBase + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bb
                }
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
    }

    /** 0-255 for any of -CLAMP_OFFSET until 256 + CLAMP_OFFSET: the BT.709 sums stay inside it. */
    private const val CLAMP_OFFSET = 384
    private val clamp = IntArray(256 + 2 * CLAMP_OFFSET) { (it - CLAMP_OFFSET).coerceIn(0, 255) }

    private const val MAX_SAMPLE_BYTES = 8 * 1024 * 1024
    private const val MAX_OPENS = 12
    private const val DECODE_MS = 3_000L
    private const val WAIT_US = 10_000L
}
