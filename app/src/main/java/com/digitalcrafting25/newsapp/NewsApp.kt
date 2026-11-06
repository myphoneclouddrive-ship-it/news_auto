package com.digitalcrafting25.newsapp

import android.app.Application

class NewsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notif.init(this)
        Session.restore(this)   // সংরক্ষিত লগইন ফিরিয়ে আনা
        // আগের ভার্সনে নামানো পুরনো আপডেট APK মুছে জায়গা খালি করা
        filesDir.listFiles()?.filter { it.name.startsWith("update-") }?.forEach { it.delete() }
    }
}
