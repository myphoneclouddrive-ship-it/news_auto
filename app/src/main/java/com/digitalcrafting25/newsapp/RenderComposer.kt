package com.digitalcrafting25.newsapp

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.TimestampWrapper
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList

/**
 * কন্ট্রাস্ট: প্রতিটা রঙ-চ্যানেল মাঝ (০.৫) ঘিরে (১ + c) গুণ — প্রিভিউয়ের ColorMatrix (`FramePreview`) ও CSS contrast(1 + c)-এর একই সূত্র।
 * Media3-এর নিজের `Contrast`-এর গুণক (1+c)/(1.0001−c) অনেক কড়া (c=০.৫-এ ৩.০ বনাম ১.৫; c=১-এ ২০০০০ বনাম ২), তাই প্রিভিউয়ের সাথে মিলত না।
 * Media3 1.4.1-এর ডিফল্ট SDR কাজের রঙ-জায়গা SMPTE 170M (গামা-এনকোডেড, লিনিয়ার নয়), তাই প্রিভিউয়ের মতোই গামা-মানে কাজ করে।
 * ম্যাট্রিক্স 4×4 column-major (GL): শেষ কলামে অফসেট (১−f)/২।
 */
@UnstableApi
private class ContrastMatrix(contrast: Float) : RgbMatrix {
    private val f = (1f + contrast.coerceIn(-1f, 1f)).coerceAtLeast(0f)
    private val matrix = floatArrayOf(
        f, 0f, 0f, 0f,
        0f, f, 0f, 0f,
        0f, 0f, f, 0f,
        (1f - f) * 0.5f, (1f - f) * 0.5f, (1f - f) * 0.5f, 1f,
    )
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = f == 1f
}

@UnstableApi
object RenderComposer {

