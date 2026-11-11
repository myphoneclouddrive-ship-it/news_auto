package com.digitalcrafting25.newsapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** ভিডিও এডিটর খোলা থাকলে পুরো স্ক্রিন এডিটরের: ওপরের হেডার ও নিচের মেনুবার লুকানো থাকে */
object EditorUi {
    var fullscreen by mutableStateOf(false)
}
