package com.digitalcrafting25.newsapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** ইন্টারনেট আছে কিনা — সংযোগ বদলালে নিজে থেকেই আপডেট হয় */
object Net {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online

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
        _online.value = check(cm)
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { _online.value = check(cm) }
                override fun onLost(network: Network) { _online.value = check(cm) }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            })
        } catch (_: Exception) { /* কলব্যাক না বসলে শুরুর অবস্থাই থাকবে */ }
    }
}