    fun build(ctx: Context, spec: RenderSpec, mixedAudio: Uri? = null): Composition {
        val w = spec.aspect.w
        val h = spec.aspect.h
        val band = spec.fbBandPx.coerceAtLeast(0)
        val outH = h + 2 * band

        // পুরোনো শিরোনাম-বার/লোগো + সব ভিজ্যুয়াল লেয়ার: প্রতি ফ্রেমের সময় অনুযায়ী একটা ওভারলে
        val totalMs = spec.clips.sumOf { it.lengthMs() }
        val visible = spec.layers.filter { !it.isAudio && it.startMs < totalMs || (it is NameBarLayer && it.repeatSec > 0) }
        val renderer = LayerFrameRenderer(
            ctx, w, h, makeOverlay(ctx, spec, w, h), visible, repeatStopMs(spec.clips), totalMs,
            canvasH = outH, yOff = band, fbTop = spec.fbTop, fbBottom = spec.fbBottom, fbColor = spec.fbColor,
            adsInBand = spec.fbAdsInBand && band > 0,
            cardRanges = cardRangesMs(spec.clips),
            cuts = ClipTr.cuts(spec.clips),
        )
        // কন্ট্রাস্ট: এডজাস্ট লেয়ারের সময়ের মধ্যেই ভিডিওর ওপর বসে (লেখা/বার/বিজ্ঞাপনের নিচে, তাই সেগুলো প্রভাবিত হয় না)
        val contrastFx: List<Effect> = visible.filterIsInstance<AdjustLayer>()
            .filter { it.contrast != 0f && it.endMs > it.startMs }
            .map { TimestampWrapper(ContrastMatrix(it.contrast.coerceIn(-1f, 1f)), it.startMs * 1000L, it.endMs * 1000L) }
        // ক্লিপ-ট্রানজিশনের স্লাইড ও জুম পাঞ্চ: কাটের ±০.২৮ সে. ভিডিও-ফ্রেম সরে/বড় হয় (ওভারলের নিচে, তাই লেখা/ব্যানার নড়ে না)
        val moveFx: List<Effect> = ClipTr.cuts(spec.clips).filter { it.mode == "slide" || it.mode == "zoom" }.map { cut ->
            val st = (cut.ms - ClipTr.HALF_MS).coerceAtLeast(0L)
            val en = (cut.ms + ClipTr.HALF_MS).coerceAtLeast(st + 1L)
            TimestampWrapper(
                object : MatrixTransformation {
                    override fun getMatrix(presentationTimeUs: Long): Matrix {
                        val u = ((presentationTimeUs / 1000.0 - cut.ms) / ClipTr.HALF_MS).toFloat().coerceIn(-0.999f, 0.999f)
                        return ClipTr.matrixFor(cut.mode, u) ?: Matrix()
                    }
                },
                st * 1000L, en * 1000L,
            )
        }
        // ওভারলে/কন্ট্রাস্ট/ট্রানজিশন-সরানো প্রতিটা ক্লিপের নিজস্ব ইফেক্টের শেষে বসে (আগে পুরো কম্পোজিশনে বসানো ছিল, কিন্তু ডিভাইসে ওভারলে আঁকা হচ্ছিল না; কারণ নিশ্চিত নয়, তাই ক্লিপ-স্তরে সরানো)।
        // ক্লিপ-স্তরের ইফেক্টেও ফ্রেমের সময় টাইমলাইন ধরে চলে (আগের ক্লিপগুলোর দৈর্ঘ্য যোগ হয়ে), তাই লেয়ারের সময় একই থাকে
        fun tailFx(): List<Effect> = contrastFx + moveFx + if (renderer.isEmpty) emptyList() else {
            listOf(OverlayEffect(ImmutableList.of<TextureOverlay>(LayerFrameOverlay(renderer))))
        }

        val items = spec.clips.map { c ->
            // প্রতিটি ক্লিপ একই মাপে (ক্রপসহ ফিট) আনা হয়; তারপর (ধাপ ৬) ফ্লিপ/জুম/পজিশন থাকলে ওই ফ্রেমের ওপর
            val fxList = mutableListOf<Effect>()
            if (band > 0 && c.card != null) {
                // ইন্ট্রো/আউট্রো কার্ড (HTML-এর মতো): ব্যান্ড ছাড়া পুরো ক্যানভাস জুড়ে; কার্ডের ভিডিও আগেই w×outH মাপে বানানো (ViewModels.startRender)
                fxList += Presentation.createForWidthAndHeight(w, outH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
            } else {
                fxList += Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
                if (c.hasTransform()) fxList += clipTransform(c)
                // ক্রপ: বাছা অংশ কেটে নিয়ে অনুপাত না বদলে আবার পুরো w×h ফ্রেমে ফিট (আউটপুটের মাপ একই; অনুপাত অন্যরকম হলে কালো জায়গা)
                if (c.hasCrop()) {
                    val cx = c.cropX.coerceIn(0f, 0.95f)
                    val cy = c.cropY.coerceIn(0f, 0.95f)
                    val cw = c.cropW.coerceIn(0.05f, 1f - cx)
                    val ch = c.cropH.coerceIn(0.05f, 1f - cy)
                    fxList += Crop(-1f + 2f * cx, -1f + 2f * (cx + cw), 1f - 2f * (cy + ch), 1f - 2f * cy)
                    fxList += Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)
                }
                // ফেইসবুক ব্যান্ড: ১২৮০×৭২০ ফ্রেমটা লম্বা ক্যানভাসের মাঝে বসে, ওপরে-নিচে কালো জায়গা (সেখানে সাদা ব্যান্ড + লেখা আঁকা হয়)
                if (band > 0) fxList += Presentation.createForWidthAndHeight(w, outH, Presentation.LAYOUT_SCALE_TO_FIT)
            }
            fxList += tailFx()
            val fit = Effects(emptyList(), fxList)
            if (c.isImage) {
                val ms = (c.imageSec * 1000).toLong().coerceAtLeast(500L)
                val mb = MediaItem.Builder().setUri(c.uri).setImageDurationMs(ms)
                // কার্ডের ফাইল-Uri-তে MIME জানা থাকে না, তাই ছবি হিসেবে চেনাতে বলে দেওয়া
                if (c.uri.scheme == "file") mb.setMimeType("image/png")
                val mi = mb.build()
                EditedMediaItem.Builder(mi)
                    .setDurationUs(ms * 1000)
                    .setFrameRate(30)
                    .setEffects(fit)
                    .build()
            } else {
                // টাইমলাইনে কাটা টুকরোগুলোর শুরু/শেষ মিলিসেকেন্ডে গোল করা হয়, যাতে জোড়ার জায়গায় ফাঁক/ওভারল্যাপ না হয়
                val cc = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(Math.round(c.startSec * 1000.0))
                if (c.endSec > c.startSec) cc.setEndPositionMs(Math.round(c.endSec * 1000.0))
                val mi = MediaItem.Builder().setUri(c.uri).setClippingConfiguration(cc.build()).build()
                EditedMediaItem.Builder(mi)
                    .setRemoveAudio(!spec.keepOriginalAudio || mixedAudio != null)
                    .setEffects(fit)
                    .build()
            }
        }

        val sequences = mutableListOf(EditedMediaItemSequence(items))

        // ব্যাকগ্রাউন্ড মিউজিক (ভিডিওর দৈর্ঘ্য পর্যন্ত লুপ হয়)
        spec.music?.let { music ->
            val vol = ChannelMixingAudioProcessor()
            for (ch in 1..2) {
                vol.putChannelMixingMatrix(ChannelMixingMatrix.create(ch, ch).scaleBy(spec.musicVolume))
            }
            val mi = EditedMediaItem.Builder(MediaItem.fromUri(music))
                .setRemoveVideo(true)
                .setEffects(Effects(listOf(vol), emptyList()))
                .build()
            sequences += EditedMediaItemSequence(listOf(mi), true)
        }

        // ধাপ ৫: ক্লিপ-শব্দ + অডিও/ভয়েস/বিপ লেয়ার আগে থেকে একটা WAV-এ মেশানো; ক্লিপের নিজের শব্দ তখন বন্ধ
        mixedAudio?.let { u ->
            val mi = EditedMediaItem.Builder(MediaItem.fromUri(u)).setRemoveVideo(true).build()
            sequences += EditedMediaItemSequence(listOf(mi))
        }

        return Composition.Builder(sequences)
            .experimentalSetForceAudioTrack(true)
            .build()
    }

