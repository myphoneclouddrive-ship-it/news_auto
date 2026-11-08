package com.digitalcrafting25.newsapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** পুরোনো ওয়েব অ্যাপের CSS-এর রঙ হুবহু */
object C {
    val Red = Color(0xFFC62828)
    val DarkRed = Color(0xFF8E0000)
    val Bg = Color(0xFFF4F5F7)
    val Text = Color(0xFF222222)
    val Label = Color(0xFF666666)
    val Gray = Color(0xFF555555)
    val Green = Color(0xFF2E7D32)
    val Blue = Color(0xFF1565C0)
    val Border = Color(0xFFCCCCCC)
    val Line = Color(0xFFEEEEEE)
    val NavLine = Color(0xFFDDDDDD)
    val Muted = Color(0xFF777777)
}

@Composable
fun NewsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = C.Red, onPrimary = Color.White,
            background = C.Bg, surface = Color.White, onSurface = C.Text,
        ),
    ) {
        Surface(color = C.Bg, contentColor = C.Text, content = content)
    }
}

/** ওয়েবের .btn : পুরো চওড়া, ৮ ব্যাসার্ধ, বোল্ড */
@Composable
fun AppBtn(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = C.Red,
    enabled: Boolean = true,
    small: Boolean = false,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled,
        modifier = if (small) modifier else modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color, contentColor = Color.White,
            disabledContainerColor = color.copy(alpha = 0.5f), disabledContentColor = Color.White.copy(alpha = 0.9f),
        ),
        contentPadding = if (small) PaddingValues(horizontal = 12.dp, vertical = 8.dp) else PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = if (small) 13.sp else 15.sp)
    }
}

/** ওয়েবের .card */
@Composable
fun InfoCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = Color.White,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
fun H1(text: String) = Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))

@Composable
fun H2(text: String) = Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))

@Composable
fun FieldLabel(text: String) = Text(text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))

@Composable
fun AppField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    visual: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = true,
    onDone: (() -> Unit)? = null,
) {
    val ph: @Composable (() -> Unit)? = if (placeholder.isEmpty()) null else { { Text(placeholder, color = Color(0xFF999999)) } }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        placeholder = ph,
        keyboardOptions = keyboard,
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke() }, onGo = { onDone?.invoke() }),
        visualTransformation = visual,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        textStyle = TextStyle(fontSize = 16.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.Red, unfocusedBorderColor = C.Border,
            focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
        ),
    )
}

// ───────── ওয়েবের CSS-এর হুবহু উপাদান (ফোটোকার্ড মেনু): input/textarea, .btn, .pnl, small ─────────

/** ওয়েবের `textarea,input`: প্যাডিং ১০, ১px #ccc বর্ডার, ৮ ব্যাসার্ধ, ১৬px লেখা */
@Composable
fun HtmlField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    readOnly: Boolean = false,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    visual: VisualTransformation = VisualTransformation.None,
) {
    var focus by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = { if (!readOnly) onChange(it) },
        readOnly = readOnly,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        textStyle = TextStyle(fontSize = 16.sp, color = C.Text),
        keyboardOptions = keyboard,
        visualTransformation = visual,
        cursorBrush = SolidColor(C.Text),
        modifier = modifier.fillMaxWidth().onFocusChanged { focus = it.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White)
                    .border(if (focus) 2.dp else 1.dp, if (focus) C.Blue else C.Border, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = Color(0xFF757575), fontSize = 16.sp)
                inner()
            }
        },
    )
}

/** ওয়েবের `.btn` (ছোট সংস্করণসহ): প্যাডিং ও ফন্ট মাপ বদলানো যায়, ডিসেবল হলে ৫০% */
@Composable
fun HBtn(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = C.Red,
    enabled: Boolean = true,
    padH: Dp = 16.dp,
    padV: Dp = 12.dp,
    size: TextUnit = 16.sp,
    radius: Dp = 8.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier.alpha(if (enabled) 1f else 0.5f).clip(RoundedCornerShape(radius)).background(color)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = padH, vertical = padV),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = size, textAlign = TextAlign.Center) }
}

/** ওয়েবের `small`: ১৩px, #666 */
@Composable
fun HSmall(text: String, modifier: Modifier = Modifier, color: Color = C.Label, center: Boolean = false) =
    Text(text, modifier = modifier, color = color, fontSize = 13.sp, textAlign = if (center) TextAlign.Center else null)

/** ওয়েবের `.pnl`: সাদা, ১px #e3e3e3 বর্ডার, ৮ ব্যাসার্ধ, প্যাডিং ১২ */
@Composable
fun HPnl(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White)
            .border(BorderStroke(1.dp, Color(0xFFE3E3E3)), RoundedCornerShape(8.dp)).padding(12.dp),
        content = content,
    )
}
