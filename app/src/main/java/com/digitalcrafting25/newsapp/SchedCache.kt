package com.digitalcrafting25.newsapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File

/**
 * সিডিউল করা খবর-পোস্টের লেখা ও ছবির কপি (এই ফোনে)। Worker-এর তালিকায় শুধু শিরোনাম/সময় আসে,
 * তাই "এডিট করুন" চাপলে ধাপ ২ থেকে শুরু করতে এই কপি থেকেই লেখা-ছবি ফেরত আনা হয়। চাবি = Worker-এর সিডিউল আইডি (তালিকার শিরোনাম লম্বা হলে Worker কেটে দেয়, তাই শিরোনাম চাবি হয় না)।
 */
object SchedCache {
    class Entry(val data: JSONObject, val photo: Bitmap?)

    private fun key(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "")
    private fun dir(ctx: Context) = File(ctx.filesDir, "sched").apply { mkdirs() }

    fun save(ctx: Context, id: String, data: JSONObject, photo: Bitmap?) {
        runCatching {
            val k = key(id)
            File(dir(ctx), "$k.json").writeText(data.toString())
            val pf = File(dir(ctx), "$k.jpg")
            if (photo == null) pf.delete() else pf.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }
    }

    fun load(ctx: Context, id: String): Entry? = runCatching {
        val k = key(id)
        val jf = File(dir(ctx), "$k.json")
        if (!jf.exists()) return null
        val photo = File(dir(ctx), "$k.jpg").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        Entry(JSONObject(jf.readText()), photo)
    }.getOrNull()

    fun delete(ctx: Context, id: String) {
        runCatching {
            val k = key(id)
            File(dir(ctx), "$k.json").delete(); File(dir(ctx), "$k.jpg").delete()
        }
    }
}
