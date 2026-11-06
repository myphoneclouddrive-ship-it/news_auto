package com.digitalcrafting25.newsapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * ইন্টারনেট চলে গেলে অফলাইন স্ক্রিন "লক" হয়ে যায়। সংযোগ ফিরলেও নিজে থেকে সরে না —
 * ইউজার "চেক করুন" চাপলে আসল সংযোগ ও Firebase যাচাই হয়; ঠিক থাকলে লক খুলে ডেটা নতুন করে আনা হয়।
 */
object Net {
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked

    /** প্রতিবার সফল চেকের পর বাড়ে; স্ক্রিনগুলো এটা দেখে Firebase থেকে ডেটা আবার আনে */
    private val _refreshTick = MutableStateFlow(0)
    val refreshTick: StateFlow<Int> = _refreshTick

    private var started = false

    private fun check(cm: ConnectivityManager): Boolean = try {
        val n = cm.activeNetwork
        val c = if (n != null) cm.getNetworkCapabilities(n) else null
        c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        true // যাচাই করা না গেলে অ্যাপ আটকাবো না
    }

    fun start(ctx: Context) {
        if (started) return
        started = true
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (!check(cm)) _locked.value = true
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                // সংযোগ গেলে লক হয়; ফিরলে লক খোলে না — চেক বাটন চাপতে হবে
                override fun onLost(network: Network) { if (!check(cm)) _locked.value = true }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) _locked.value = true
                }
            })
        } catch (_: Exception) { /* কলব্যাক না বসলে শুরুর অবস্থাই থাকবে */ }
    }

    /**
     * "চেক করুন": ফোনের নেটওয়ার্ক → Firebase সার্ভারে আসল যোগাযোগ → লগইন টোকেন রিফ্রেশ।
     * সব ঠিক থাকলে লক খোলে ও ডেটা নতুন করে আনার সংকেত দেয়। সংযোগ না থাকলে false।
     */
    suspend fun verify(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (!check(cm)) return false
        try {
            // আসল ইন্টারনেট আছে কিনা: Firebase-এর সার্ভারে পৌঁছানো গেলেই হলো (যে কোনো সাড়া)
            withContext(Dispatchers.IO) { httpCall("GET", "https://firestore.googleapis.com/", readMs = 8000) }
            // লগইন থাকলে Firebase টোকেন নতুন করে নেওয়া; মেয়াদ শেষ হলে এখানেই লগইন স্ক্রিনে যাবে
            if (Session.state.value) Session.token(ctx, force = true)
        } catch (e: ApiError) {
            if (e.status == 401) { _locked.value = false; return true } // লগআউট হয়ে গেছে; লগইন স্ক্রিন দেখাবে
            return false
        } catch (e: Exception) {
            return false
        }
        _locked.value = false
        _refreshTick.value = _refreshTick.value + 1
        return true
    }
}
