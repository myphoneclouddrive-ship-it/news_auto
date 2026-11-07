@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.digitalcrafting25.newsapp

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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
        videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        runCatching { setImageOutput { _, _ -> } }   // ছবি-ক্লিপ আমরা নিজে আঁকি; ছবির আউটপুট ফেলে দেওয়া
    }
    private var clips: List<Clip> = emptyList()
    private var starts: List<Long> = emptyList()
    private var sig = ""
    private val audio = HashMap<Long, ExoPlayer>()
    private val audioUri = HashMap<Long, String>()
    private var tone: ToneGenerator? = null
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
        val beepOn = playing && auds.any { it.uri == null && it.sourceClipId == null && ms >= it.startMs && ms < it.endMs }

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

        if (beepOn) startTone() else stopTone()
    }

    private fun startTone() {
        if (toneOn) return
        toneOn = true
        runCatching {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 80).also { tone = it }
            t.startTone(ToneGenerator.TONE_PROP_BEEP2, 60_000)
        }
    }

    private fun stopTone() {
        if (!toneOn) return
        toneOn = false
        runCatching { tone?.stopTone() }
    }

    fun release() {
        stopTone()
        runCatching { tone?.release() }
        audio.values.forEach { it.release() }
        audio.clear()
        player.release()
    }
}
