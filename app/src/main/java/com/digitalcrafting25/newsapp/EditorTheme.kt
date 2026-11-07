package com.digitalcrafting25.newsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ভিডিও এডিটরের ডার্ক রং — index.html-এর `#vcRoot` CSS থেকে নেওয়া
 * (#14161b প্যানেল, #1b1e26 বার, #0d0f13 টাইমলাইন, #0b0d11 ডক, বাছাই = অ্যাম্বার #fbbf24, প্লেহেড = #ef4444)
 */
object ED {
    val Panel = Color(0xFF14161B)
    val Bar = Color(0xFF1B1E26)
    val Deep = Color(0xFF0D0F13)
    val Dock = Color(0xFF0B0D11)
    val Border = Color(0xFF2A2E37)
    val Border2 = Color(0xFF333A47)
    val Btn = Color(0xFF232733)
    val Text = Color(0xFFE5E7EB)
    val Sub = Color(0xFFC7CBD6)
    val Muted = Color(0xFF9AA1B1)
    val Amber = Color(0xFFFBBF24)
    val Playhead = Color(0xFFEF4444)
    val Publish = Color(0xFFC62828)
    val Ok = Color(0xFF86EFAC)
    val Err = Color(0xFFFCA5A5)
    val AudioLane = Color(0x140D9488)   // rgba(13,148,136,.08)

    /** ক্লিপের রং: প্রথম ৩টা + তারপর ঘুরে ঘুরে (HTML-এর COL / XCOL) */
    private val SEG = listOf(0xFF7C3AED, 0xFF2563EB, 0xFF059669)
    private val XSEG = listOf(0xFFD97706, 0xFFDB2777, 0xFF0891B2, 0xFF65A30D, 0xFF9333EA, 0xFFDC2626)
    fun segColor(i: Int): Color = Color(if (i < 3) SEG[i] else XSEG[(i - 3) % XSEG.size])

    /** লেয়ার-বারের রং (HTML-এর LCOL) */
    fun layerColor(k: LayerKind): Color = Color(
        when (k) {
            LayerKind.TEXT -> 0xFFD97706
            LayerKind.IMAGE -> 0xFFDB2777
            LayerKind.LOGO -> 0xFF9333EA
            LayerKind.TAG -> 0xFF65A30D
            LayerKind.TICKER -> 0xFFDC2626
            LayerKind.NAME_BAR -> 0xFF2563EB
            LayerKind.AD_BOTTOM -> 0xFF16A34A
            LayerKind.AD_TOP -> 0xFF0891B2
            LayerKind.OVERLAY -> 0xFF4F46E5
            LayerKind.ADJUST -> 0xFF64748B
            LayerKind.AUDIO -> 0xFF0D9488
            LayerKind.VOICE -> 0xFF0E7490
            LayerKind.BEEP -> 0xFFB45309
        },
    )
}

/** এডিটরের ভেতরের Material উপাদান (স্লাইডার, টেক্সট ফিল্ড, চেকবক্স…) ডার্ক রঙে আঁকার জন্য */
@Composable
fun EditorDarkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ED.Amber,
            onPrimary = Color(0xFF111111),
            secondary = ED.Amber,
            onSecondary = Color(0xFF111111),
            background = ED.Panel,
            onBackground = ED.Text,
            surface = ED.Panel,
            onSurface = ED.Text,
            surfaceVariant = ED.Btn,
            onSurfaceVariant = ED.Sub,
            secondaryContainer = ED.Btn,
            onSecondaryContainer = ED.Text,
            outline = ED.Border2,
            outlineVariant = ED.Border,
        ),
    ) {
        Surface(color = Color.Transparent, contentColor = ED.Text, content = content)
    }
}

/** নিচের ডকের বাটন: ওপরে আইকন (ইমোজি), নিচে ছোট লেখা (HTML `#vc_dock button.vct`) */
@Composable
fun DockBtn(
    icon: String,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = if (active) ED.Amber else ED.Text
    Column(
        modifier
            .widthIn(min = 54.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.35f)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(icon, fontSize = 18.sp, color = c, textAlign = TextAlign.Center)
        Text(label, fontSize = 10.sp, color = c, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

/** গোল ৪০dp বাটন (আনডু/রিডু) */
@Composable
fun CircleBtn(text: String, enabled: Boolean = true, size: Dp = 40.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(ED.Btn)
            .border(1.dp, ED.Border2, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 20.sp, color = ED.Text) }
}

/** ছোট ধূসর বাটন (জুম-বারের ◀ ০.১ ধরনের) */
@Composable
fun SmallBtn(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fontSize: TextUnit = 12.sp,
    hPad: Dp = 8.dp,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier.clip(shape).background(ED.Btn).border(1.dp, ED.Border2, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = hPad, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = fontSize, color = ED.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
}

/** সময়ের মনোস্পেস পিল: `0:03.4 / 0:12.0` */
@Composable
fun TimePill(text: String, modifier: Modifier = Modifier, fontSize: TextUnit = 12.sp, hPad: Dp = 6.dp) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text,
        modifier = modifier.clip(shape).background(ED.Deep).border(1.dp, ED.Border, shape)
            .padding(horizontal = hPad, vertical = 8.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize,
        color = Color(0xFFF3F4F6),
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
    )
}

/** এডিটর প্যানেলের ফ্রেম (HTML `.lyed`: #14161b, বর্ডার #2a2e37, কোণা ১২) */
fun Modifier.edPanel(): Modifier {
    val shape = RoundedCornerShape(12.dp)
    return this.fillMaxWidth().clip(shape).background(ED.Panel).border(1.dp, ED.Border, shape).padding(12.dp)
}

/** সাদা গোল "▶ প্লে" বাটন (HTML `#vc_play`) — এখনো কোনো ফাংশন বসানো নেই, শুধু চেহারা */
@Composable
fun PlayBtn(modifier: Modifier = Modifier, label: String = "▶ প্লে", onClick: () -> Unit = {}) {
    Box(
        modifier.clip(RoundedCornerShape(999.dp)).background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = ED.Panel, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
}
