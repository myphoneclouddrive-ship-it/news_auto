package com.digitalcrafting25.newsapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList

@UnstableApi
object RenderComposer {

    fun build(ctx: Context, spec: RenderSpec): Composition {
        val w = spec.aspect.w
        val h = spec.aspect.h

        val items = spec.clips.map { c ->
            // প্রতিটি ক্লিপ একই মাপে (ক্রপসহ ফিট) আনা হয়
            val fit = Effects(
                emptyList(),
                listOf<Effect>(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)),
            )
            if (c.isImage) {
                val ms = (c.imageSec * 1000).toLong().coerceAtLeast(500L)
                val mi = MediaItem.Builder().setUri(c.uri).setImageDurationMs(ms).build()
                EditedMediaItem.Builder(mi)
                    .setDurationUs(ms * 1000)
                    .setFrameRate(30)
                    .setEffects(fit)
                    .build()
            } else {
                val cc = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((c.startSec * 1000).toLong())
                if (c.endSec > c.startSec) cc.setEndPositionMs((c.endSec * 1000).toLong())
                val mi = MediaItem.Builder().setUri(c.uri).setClippingConfiguration(cc.build()).build()
                EditedMediaItem.Builder(mi)
                    .setRemoveAudio(!spec.keepOriginalAudio)
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

        val overlayBmp = makeOverlay(ctx, spec, w, h)
        val videoFx: List<Effect> = if (overlayBmp != null) {
            listOf(OverlayEffect(ImmutableList.of<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(overlayBmp))))
        } else emptyList()

        return Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), videoFx))
            .experimentalSetForceAudioTrack(true)
            .build()
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
