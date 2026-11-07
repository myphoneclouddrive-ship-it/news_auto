package com.digitalcrafting25.newsapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** একটা আইকনের পাথ (২৪×২৪ গ্রিড, SVG পাথ-ডেটা) */
class EdIconSpec(val d: String, val filled: Boolean)

/**
 * এডিটরের লাইন-আইকন সেট (CapCut ধাঁচের পাতলা রেখার আইকন)।
 * ইমোজির বদলে এগুলো আঁকা হয়, তাই রং (সাদা/ধূসর/অ্যাকসেন্ট) নিজের মতো বদলানো যায়।
 */
object EdIcons {
    val all: Map<String, EdIconSpec> = mapOf(
        "back" to EdIconSpec("M19 12H5 M11 6l-6 6 6 6", false),
        "chevL" to EdIconSpec("M15 5l-7 7 7 7", false),
        "chevR" to EdIconSpec("M9 5l7 7-7 7", false),
        "play" to EdIconSpec("M8 5v14l11-7z", true),
        "pause" to EdIconSpec("M7 5h3.5v14H7z M13.5 5H17v14h-3.5z", true),
        "undo" to EdIconSpec("M9 14L4 9l5-5 M4 9h10a6 6 0 0 1 0 12h-3", false),
        "redo" to EdIconSpec("M15 14l5-5-5-5 M20 9H10a6 6 0 0 0 0 12h3", false),
        "full" to EdIconSpec("M4 9V4h5 M20 9V4h-5 M4 15v5h5 M20 15v5h-5", false),
        "scissors" to EdIconSpec("M8.5 6a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0 M8.5 18a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0 M8 7.7L20 18 M8 16.3L20 6", false),
        "overlay" to EdIconSpec("M8 8V6a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-2 M4 10a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z", false),
        "sparkle" to EdIconSpec("M12 3l2.2 6.8L21 12l-6.8 2.2L12 21l-2.2-6.8L3 12l6.8-2.2z", false),
        "music" to EdIconSpec("M9 18V6l11-2v12 M9 18a3 3 0 1 1-6 0a3 3 0 1 1 6 0 M20 16a3 3 0 1 1-6 0a3 3 0 1 1 6 0", false),
        "text" to EdIconSpec("M5 7V4.5h14V7 M12 4.5V20 M9 20h6", false),
        "sticker" to EdIconSpec("M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0 M8.5 14a4 4 0 0 0 7 0 M9 9.5h.01 M15 9.5h.01", false),
        "sliders" to EdIconSpec("M4 8h9 M17 8h3 M4 16h3 M11 16h9 M15 5.5v5 M9 13.5v5", false),
        "adBottom" to EdIconSpec("M4 5h16v14H4z M4 14h16", false),
        "adTop" to EdIconSpec("M4 5h16v14H4z M4 10h16", false),
        "image" to EdIconSpec("M4 5h16v14H4z M4 16l5-5 4 4 3-3 4 4 M9 9.5a1 1 0 1 1-2 0a1 1 0 1 1 2 0", false),
        "logo" to EdIconSpec("M12 3l8 3.5V12c0 4.5-3.2 7.8-8 9-4.8-1.2-8-4.5-8-9V6.5z", false),
        "pin" to EdIconSpec("M3 12V4h8l10 10-8 8z M7.5 8h.01", false),
        "user" to EdIconSpec("M16 8a4 4 0 1 1-8 0a4 4 0 1 1 8 0 M4 21a8 8 0 0 1 16 0", false),
        "ticker" to EdIconSpec("M3 8h18 M3 12h18 M3 16h11", false),
        "clapper" to EdIconSpec("M4 10h16v10H4z M4 10l3-5h4l-3 5 M11 10l3-5h4l-3 5", false),
        "flag" to EdIconSpec("M5 21V4 M5 4h12l-2 4 2 4H5", false),
        "mic" to EdIconSpec("M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z M5 11a7 7 0 0 0 14 0 M12 18v3", false),
        "bell" to EdIconSpec("M6 16v-5a6 6 0 0 1 12 0v5l2 2H4z M10 21h4", false),
        "volUp" to EdIconSpec("M4 9v6h4l5 4V5L8 9z M16 9a4 4 0 0 1 0 6 M18.5 6.5a8 8 0 0 1 0 11", false),
        "volDown" to EdIconSpec("M4 9v6h4l5 4V5L8 9z M16 9a4 4 0 0 1 0 6", false),
        "mute" to EdIconSpec("M4 9v6h4l5 4V5L8 9z M17 9.5l5 5 M22 9.5l-5 5", false),
        "trash" to EdIconSpec("M4 7h16 M9 7V4h6v3 M6 7l1 13h10l1-13 M10 11v6 M14 11v6", false),
        "copy" to EdIconSpec("M9 9h11v11H9z M5 15V5h10", false),
        "pencil" to EdIconSpec("M4 20l1-5L16 4l4 4L9 19z M14 6l4 4", false),
        "zoom" to EdIconSpec("M18 10.5a7.5 7.5 0 1 1-15 0a7.5 7.5 0 1 1 15 0 M16 16l5 5", false),
        "flipH" to EdIconSpec("M12 3v18 M8 7l-5 5 5 5z M16 7l5 5-5 5z", false),
        "flipV" to EdIconSpec("M3 12h18 M7 8l5-5 5 5z M7 16l5 5 5-5z", false),
        "up" to EdIconSpec("M12 19V5 M6 11l6-6 6 6", false),
        "down" to EdIconSpec("M12 5v14 M6 13l6 6 6-6", false),
        "skipL" to EdIconSpec("M11 6l-6 6 6 6 M19 6l-6 6 6 6", false),
        "skipR" to EdIconSpec("M13 6l6 6-6 6 M5 6l6 6-6 6", false),
        "reset" to EdIconSpec("M4 12a8 8 0 1 0 2.5-5.8 M4 4v5h5", false),
        "fadeIn" to EdIconSpec("M3 19L21 5 M3 19h18 M21 5v14", false),
        "fadeOut" to EdIconSpec("M3 5l18 14 M3 5v14 M3 19h18", false),
        "wave" to EdIconSpec("M3 12h1 M7 8v8 M11 4v16 M15 8v8 M19 10v4", false),
        "check" to EdIconSpec("M5 12.5l4.5 4.5L19 7", false),
        "close" to EdIconSpec("M6 6l12 12 M18 6L6 18", false),
        "plus" to EdIconSpec("M12 5v14 M5 12h14", false),
        "sun" to EdIconSpec("M16 12a4 4 0 1 1-8 0a4 4 0 1 1 8 0 M12 2.5v2 M12 19.5v2 M2.5 12h2 M19.5 12h2 M5.3 5.3l1.4 1.4 M17.3 17.3l1.4 1.4 M5.3 18.7l1.4-1.4 M17.3 6.7l1.4-1.4", false),
        "sunDim" to EdIconSpec("M16 12a4 4 0 1 1-8 0a4 4 0 1 1 8 0 M12 3v1.5 M12 19.5V21 M3 12h1.5 M19.5 12H21", false),
    )

