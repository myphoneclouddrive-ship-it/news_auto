package com.digitalcrafting25.newsapp

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class NewsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notif.init(this)
        Session.restore(this)   // সংরক্ষিত লগইন ফিরিয়ে আনা
        // প্রতিদিন ১ বার অটো আপডেট চেক
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("daily-update-check", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
