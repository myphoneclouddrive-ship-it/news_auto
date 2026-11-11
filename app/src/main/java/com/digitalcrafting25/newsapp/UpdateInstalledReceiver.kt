package com.digitalcrafting25.newsapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** অ্যাপ আপডেট ইন্সটল হওয়ামাত্র (অ্যাপ না খুলেই) ডাউনলোড করা APK ও আপডেটের নোটিফিকেশন মুছে দেয় */
class UpdateInstalledReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching { Updater.cleanup(ctx) }
        runCatching { Notif.cancel(ctx, 1006) }
    }
}
