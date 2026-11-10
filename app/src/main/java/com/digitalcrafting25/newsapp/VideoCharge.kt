package com.digitalcrafting25.newsapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ভিডিওর টাকা কাটা (Worker `/vid-charge`)। দাম: পাবলিশ — Facebook ৫, YouTube ২, WordPress ৩ (যেগুলো বাছা হয় তার যোগফল);
 * ডাউনলোড — Facebook ভিডিও ৩, YouTube ভিডিও ২। একই ভিডিও + একই আইটেমের জন্য একবার কাটলে আর কাটে না (সার্ভারেই মনে রাখে)।
 * কাজ শুরুর আগে [check] (কাটে না), কাজ সফল হলে [commit] (কাটে)।
 */
object VideoCharge {
    const val PUB_FB = "pf"; const val PUB_YT = "py"; const val PUB_WP = "pw"
    const val DL_FB = "df"; const val DL_YT = "dy"

    /** দাম (টাকা) — অ্যাডমিন অ্যাপের 💰 রেট থেকে বদলায় (Rates); Worker-ও একই রেটে কাটে */
    val price: Map<String, Int>
        get() = mapOf(PUB_FB to Rates.get(PUB_FB), PUB_YT to Rates.get(PUB_YT), PUB_WP to Rates.get(PUB_WP), DL_FB to Rates.get(DL_FB), DL_YT to Rates.get(DL_YT))
    fun sum(items: Collection<String>): Int = items.sumOf { price[it] ?: 0 }

    class Check(val ok: Boolean, val need: Int, val balance: Double)
    class Done(val charged: Double, val balance: Double)

    private fun body(vid: String, items: Collection<String>, mode: String) =
        JSONObject().put("vid", vid).put("items", JSONArray(items.toList())).put("mode", mode)

    /** টাকা যথেষ্ট কিনা (কাটে না)। যোগাযোগ ব্যর্থ হলে ব্যতিক্রম ছোঁড়ে */
    suspend fun check(ctx: Context, vid: String, items: Collection<String>): Check {
        val r = Worker.post(ctx, "/vid-charge", body(vid, items, "check"), 30000)
        return Check(r.optBoolean("ok"), r.optInt("need"), r.optDouble("balance", 0.0))
    }

    /** কাজ সফল হওয়ার পর টাকা কাটা; একটু ব্যর্থ হলে একবার আবার চেষ্টা */
    suspend fun commit(ctx: Context, vid: String, items: Collection<String>): Done? {
        repeat(2) {
            try {
                val r = Worker.post(ctx, "/vid-charge", body(vid, items, "commit"), 30000)
                return Done(r.optDouble("charged", 0.0), r.optDouble("balance", 0.0))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
        return null
    }

    fun lowMsg(c: Check) = "ব্যালেন্স কম আছে (দরকার ৳${Bn.num(c.need)}, আছে ৳${Bn.num(c.balance.toInt())})। ড্যাশবোর্ড থেকে ব্যালেন্স যোগ করুন"
}
