package com.digitalcrafting25.newsapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private lateinit var cm: ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null

    /**
     * নেটওয়ার্ক গেছে মনে হলে সাথে সাথে লক করি না: স্ক্রিন অফ/অন বা ওয়াইফাই↔মোবাইল বদলের সময় সংযোগ কিছুক্ষণের জন্য ঝলক দেয়।
     * কয়েক সেকেন্ড অপেক্ষা করে তবুও না থাকলে তবেই লক হয় — আর অ্যাপ ব্যাকগ্রাউন্ডে থাকলে, বা AI লেখা চলার সময় লক হয় না।
     */
    private fun lockIfStillOffline(graceMs: Long = 6000L) {
        pending?.cancel()
        pending = scope.launch {
            var waited = 0L
            while (waited < graceMs) {
                if (check(cm)) return@launch
                delay(500); waited += 500
            }
            if (!check(cm) && AppState.foreground && !AiKeepAliveService.active) _locked.value = true
        }
    }

    /** অ্যাপ সামনে এলে (স্ক্রিন অন/অ্যাপে ফেরা) বা AI কাজ শেষে: সংযোগ সত্যিই না থাকলে তবেই লক */
    fun recheck() { if (started && !check(cm)) lockIfStillOffline() }

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
        cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (!check(cm)) lockIfStillOffline()
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                // সংযোগ গেলে লক হয়; ফিরলে লক খোলে না — চেক বাটন চাপতে হবে
                override fun onLost(network: Network) { if (!check(cm)) lockIfStillOffline() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) lockIfStillOffline()
                }
            })
        } catch (_: Exception) { /* কলব্যাক না বসলে শুরুর অবস্থাই থাকবে */ }
    }

    /**
     * "চেক করুন": ফোনের নেটওয়ার্ক → Firebase সার্ভারে আসল যোগাযোগ → লগইন টোকেন রিফ্রেশ।
     * সব ঠিক থাকলে লক খোলে ও ডেটা নতুন করে আনার সংকেত দেয়। সংযোগ না থাকলে false।
     */
    suspend fun verify(ctx: Context): Boolean {
        val cmv = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (!check(cmv)) return false
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
