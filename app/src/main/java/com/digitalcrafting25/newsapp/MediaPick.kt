package com.digitalcrafting25.newsapp

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * ভিডিও/ছবি বাছাই: চাপলে প্রথমে "গ্যালারি" বা "ফাইল" বেছে নেওয়ার ছোট প্রশ্ন আসে।
 * গ্যালারির ফটো-পিকার সব ফোনে .ts (MPEG-TS) ফাইল দেখায় না, তাই "ফাইল" দিয়ে ফোনের ফাইল-পিকার খোলে —
 * সেখানে .ts সহ যেকোনো ভিডিও বাছা যায় (অনুমতিও স্থায়ী থাকে)।
 * @param images ছবিও বাছা যাবে কিনা (false = শুধু ভিডিও)
 * @param single একটাই বাছা যাবে কিনা
 * @return ডাকলে প্রশ্ন দেখায়
 */
@Composable
fun rememberMediaPicker(images: Boolean, single: Boolean, onPicked: (List<Uri>) -> Unit): () -> Unit {
    val cb by rememberUpdatedState(onPicked)
    var ask by remember { mutableStateOf(false) }
    val galleryKind = if (images) ActivityResultContracts.PickVisualMedia.ImageAndVideo else ActivityResultContracts.PickVisualMedia.VideoOnly
    val galleryMulti = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { if (it.isNotEmpty()) cb(it) }
    val gallerySingle = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { if (it != null) cb(listOf(it)) }
    val filesMulti = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) cb(it) }
    val filesSingle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) cb(listOf(it)) }
    // .ts ফাইলের MIME ফোনভেদে video/mp2t বা অজানা (octet-stream) হয়, তাই সবগুলো দেওয়া
    val types: Array<String> = if (images) {
        arrayOf("video/*", "image/*", "video/mp2t", "video/vnd.dlna.mpeg-tts", "application/octet-stream")
    } else {
        arrayOf("video/*", "video/mp2t", "video/vnd.dlna.mpeg-tts", "application/octet-stream")
    }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text("কোথা থেকে বাছবেন?") },
            text = { Text("গ্যালারি থেকে অথবা ফাইল থেকে। .ts সহ যেকোনো ভিডিও (MP4, MKV, TS…) \"ফাইল\" থেকে বাছুন।") },
            confirmButton = {
                TextButton(onClick = {
                    ask = false
                    if (single) filesSingle.launch(types) else filesMulti.launch(types)
                }) { Text("📁 ফাইল (.ts সহ)") }
            },
            dismissButton = {
                TextButton(onClick = {
                    ask = false
                    val req = PickVisualMediaRequest(galleryKind)
                    if (single) gallerySingle.launch(req) else galleryMulti.launch(req)
                }) { Text("🖼️ গ্যালারি") }
            },
        )
    }
    return { ask = true }
}