    /** ডকের পুরোনো ইমোজি-বাটন → আইকনের নাম (FE0F বাদ দিয়ে মেলানো হয়) */
    private val byEmoji: Map<String, String> = mapOf(
        "↔" to "flipH",
        "↕" to "flipV",
        "↺" to "reset",
        "⟲" to "reset",
        "⏩" to "skipR",
        "⏪" to "skipL",
        "☀" to "sun",
        "✂" to "scissors",
        "✏" to "pencil",
        "✨" to "flag",
        "⬅" to "back",
        "🔙" to "back",
        "⬆" to "up",
        "⬇" to "down",
        "🌅" to "fadeIn",
        "🌇" to "fadeOut",
        "🎙" to "mic",
        "🎨" to "overlay",
        "🎬" to "clapper",
        "🎵" to "music",
        "🏞" to "adTop",
        "🏷" to "logo",
        "👤" to "user",
        "📋" to "copy",
        "📍" to "pin",
        "📜" to "ticker",
        "🔅" to "sunDim",
        "🔆" to "sun",
        "🔉" to "volDown",
        "🔊" to "volUp",
        "🔇" to "mute",
        "🔍" to "zoom",
        "🔔" to "bell",
        "🔤" to "text",
        "🖼" to "image",
    )

    /** `icon` আইকনের নাম হলে সেটাই; ইমোজি হলে তার আইকন; কিছু না মিললে null (তখন লেখা হিসেবেই আঁকা হবে) */
    fun resolve(icon: String): String? {
        if (all.containsKey(icon)) return icon
        return byEmoji[icon.replace("\uFE0F", "")]
    }
}

/** নামে আইকন আঁকে। [stroke] ২৪-গ্রিডের এককে (১.৮ = পাতলা)। */
@Composable
fun LineIcon(name: String, tint: Color, modifier: Modifier = Modifier, iconSize: Dp = 22.dp, stroke: Float = 1.8f) {
    val spec = EdIcons.all[name] ?: return
    val path = remember(name) { PathParser().parsePathString(spec.d).toPath() }
    Canvas(modifier.size(iconSize)) {
        val s = this.size.minDimension / 24f
        scale(s, s, pivot = Offset.Zero) {
            if (spec.filled) {
                drawPath(path, tint, style = Fill)
            } else {
                drawPath(path, tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
