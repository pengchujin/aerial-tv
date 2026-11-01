package app.orchard.tv.wallpaper

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File

/**
 * Streams Apple's aerial films with Media3 ExoPlayer rather than the platform MediaPlayer, which on
 * TVs is the maker's own fork with its own network handling (TclNuPlayer on TCL 75S365C retried a
 * failing load 10 times over 34 s before reporting it, and every film started on a fresh connection).
 *
 * - One player per screensaver or wallpaper with the films kept (AerialSet) as a playlist, round and
 *   round, so the next film loads while the current one ends and they follow without a black gap.
 * - Short network timeouts and a single retry: a slow edge of Apple's CDN costs seconds, not a minute.
 * - These files keep their index (moov) at the very end, so a cold start is two round trips to Apple
 *   (the index, then the start of the film) before the first frame; [prepareSeek] and [prepareStart]
 *   fetch those ahead where the next start is known.
 * - Playback writes what it plays to a disk cache of up to [keepBytes], in every format: the few
 *   films kept come round again and play from disk the second time (owner, 2026-10-29; issue #843
 *   asked for a kept copy rather than the same film fetched again and again).
 */
@OptIn(UnstableApi::class)
object AerialStream {
    private const val TAG = "AerialStream"

    /** The index is 115–170 KB in these files; a generous tail covers it. */
    private const val TAIL_BYTES = 1L * 1024 * 1024

    /** The least the cache may take, where a quarter of the free space is less. */
    private const val CACHE_BYTES = 64L * 1024 * 1024

    /**
     * The most a kept film may take: Apple's films are 0.2-2.2 GB each in Dolby Vision and 0.2-1.1 GB
     * in 4K SDR, so the two of a short film fit and a long one is kept in part. Never more than a
     * quarter of the free space, for TVs with 8-16 GB in all (Sony 65X91J had 12 GB free, #843).
     */
    const val KEEP_MAX_BYTES = 3_000_000_000L

    /** The cache's limit; set when the cache opens. */
    @Volatile
    var keepBytes = CACHE_BYTES
        private set

    @Volatile
    private var cache: SimpleCache? = null
    private val evictor = AerialEvictor { keepBytes }

    /** One instance per process: SimpleCache locks its folder, and Home and the system screensaver share it. */
    private fun cache(context: Context): SimpleCache {
        return cache ?: synchronized(this) {
            cache ?: run {
                val dir = File(context.applicationContext.cacheDir, "aerials")
                // The limit before the cache opens: it reads what is on disk on a thread of its own,
                // and evicted down to 64 MB while the limit was still that (TCL MT5879, 320 MB to 172
                // after a restart).
                val kept = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                keepBytes = ((context.applicationContext.cacheDir.usableSpace + kept) / 4).coerceIn(CACHE_BYTES, KEEP_MAX_BYTES)
                SimpleCache(dir, evictor, StandaloneDatabaseProvider(context.applicationContext)).also { cache = it }
            }
        }
    }

    /** What [keepBytes] comes to on this TV now, for Personalize to say; without opening the cache. */
    fun keepLimit(context: Context): Long =
        ((context.applicationContext.cacheDir.usableSpace + (cache?.cacheSpace ?: 0L)) / 4)
            .coerceIn(CACHE_BYTES, KEEP_MAX_BYTES)

    /** Bytes on disk now, and the limit; for Diagnostics. */
    fun usage(context: Context): Pair<Long, Long> = cache(context).cacheSpace to keepBytes

    private fun http() = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(5_000)
        .setReadTimeoutMs(8_000)

    fun url(film: Aerial, quality: AerialQuality, overHttp: Boolean): String =
        film.url(quality).let { if (overHttp) it.replaceFirst("https://", "http://") else it }

    /** Keyed by film and quality, not address, so the cached opener serves HTTP and HTTPS alike. */
    private fun key(film: Aerial, quality: AerialQuality) = "${film.id}@${quality.key}"

