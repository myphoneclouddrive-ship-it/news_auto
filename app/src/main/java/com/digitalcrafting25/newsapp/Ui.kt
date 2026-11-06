package com.digitalcrafting25.newsapp

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
