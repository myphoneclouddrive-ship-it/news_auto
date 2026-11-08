@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.digitalcrafting25.newsapp

import android.content.Context
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * এডিটরের আসল প্রিভিউ প্লেয়ার (ExoPlayer):
 *  - সব ক্লিপ একটা প্লেলিস্ট (ট্রিম সহ) — এডিটর খুললেই/ক্লিপ বদলালেই আগে থেকে prepare হয়ে থাকে
 *  - ক্লিপের আসল শব্দ (মিউট/ভলিউম ≤১০০%) বাজে
 *  - অডিও লেয়ার (ফাইল/ভয়েস/এক্সট্রাক্ট) আলাদা প্লেয়ারে নিজ নিজ সময়ে বাজে;
 *    ফেড ইন/আউট ও নয়েজ কমানোও প্রিভিউয়ে শোনা যায় (রেন্ডারের একই হিসাব)
 *  - বিপ লেয়ার চলার সময় বাকি সব শব্দ চুপ + একটা বিপ টোন
 * ছবি-ক্লিপের ছবি এডিটর নিজে আঁকে (ExoPlayer শুধু সময় গোনে)
 * মনে রাখুন: সব মেথড মেইন থ্রেড থেকে ডাকতে হবে
 */
class PreviewPlayer(private val ctx: Context) {
    // ডিকোডার না পেলে সফটওয়্যার ডিকোডারে নামে (ইন্ট্রো বানানোর এনকোডার চলার সময় হার্ডওয়্যার কোডেক ফুরালে প্রিভিউ আটকে যেত)
    // ক্লিপের নিজের শব্দের লাইভ নয়েজ কমানো (অডিও এক্সট্রাক্ট ছাড়াই): প্রধান প্লেয়ারের অডিও সিঙ্কে বসানো
    private val clipDenoise = PreviewDenoise()

