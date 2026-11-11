package com.digitalcrafting25.newsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// এডিটরের নতুন ডার্ক চেহারার ছোট ছোট অংশ: ওপরের বার, নিচের শিটের হেডার, বাছাইয়ের টাইল
// ---------------------------------------------------------------------------

/** ওপরের বার: পেছনে (গোল) · মাঝে ফাঁকা · রেজোলিউশন চিপ · পাবলিশ (লাল গোলাপি পিল) */
@Composable
fun EditorTopBar(
    onBack: () -> Unit, publishEnabled: Boolean, onPublish: () -> Unit,
    aspect: Aspect = Aspect.LANDSCAPE, onAspect: (Aspect) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconBtn("back", circle = true, size = 38.dp, iconSize = 20.dp, onClick = onBack)
        Spacer(Modifier.weight(1f))
        // মাপ-চিপ: "পোস্ট" (ডিফল্ট, ১২৮০×৭২০ ইউটিউব মাপ) ↔ "রিলস" (৭২০×১২৮০, ৯:১৬); চাপলে দুই অপশনের মেনু
        var sizeMenu by remember { mutableStateOf(false) }
        Box {
            Box(
                Modifier.clip(RoundedCornerShape(999.dp)).background(ED.Btn)
                    .clickable { sizeMenu = true }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    (if (aspect == Aspect.PORTRAIT) "রিলস" else "পোস্ট") + " ▾",
                    color = ED.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                )
            }
            DropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false }) {
                DropdownMenuItem(
                    text = { Text("পোস্ট (১৬:৯ · 1280×720)" + if (aspect == Aspect.LANDSCAPE) "  ✓" else "") },
                    onClick = { sizeMenu = false; onAspect(Aspect.LANDSCAPE) },
                )
                DropdownMenuItem(
                    text = { Text("রিলস (৯:১৬ · 720×1280)" + if (aspect == Aspect.PORTRAIT) "  ✓" else "") },
                    onClick = { sizeMenu = false; onAspect(Aspect.PORTRAIT) },
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.clip(RoundedCornerShape(999.dp)).background(ED.Publish)
                .clickable(enabled = publishEnabled, onClick = onPublish)
                .alpha(if (publishEnabled) 1f else 0.5f)
                .padding(horizontal = 18.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) { Text("পাবলিশ", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
    }
}

/** নিচের এডিট শিটের মাথা: বাঁয়ে নাম, ডানে ✓ (শেষ করুন) */
@Composable
fun SheetHeader(title: String, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(48.dp)
            .background(ED.Panel, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, color = ED.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        IconBtn("check", size = 44.dp, iconSize = 24.dp, onClick = onDone)
    }
}

/**
 * বাছাইয়ের টাইল (CapCut-এর "None / Portrait / Face" সারির মতো)।
 * [icon] দিলে ওপরে আইকন + নিচে লেখা; না দিলে টাইলের ভেতরেই লেখা।
 */
@Composable
fun OptionTile(label: String, selected: Boolean, modifier: Modifier = Modifier, icon: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val bg = if (selected) Color(0xFF4A4A55) else ED.Btn
    if (icon != null) {
        Column(
            modifier.widthIn(min = 64.dp).clip(shape).clickable(onClick = onClick).padding(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier.size(60.dp, 52.dp).clip(shape).background(bg)
                    .then(if (selected) Modifier.border(1.5.dp, Color.White, shape) else Modifier),
                contentAlignment = Alignment.Center,
            ) { LineIcon(icon, ED.Text, iconSize = 24.dp) }
            Text(label, color = if (selected) ED.Text else ED.Sub, fontSize = 11.sp, maxLines = 1, softWrap = false)
        }
    } else {
        Box(
            modifier.heightIn(min = 44.dp).widthIn(min = 64.dp).clip(shape).background(bg)
                .then(if (selected) Modifier.border(1.5.dp, Color.White, shape) else Modifier)
                .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label, color = if (selected) ED.Text else ED.Sub, fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center, maxLines = 2,
            )
        }
    }
}
