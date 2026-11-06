package com.digitalcrafting25.newsapp

import android.app.Application

class NewsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notif.init(this)
        Session.restore(this)   // সংরক্ষিত লগইন ফিরিয়ে আনা
        // ইন্সটল হয়ে যাওয়া আপডেটের APK মুছে ফেলা
        runCatching { Updater.cleanup(this) }
    }
}