    private fun rendererFactory(dn: PreviewDenoise): DefaultRenderersFactory {
        val rf = object : DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(dn))
                    .build()
        }
        rf.setEnableDecoderFallback(true)
        return rf
    }

    val player: ExoPlayer = ExoPlayer.Builder(ctx, rendererFactory(clipDenoise)).build()
    /** এখনকার ভিডিওর আসল প্রস্থ÷উচ্চতা (ছবি চ্যাপ্টা না করতে প্রিভিউ এটা দিয়ে মাপ ঠিক করে) */
    var videoAspect by mutableFloatStateOf(16f / 9f)
        private set

    /** প্লেয়ারের সর্বশেষ এরর (ডিকোড/সোর্স সমস্যা); READY হলে মুছে যায়। এডিটর প্লে আটকে গেলে এটা দেখায় */
    var lastError by mutableStateOf<String?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var retries = 0

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { retries = 0; lastError = null }
            }

            // আগে এরর ধরা হতো না: ExoPlayer চুপচাপ IDLE-এ চলে যেত, লেন্থ দেখাত (মডেল থেকে) কিন্তু ভিডিও থাকত না
            override fun onPlayerError(error: PlaybackException) {
                lastError = error.errorCodeName + (error.message?.let { ": $it" } ?: "")
                if (clips.isEmpty() || retries >= 3) return
                retries++
                val idx = player.currentMediaItemIndex.coerceAtLeast(0)
                val pos = player.currentPosition.coerceAtLeast(0L)
                val wasPlaying = player.playWhenReady
                main.postDelayed({
                    runCatching {
                        if (retries >= 2) { sig = ""; setClips(clips) }   // শেষ চেষ্টায় পুরো প্লেলিস্ট নতুন করে
                        else player.prepare()
                        if (idx < player.mediaItemCount) player.seekTo(idx, pos)
                        player.playWhenReady = wasPlaying
                    }
                }, 250L * retries)
            }

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
    private val denoisers = HashMap<Long, PreviewDenoise>()
    private var tone: AudioTrack? = null
    private var toneOn = false

    /** ক্লিপ তালিকা বদলেছে কিনা বোঝার চাবি */
    companion object {
        fun signature(clips: List<Clip>): String =
            clips.joinToString("|") { "${it.id}:${it.uri}:${it.startSec}:${it.endSec}:${it.imageSec}:${it.isImage}" }
    }

    val hasClips: Boolean get() = clips.isNotEmpty()

    /** প্লেলিস্ট জোর করে নতুন করে বানায় (ব্যাকগ্রাউন্ড থেকে ফিরলে / কার্ড বানানো শেষে) */
    fun refresh(list: List<Clip>) { sig = ""; retries = 0; setClips(list) }

    /**
     * ইন্ট্রো/আউট্রো এনকোড চলার সময় প্লেয়ারের ডিকোডার ও অডিও-লেয়ার প্লেয়ার ছেড়ে দেয়,
     * যাতে ফোনের সীমিত হার্ডওয়্যার কোডেক এনকোডারের জন্য ফাঁকা থাকে। শেষে refresh() ডাকুন।
     */
    fun suspendForEncode() {
        pause()
        runCatching { player.stop(); player.clearMediaItems() }
        sig = ""
        stopTone()
        audio.values.forEach { runCatching { it.release() } }
        audio.clear(); audioUri.clear(); denoisers.clear()
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

    fun play() {
        if (clips.isEmpty()) return
        // এরর খেয়ে IDLE-এ আটকে থাকলে প্লে চাপলেই নতুন করে সাজিয়ে নেয়
        if (player.playbackState == Player.STATE_IDLE) { retries = 0; sig = ""; setClips(clips) }
        if (player.playbackState == Player.STATE_ENDED) seekTo(0)
        player.playWhenReady = true
    }
    fun pause() {
        player.playWhenReady = false
        audio.values.forEach { it.playWhenReady = false }
        stopTone()
        NoiseMeter.clear()
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
        // ক্লিপের নয়েজ কমানো: স্লাইডার টানলে প্লে চলা অবস্থায় সাথে সাথে শোনা যায়
        clipDenoise.amount = if (c == null || c.isImage || c.muted) 0f else c.noise
        var clipMetered = false
        var layerMetered = false
        if (playing && clipDenoise.enabled) { NoiseMeter.setClip(clipDenoise.inLevel, clipDenoise.outLevel); clipMetered = true }

        // অডিও লেয়ার
        val live = auds.filter { it.uri != null }.map { it.id }.toSet()
        audio.keys.filter { it !in live }.forEach { id -> audio.remove(id)?.release(); audioUri.remove(id); denoisers.remove(id) }
        val totalMs = clips.sumOf { it.lengthMs() }
        for (a in auds) {
            val u = a.uri ?: continue
            val active = playing && ms >= a.startMs && ms < a.endMs
            var p = audio[a.id]
            if (p == null && !active) continue   // চলার সময় না হলে প্লেয়ার বানাই না (কোডেক বাঁচে)
            if (p == null || audioUri[a.id] != u.toString()) {
                p?.release()
                val dn = PreviewDenoise()
                denoisers[a.id] = dn
                // এই লেয়ারের নিজস্ব অডিও সিঙ্কে নয়েজ-কমানোর প্রসেসর বসানো (বন্ধ থাকলে অবিকল পাস)
                val rf = rendererFactory(dn)
                p = ExoPlayer.Builder(ctx, rf).build().also { it.setMediaItem(MediaItem.fromUri(u)); it.prepare() }
                audio[a.id] = p; audioUri[a.id] = u.toString()
            }
            denoisers[a.id]?.amount = if (a.denoise) a.denoiseAmt else 0f
            if (active && a.denoise) { denoisers[a.id]?.let { NoiseMeter.setLayer(a.id, it.inLevel, it.outLevel) }; layerMetered = true }
            p.volume = if (a.muted || beepOn) 0f else (a.volume * fadeEnv(a, ms, totalMs)).coerceIn(0f, 1f)
            if (active && !p.playWhenReady) {
                p.seekTo(a.sourceOffsetMs + (ms - a.startMs))
                p.playWhenReady = true
            } else if (!active && p.playWhenReady) {
                p.playWhenReady = false
            }
        }

        if (!clipMetered) NoiseMeter.setClip(0f, 0f)
        if (!layerMetered) NoiseMeter.setLayer(0L, 0f, 0f)
        if (beepOn) startTone(beepLayer!!, ms) else stopTone()
    }

    /**
     * ফেড ইন/আউটের গুণক (০..১) — AudioMixer.Sink-এর হিসাবের হুবহু:
     * শুরু থেকে দূরত্ব ÷ ফেড ইন, আর (মোট দৈর্ঘ্য − দূরত্ব) ÷ ফেড আউট; লেয়ারের দৈর্ঘ্য ভিডিওর মোট দৈর্ঘ্যে ছাঁটা।
     */
    private fun fadeEnv(a: AudioLayer, ms: Long, totalMs: Long): Float {
        if (a.fadeInMs <= 0L && a.fadeOutMs <= 0L) return 1f
        val start = a.startMs.coerceAtLeast(0L)
        val len = (minOf(a.endMs, if (totalMs > 0L) totalMs else a.endMs) - start).coerceAtLeast(0L)
        val rel = (ms - start).coerceIn(0L, len)
        var env = 1f
        if (a.fadeInMs > 0L && rel < a.fadeInMs) env = rel / a.fadeInMs.toFloat()
        if (a.fadeOutMs > 0L && len - rel < a.fadeOutMs) env = minOf(env, (len - rel) / a.fadeOutMs.toFloat())
        return env.coerceIn(0f, 1f)
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
        denoisers.clear()
        main.removeCallbacksAndMessages(null)
        player.release()
    }
}
