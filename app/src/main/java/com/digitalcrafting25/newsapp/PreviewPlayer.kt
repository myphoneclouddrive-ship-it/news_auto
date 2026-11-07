@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.digitalcrafting25.newsapp

import android.content.Context
import android.media.AudioTrack
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

/**
 * এডিটরের আসল প্রিভিউ প্লেয়ার (ExoPlayer):
 *  - সব ক্লিপ একটা প্লেলিস্ট (ট্রিম সহ) — এডিটর খুললেই/ক্লিপ বদলালেই আগে থেকে prepare হয়ে থাকে
 *  - ক্লিপের আসল শব্দ (মিউট/ভলিউম ≤১০০%) বাজে
 *  - অডিও লেয়ার (ফাইল/ভয়েস/এক্সট্রাক্ট) আলাদা প্লেয়ারে নিজ নিজ সময়ে বাজে
 *  - বিপ লেয়ার চলার সময় বাকি সব শব্দ চুপ + একটা বিপ টোন
 * ছবি-ক্লিপের ছবি এডিটর নিজে আঁকে (ExoPlayer শুধু সময় গোনে)
 * মনে রাখুন: সব মেথড মেইন থ্রেড থেকে ডাকতে হবে
 */
class PreviewPlayer(private val ctx: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(ctx).build().apply {
    }
    /** এখনকার ভিডিওর আসল প্রস্থ÷উচ্চতা (ছবি চ্যাপ্টা না করতে প্রিভিউ এটা দিয়ে মাপ ঠিক করে) */
    var videoAspect by mutableFloatStateOf(16f / 9f)
        private set

    init {
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                var w = videoSize.width * videoSize.pixelWidthHeightRatio
                var h = videoSize.height.toFloat()
                if (videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270) { val t = w; w = h; h = t }
                videoAspect = w / h
            }
        })
    }

    private var clips: List<Clip> = emptyList()
    private var starts: List<Long> = emptyList()
    private var sig = ""
    private val audio = HashMap<Long, ExoPlayer>()
    private val audioUri = HashMap<Long, String>()
    private var tone: AudioTrack? = null
    private var toneOn = false

    /** ক্লিপ তালিকা বদলেছে কিনা বোঝার চাবি */
    companion object {
        fun signature(clips: List<Clip>): String =
            clips.joinToString("|") { "${it.id}:${it.uri}:${it.startSec}:${it.endSec}:${it.imageSec}:${it.isImage}" }
    }

    fun setClips(list: List<Clip>) {
        val s = signature(list)
        if (s == sig) { clips = list; return }   // শুধু ভলিউম/মিউট/জুম বদল — প্লেলিস্ট একই
        sig = s
        clips = list
        var acc = 0L
        starts = list.map { val st = acc; acc += it.lengthMs(); st }
        val items = list.map { c ->
            val b = MediaItem.Builder().setUri(c.uri)
            if (c.isImage) {
                val mime = runCatching { ctx.contentResolver.getType(c.uri) }.getOrNull()
                    ?: if (c.uri.toString().endsWith(".png", true)) "image/png" else "image/jpeg"
                b.setMimeType(mime).setImageDurationMs((c.imageSec * 1000f).toLong().coerceAtLeast(100L))
            } else {
                val cc = MediaItem.ClippingConfiguration.Builder().setStartPositionMs((c.startSec * 1000f).toLong())
                if (c.endSec > 0f) cc.setEndPositionMs((c.endSec * 1000f).toLong())
                b.setClippingConfiguration(cc.build())
            }
            b.build()
        }
        player.setMediaItems(items, /* resetPosition = */ true)
        if (items.isNotEmpty()) player.prepare()
        player.playWhenReady = false
    }

    /** টাইমলাইনের সময়ে যাও (ক্লিপ-সীমার ভেতরে) */
    fun seekTo(ms: Long) {
        if (clips.isEmpty()) return
        var acc = 0L
        for ((i, c) in clips.withIndex()) {
            val len = c.lengthMs()
            if (ms < acc + len || i == clips.lastIndex) {
                player.seekTo(i, (ms - acc).coerceIn(0L, maxOf(0L, len - 1)))
                return
            }
            acc += len
        }
    }

    fun play() { if (clips.isNotEmpty()) { if (player.playbackState == Player.STATE_ENDED) seekTo(0); player.playWhenReady = true } }
    fun pause() {
        player.playWhenReady = false
        audio.values.forEach { it.playWhenReady = false }
        stopTone()
    }

    fun ended(): Boolean = player.playbackState == Player.STATE_ENDED

    /** এখনকার টাইমলাইন সময় */
    fun positionMs(): Long {
        val i = player.currentMediaItemIndex
        if (i !in starts.indices) return 0L
        val len = clips[i].lengthMs()
        return starts[i] + player.currentPosition.coerceIn(0L, len)
    }

    /** প্রতি ফ্রেমে ডাকুন: অডিও লেয়ার/বিপ/ক্লিপ-ভলিউম মেলায় */
    fun syncLayers(layers: List<Layer>, ms: Long, playing: Boolean) {
        val auds = layers.filterIsInstance<AudioLayer>()
        // বিপ HTML-এর মতো সর্বোচ্চ ০.৬ সেকেন্ড (BeepSound); এর বাইরে বাকি শব্দ চুপ থাকে না
        val beepLayer = auds.firstOrNull {
            it.uri == null && it.sourceClipId == null && ms >= it.startMs &&
                ms < minOf(it.endMs, it.startMs + (BeepSound.LEN_MS - it.sourceOffsetMs).coerceAtLeast(0L))
        }
        val beepOn = playing && beepLayer != null

        // ক্লিপের আসল শব্দ
        val ci = player.currentMediaItemIndex
        val c = clips.getOrNull(ci)
        player.volume = if (c == null || c.muted || beepOn) 0f else c.volume.coerceIn(0f, 1f)

        // অডিও লেয়ার
        val live = auds.filter { it.uri != null }.map { it.id }.toSet()
        audio.keys.filter { it !in live }.forEach { id -> audio.remove(id)?.release(); audioUri.remove(id) }
        for (a in auds) {
            val u = a.uri ?: continue
            val active = playing && ms >= a.startMs && ms < a.endMs
            var p = audio[a.id]
            if (p == null || audioUri[a.id] != u.toString()) {
                p?.release()
                p = ExoPlayer.Builder(ctx).build().also { it.setMediaItem(MediaItem.fromUri(u)); it.prepare() }
                audio[a.id] = p; audioUri[a.id] = u.toString()
            }
            p.volume = if (a.muted || beepOn) 0f else a.volume.coerceIn(0f, 1f)
            if (active && !p.playWhenReady) {
                p.seekTo(a.sourceOffsetMs + (ms - a.startMs))
                p.playWhenReady = true
            } else if (!active && p.playWhenReady) {
                p.playWhenReady = false
            }
        }

        if (beepOn) startTone(beepLayer!!, ms) else stopTone()
    }

    private fun startTone(l: AudioLayer, ms: Long) {
        if (toneOn) return
        toneOn = true
        runCatching {
            tone?.let { runCatching { it.stop() }; runCatching { it.release() } }
            val off = l.sourceOffsetMs + (ms - l.startMs).coerceAtLeast(0L)
            val t = BeepSound.newTrack(if (l.muted) 0f else l.volume, off)
            tone = t
            t?.play()
        }
    }

    private fun stopTone() {
        if (!toneOn) return
        toneOn = false
        runCatching { tone?.stop() }
    }

    fun release() {
        stopTone()
        runCatching { tone?.stop() }
        runCatching { tone?.release() }
        audio.values.forEach { it.release() }
        audio.clear()
        player.release()
    }
}