    /**
     * ক্লিপের ফ্লিপ + জুম + পজিশন, একটাই ম্যাট্রিক্স (NDC: x ডানে, y ওপরে, -১..১)।
     * ফ্রেমের কেন্দ্র ধরে স্কেল (ফ্লিপ = ঋণাত্মক স্কেল), তারপর সরানো। জুম z হলে ছবির প্রান্ত ফ্রেমের প্রান্তে
     * পৌঁছাতে সর্বোচ্চ (z−1) সরানো যায় — পজিশন -১..১ তাই (z−1) দিয়ে গুণ।
     * প্রিভিউ (`FramePreview`-এর graphicsLayer) ঠিক একই নিয়ম মানে।
     */
    private fun clipTransform(c: Clip): MatrixTransformation {
        val z = c.zoom.coerceIn(1f, 4f)
        val m = Matrix().apply {
            setScale(if (c.flipH) -z else z, if (c.flipV) -z else z)
            // জানালা ডানে (panX>0) = ছবি বাঁয়ে; জানালা নিচে (panY>0) = ছবি ওপরে (NDC-তে +y)
            postTranslate(-c.panX.coerceIn(-1f, 1f) * (z - 1f), c.panY.coerceIn(-1f, 1f) * (z - 1f))
        }
        return object : MatrixTransformation {
            override fun getMatrix(presentationTimeUs: Long): Matrix = m
        }
    }

    /** পুরো ফ্রেমের মাপের স্বচ্ছ ছবি: ডানে-উপরে লোগো, নিচে লাল শিরোনাম-বার */
    private fun makeOverlay(ctx: Context, spec: RenderSpec, w: Int, h: Int): Bitmap? {
        val caption = spec.caption.trim()
        if (caption.isEmpty() && spec.logo == null) return null

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val unit = minOf(w, h)

        spec.logo?.let { uri ->
            val logo = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (logo != null) {
                val lw = (w * 0.18f).toInt().coerceAtLeast(1)
                val lh = (lw * logo.height / logo.width.toFloat()).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(logo, lw, lh, true)
                val m = unit * 0.04f
                cv.drawBitmap(scaled, w - lw - m, m, Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }

        if (caption.isNotEmpty()) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = unit * 0.06f
                typeface = Typeface.DEFAULT_BOLD
            }
            val margin = unit * 0.04f
            val padding = unit * 0.025f
            val textW = (w - 2 * margin - 2 * padding).toInt()
            val layout = StaticLayout.Builder.obtain(caption, 0, caption.length, tp, textW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            val barH = layout.height + 2 * padding
            val top = h - margin * 1.5f - barH
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6C62828.toInt() }
            cv.drawRoundRect(RectF(margin, top, w - margin, top + barH), padding, padding, bg)
            cv.save()
            cv.translate(margin + padding, top + padding)
            layout.draw(cv)
            cv.restore()
        }
        return bmp
    }
}
