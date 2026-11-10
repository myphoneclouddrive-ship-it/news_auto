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
 * ভিডিও এডিটরের প্রিমিয়াম ডার্ক রং (CapCut ধাঁচ): কালো-ঘেঁষা পেছন, গাঢ় ধূসর বাটন, সাদা বাছাই-রেখা ও সাদা প্লেহেড,
 * একটাই উজ্জ্বল অ্যাকসেন্ট (গোলাপি-লাল) — পাবলিশ বাটন ও সক্রিয় টুলে।
 */
object ED {
    val Screen = Color(0xFF0A0A0D)   // পুরো এডিটরের পেছন
    val Panel = Color(0xFF16161B)    // নিচের প্যানেল/শিট
    val Bar = Color(0xFF1C1C22)
    val Deep = Color(0xFF0F0F13)     // টাইমলাইনের পেছন
    val Dock = Color(0xFF101014)     // নিচের টুলবার
    val Border = Color(0xFF26262E)
    val Border2 = Color(0xFF34343E)
    val Btn = Color(0xFF212128)
    val Text = Color(0xFFF5F5F7)
    val Sub = Color(0xFFB4B4BE)
    val Muted = Color(0xFF7C7C88)
    val Amber = Color(0xFFFFFFFF)    // বাছাই = সাদা রেখা (নামটা পুরোনো; সব জায়গায় একই নামে চলে)
    val Accent = Color(0xFFFF2D55)   // সক্রিয় টুল / প্রাইমারি
    val Playhead = Color(0xFFFFFFFF)
    val Publish = Color(0xFFFF2D55)
    val Ok = Color(0xFF34D399)
    val Err = Color(0xFFFF6B81)
    val AudioLane = Color(0x00000000)

    /** ক্লিপের রং: প্রথম ৩টা + তারপর ঘুরে ঘুরে (ক্লিপের নিচের সরু দাগ ও ফ্রেম লোডের আগের পেছন) */
    private val SEG = listOf(0xFF8B5CF6, 0xFF3B82F6, 0xFF10B981)
    private val XSEG = listOf(0xFFF59E0B, 0xFFEC4899, 0xFF06B6D4, 0xFF84CC16, 0xFFA855F7, 0xFFEF4444)
    fun segColor(i: Int): Color = Color(if (i < 3) SEG[i] else XSEG[(i - 3) % XSEG.size])

    /** লেয়ার-বারের রং: অডিও বেগুনি, লেখা সবুজ, স্টিকার/লোগো টিল, ইফেক্ট/ওভারলে নীল (CapCut-এর ট্র্যাকের মতো) */
    fun layerColor(k: LayerKind): Color = Color(
        when (k) {
            LayerKind.TEXT -> 0xFF4FAE6B
            LayerKind.IMAGE -> 0xFFE8825C
            LayerKind.LOGO -> 0xFF36B5AC
            LayerKind.TAG -> 0xFF2FA4C9
            LayerKind.WATERMARK -> 0xFF5BB5E0
            LayerKind.TICKER -> 0xFF7BB661
            LayerKind.NAME_BAR -> 0xFF4A8FE0
            LayerKind.AD_BOTTOM -> 0xFFD9A441
            LayerKind.AD_TOP -> 0xFFC98B3A
            LayerKind.OVERLAY -> 0xFF3B9AD9
            LayerKind.ADJUST -> 0xFF6B7FD7
            LayerKind.AUDIO -> 0xFF9B6DDB
            LayerKind.VOICE -> 0xFFB07AE0
            LayerKind.BEEP -> 0xFFD08A4B
        },
    )
}

/** এডিটরের ভেতরের Material উপাদান (স্লাইডার, টেক্সট ফিল্ড, চেকবক্স…) ডার্ক রঙে আঁকার জন্য */
@Composable
fun EditorDarkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ED.Accent,
            onPrimary = Color.White,
            secondary = ED.Accent,
            onSecondary = Color.White,
            background = ED.Screen,
            onBackground = ED.Text,
            surface = ED.Panel,
            onSurface = ED.Text,
            surfaceVariant = ED.Btn,
            onSurfaceVariant = ED.Sub,
            secondaryContainer = Color(0xFF3A1B25),
            onSecondaryContainer = ED.Text,
            outline = ED.Border2,
            outlineVariant = ED.Border,
        ),
    ) {
        Surface(color = Color.Transparent, contentColor = ED.Text, content = content)
    }
}

/**
 * নিচের টুলবারের বাটন: ওপরে লাইন-আইকন, নিচে ছোট লেখা।
 * [icon] আইকনের নাম (যেমন "music") বা পুরোনো ইমোজি হতে পারে; আইকন না মিললে ইমোজিই আঁকা হয়।
 */
@Composable
fun DockBtn(
    icon: String,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = if (active) ED.Accent else ED.Text
    val key = EdIcons.resolve(icon)
    Column(
        modifier
            .widthIn(min = 62.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.35f)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (key != null) LineIcon(key, c, iconSize = 24.dp)
            else Text(icon, fontSize = 18.sp, color = c, textAlign = TextAlign.Center)
        }
        Text(label, fontSize = 10.sp, color = if (active) ED.Accent else ED.Sub, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

/** আইকনের গোল/খালি বাটন (আনডু, রিডু, প্লে, পেছনে…): [circle] true হলে ধূসর গোল পেছন */
@Composable
fun IconBtn(
    icon: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    tint: Color = ED.Text,
    circle: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier.size(size).clip(CircleShape)
            .then(if (circle) Modifier.background(ED.Btn) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) { LineIcon(icon, tint, iconSize = iconSize) }
}

/** গোল ৪০dp বাটন (লেখা-ভিত্তিক; আগের কোডের জন্য রাখা) */
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

/** ছোট ধূসর বাটন */
@Composable
fun SmallBtn(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fontSize: TextUnit = 12.sp,
    hPad: Dp = 8.dp,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
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
        color = ED.Text,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
    )
}

/** এডিটর প্যানেলের কার্ড (গাঢ় ধূসর, সরু বর্ডার, কোণা ১৪) */
fun Modifier.edPanel(): Modifier {
    val shape = RoundedCornerShape(14.dp)
    return this.fillMaxWidth().clip(shape).background(ED.Bar).border(1.dp, ED.Border, shape).padding(12.dp)
}

/** "▶ প্লে" লেখার বাটন (আগের কোডের জন্য রাখা; নতুন স্ক্রিনে আইকন-বাটন ব্যবহার হয়) */
@Composable
fun PlayBtn(modifier: Modifier = Modifier, label: String = "▶ প্লে", onClick: () -> Unit = {}) {
    Box(
        modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xFF222222))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
}
