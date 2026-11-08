package com.digitalcrafting25.newsapp

import android.app.Application

class NewsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notif.init(this)
        BrandFont.init(this)    // Noto Sans Bengali (HTML-এর ফন্ট)
        Net.start(this)         // ইন্টারনেট আছে কিনা নজর রাখা
        Session.restore(this)   // সংরক্ষিত লগইন ফিরিয়ে আনা
        // ইন্সটল হয়ে যাওয়া আপডেটের APK মুছে ফেলা
        runCatching { Updater.cleanup(this) }
        if (BuildConfig.IS_ADMIN) runCatching { BkashPrefs.syncSchedule(this) }   // বিকাশ/নগদ SMS অটো কনফার্ম চালু থাকলে ছুটে-যাওয়া SMS-এর স্ক্যান বসানো
    }
}