    fun mediaItem(film: Aerial, quality: AerialQuality, overHttp: Boolean): MediaItem =
        MediaItem.Builder().setUri(url(film, quality, overHttp)).setMediaId(film.id).setCustomCacheKey(key(film, quality)).build()

    /**
     * [decodeOnScreen]: the decoder starts only once the player has its SurfaceView, not on
     * ExoPlayer's off-screen placeholder surface. Xiaomi MiTV-MFTR0's Dolby Vision decoder set up on
     * the placeholder copies its frames out through the GPU (MStar's DIP), and keeps doing so on the
     * SurfaceView it is handed later: the film plays, but not in Dolby Vision (issue #754).
     */
    fun player(context: Context, quality: AerialQuality, decodeOnScreen: Boolean = false): ExoPlayer {
        val cache = cache(context)
        val source = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(http())
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // ExoPlayer's default video buffer is sized for phones (up to 128 MB); TVs here have 1–2 GB.
        // 24 MB is 10–13 s of 1080p film, which is also when the next film starts loading; the 4K
        // files get about as many seconds.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 30_000, 1_000, 2_000)
            .setTargetBufferBytes(
                when (quality) {
                    AerialQuality.DolbyVision -> 48
                    AerialQuality.Uhd -> 32
                    else -> 24
                } * 1024 * 1024,
            )
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val builder = if (decodeOnScreen) ExoPlayer.Builder(context, OnScreenRenderers(context)) else ExoPlayer.Builder(context)
        return builder
            .setLoadControl(load)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(source).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1)),
            )
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ALL
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            }
    }

    /** ExoPlayer's renderers, with a video renderer that never decodes onto the placeholder surface. */
    private class OnScreenRenderers(context: Context) : DefaultRenderersFactory(context) {
        override fun buildVideoRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: Handler,
            eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
            out: ArrayList<Renderer>,
        ) {
            out += object : MediaCodecVideoRenderer(
                context, mediaCodecSelector, allowedVideoJoiningTimeMs, enableDecoderFallback,
                eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
            ) {
                override fun shouldUsePlaceholderSurface(codecInfo: MediaCodecInfo) = false
            }
        }
    }

    /** The boxes before the film's data: the player reads them, then seeks to the index. */
    private const val LEAD_BYTES = 256L * 1024

    /**
     * Fetches the index and the first boxes of [film] in [quality], about 1.3 MB, so that starting
     * it mid-film needs one request to Apple, for the moment itself, rather than three. For the
     * other format's player: the Dolby Vision screensaver going on from the aerial wallpaper's film,
     * and the wallpaper going on from the screensaver's.
     */
    suspend fun prepareSeek(context: Context, film: Aerial, quality: AerialQuality, overHttp: Boolean) =
        runInterruptible(Dispatchers.IO) {
            val cache = cache(context)
            val key = key(film, quality)
            val known = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            if (known > 0 && cache.isCached(key, 0, LEAD_BYTES) && cache.isCached(key, known - TAIL_BYTES, TAIL_BYTES)) return@runInterruptible
            val started = SystemClock.elapsedRealtime()
            runCatching {
                val uri = android.net.Uri.parse(url(film, quality, overHttp))
                val length = if (known > 0) known else contentLength(uri)
                ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, length) }
                    .let { cache.applyContentMetadataMutations(key, it) }
                val writer = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http())
                for ((position, size) in listOf(0L to LEAD_BYTES, length - TAIL_BYTES to TAIL_BYTES)) {
                    val spec = DataSpec.Builder().setUri(uri).setKey(key).setPosition(position).setLength(size).build()
                    CacheWriter(writer.createDataSource(), spec, null, null).cache()
                }
                Log.i(TAG, "index of $key fetched in ${SystemClock.elapsedRealtime() - started} ms")
            }.onFailure { Log.w(TAG, "could not fetch the index of $key", it) }
        }

    /** About 2 s of Dolby Vision film (24 Mbps), 4 of 4K SDR: enough to start without the network. */
    private const val START_BYTES = 6L * 1024 * 1024

    /** A moment fetched ahead by [prepareStart]: film id, quality and where in it. */
    class Start(val film: String, val quality: AerialQuality, val atMs: Long)

    @Volatile
    var prepared: Start? = null
        private set

    /**
     * Fetches [film] in [quality] from [atMs] on, about 2-4 s of it, so a player started there
     * plays from disk at once (0.5 s to the first frame on Xiaomi MiTV-MFTR0, against 1.8-2.7 s
     * from Apple). For the Dolby Vision screensaver, fetched a few seconds before it starts at the
     * moment the wallpaper's film will be at then (MainActivity). The byte where that moment starts
     * comes from the film's index, read with Media3's MP4 extractor.
     */
    suspend fun prepareStart(context: Context, film: Aerial, quality: AerialQuality, overHttp: Boolean, atMs: Long) {
        prepareSeek(context, film, quality, overHttp)
        runInterruptible(Dispatchers.IO) {
            val cache = cache(context)
            val key = key(film, quality)
            val started = SystemClock.elapsedRealtime()
            runCatching {
                val uri = android.net.Uri.parse(url(film, quality, overHttp))
                val reader = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http()).setCacheWriteDataSinkFactory(null)
                val seekMap = seekMap(reader, uri, key)
                if (atMs * 1000 > seekMap.durationUs - 3_000_000) error("too near the end")
                val position = seekMap.getSeekPoints(atMs * 1000).first.position
                val writer = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http())
                val spec = DataSpec.Builder().setUri(uri).setKey(key).setPosition(position).setLength(START_BYTES).build()
                CacheWriter(writer.createDataSource(), spec, null, null).cache()
                prepared = Start(film.id, quality, atMs)
                Log.i(TAG, "$key at $atMs ms fetched ahead in ${SystemClock.elapsedRealtime() - started} ms")
            }.onFailure { Log.w(TAG, "could not fetch $key at $atMs ms ahead", it) }
        }
    }

    /** The film's index, as the player reads it: the boxes at the start, then the index at the end. */
    private fun seekMap(factory: CacheDataSource.Factory, uri: android.net.Uri, key: String): SeekMap {
        var map: SeekMap? = null
        val extractor = Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = DiscardingTrackOutput()
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) { map = seekMap }
        })
        val holder = PositionHolder()
        var position = 0L
        repeat(8) {
            val source = factory.createDataSource()
            try {
                val length = source.open(DataSpec.Builder().setUri(uri).setKey(key).setPosition(position).build())
                val input = DefaultExtractorInput(source, position, if (length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else position + length)
                var result = Extractor.RESULT_CONTINUE
                while (result == Extractor.RESULT_CONTINUE && map == null) result = extractor.read(input, holder)
                map?.let { return it }
                if (result != Extractor.RESULT_SEEK) error("no index")
                position = holder.position
            } finally {
                source.close()
            }
        }
        error("no index after 8 seeks")
    }

    /** The file's size, from the Content-Range of a one-byte request. */
    private fun contentLength(uri: android.net.Uri): Long {
        val source = http().createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(uri).setPosition(0).setLength(1).build())
            val range = source.responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value?.firstOrNull()
            return range?.substringAfterLast('/')?.toLongOrNull() ?: error("no Content-Range: $range")
        } finally {
            source.close()
        }
    }
}

/** Media3's least-recently-used evictor, with its limit read when it is needed ([AerialStream.keepBytes]). */
@OptIn(UnstableApi::class)
private class AerialEvictor(private val limit: () -> Long) : CacheEvictor {
    private val spans = java.util.TreeSet<CacheSpan> { a, b ->
        if (a.lastTouchTimestamp != b.lastTouchTimestamp) a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp) else a.compareTo(b)
    }
    private var size = 0L

    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        spans.add(span)
        size += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        spans.remove(span)
        size -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    fun evict(cache: Cache, required: Long) {
        val max = limit()
        while (size + required > max && spans.isNotEmpty()) cache.removeSpan(spans.first())
    }
}
