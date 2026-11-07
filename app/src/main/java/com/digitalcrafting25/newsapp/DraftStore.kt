package com.digitalcrafting25.newsapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * অসমাপ্ত ভিডিও এডিট ফোনে সেভ রাখে (অ্যাপ কেটে দিলেও থাকবে)।
 *  - এডিটের তথ্য (ক্লিপ + লেয়ার) `filesDir/editor_drafts.json`-এ
 *  - গ্যালারির ফাইলের অনুমতি স্থায়ী করা যায় না এমন হলে ফাইলটা `filesDir/draft_media/`-এ কপি হয়ে থাকে
 */
object DraftStore {
    private val lock = Any()
    private fun store(ctx: Context) = File(ctx.filesDir, "editor_drafts.json")
    private fun mediaDir(ctx: Context) = File(ctx.filesDir, "draft_media")

    /** ব্লকিং — IO থ্রেডে ডাকুন। অনুমতি স্থায়ী হলে একই Uri, নইলে অ্যাপের ভেতরে কপি করা ফাইলের Uri */
    fun keep(ctx: Context, uri: Uri): Uri {
        if (uri.scheme == "file") return uri
        val persisted = runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (persisted) return uri
        return runCatching {
            val dir = mediaDir(ctx).apply { mkdirs() }
            val mime = ctx.contentResolver.getType(uri)
            val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "bin"
            val f = File(dir, "${System.currentTimeMillis()}_${Math.abs(uri.hashCode())}.$ext")
            ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            Uri.fromFile(f)
        }.getOrDefault(uri)
    }

    fun readable(ctx: Context, uri: Uri): Boolean = runCatching {
        ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    /** কপি করা মিডিয়ার যে ফাইলগুলো আর কোথাও লাগছে না সেগুলো মোছে */
    fun deleteUnused(ctx: Context, inUse: Set<String>) {
        mediaDir(ctx).listFiles()?.forEach { f ->
            if (Uri.fromFile(f).toString() !in inUse) runCatching { f.delete() }
        }
    }

    fun urisOf(clips: List<Clip>, layers: List<Layer>): Set<String> {
        val out = HashSet<String>()
        clips.forEach { c ->
            out.add(c.uri.toString())
            c.card?.logo?.let { out.add(it.toString()) }
        }
        layers.forEach { l ->
            when (l) {
                is ImageLayer -> l.uri?.let { out.add(it.toString()) }
                is AdLayer -> l.uri?.let { out.add(it.toString()) }
                is AudioLayer -> l.uri?.let { out.add(it.toString()) }
                else -> {}
            }
        }
        return out
    }

    fun save(ctx: Context, drafts: List<EditorViewModel.Draft>) {
        val arr = JSONArray()
        drafts.forEach { d ->
            arr.put(JSONObject().apply {
                put("id", d.id)
                put("savedAt", d.savedAt)
                put("clips", JSONArray().also { a -> d.clips.forEach { a.put(clipToJson(it)) } })
                put("layers", JSONArray().also { a -> d.layers.forEach { a.put(layerToJson(it)) } })
            })
        }
        synchronized(lock) {
            runCatching {
                val tmp = File(ctx.filesDir, "editor_drafts.json.tmp")
                tmp.writeText(arr.toString())
                if (!tmp.renameTo(store(ctx))) { store(ctx).writeText(arr.toString()); tmp.delete() }
            }
        }
    }

    fun load(ctx: Context): List<EditorViewModel.Draft> = synchronized(lock) {
        val f = store(ctx)
        if (!f.exists()) return@synchronized emptyList()
        runCatching {
            val arr = JSONArray(f.readText())
            val out = ArrayList<EditorViewModel.Draft>()
            for (i in 0 until arr.length()) {
                runCatching {
                    val o = arr.getJSONObject(i)
                    val cs = o.getJSONArray("clips")
                    val ls = o.getJSONArray("layers")
                    val clips = (0 until cs.length()).mapNotNull { runCatching { clipFromJson(cs.getJSONObject(it)) }.getOrNull() }
                    val layers = (0 until ls.length()).mapNotNull { runCatching { layerFromJson(ls.getJSONObject(it)) }.getOrNull() }
                    if (clips.isNotEmpty()) out.add(EditorViewModel.Draft(o.getLong("id"), clips, layers, o.optLong("savedAt")))
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- JSON ----

    private fun JSONObject.uriOrNull(k: String): Uri? =
        if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }

    private fun JSONObject.f(k: String, d: Float): Float = optDouble(k, d.toDouble()).toFloat()

    private fun clipToJson(c: Clip) = JSONObject().apply {
        put("id", c.id); put("uri", c.uri.toString()); put("isImage", c.isImage)
        put("dur", c.durationMs); put("name", c.name)
        put("startSec", c.startSec.toDouble()); put("endSec", c.endSec.toDouble())
        put("imageSec", c.imageSec.toDouble()); put("volume", c.volume.toDouble()); put("muted", c.muted)
        put("flipH", c.flipH); put("flipV", c.flipV)
        put("zoom", c.zoom.toDouble()); put("panX", c.panX.toDouble()); put("panY", c.panY.toDouble())
        c.card?.let {
            put("card", JSONObject().apply {
                put("outro", it.outro); put("style", it.style.name)
                put("title", it.title); put("subtitle", it.subtitle)
                it.logo?.let { l -> put("logo", l.toString()) }
            })
        }
    }

    private fun clipFromJson(o: JSONObject): Clip {
        val card = if (o.has("card")) o.getJSONObject("card").let {
            CardSpec(
                outro = it.optBoolean("outro"),
                style = runCatching { CardStyle.valueOf(it.optString("style")) }.getOrDefault(CardStyle.RED),
                title = it.optString("title"),
                subtitle = it.optString("subtitle"),
                logo = it.uriOrNull("logo"),
            )
        } else null
        return Clip(
            id = o.getLong("id"), uri = Uri.parse(o.getString("uri")), isImage = o.optBoolean("isImage"),
            durationMs = o.optLong("dur"), name = o.optString("name"),
            startSec = o.f("startSec", 0f), endSec = o.f("endSec", 0f), imageSec = o.f("imageSec", 3f),
            volume = o.f("volume", 1f), muted = o.optBoolean("muted"),
            flipH = o.optBoolean("flipH"), flipV = o.optBoolean("flipV"),
            zoom = o.f("zoom", 1f), panX = o.f("panX", 0f), panY = o.f("panY", 0f),
            card = card,
        )
    }

    private fun layerToJson(l: Layer): JSONObject = JSONObject().apply {
        put("id", l.id); put("s", l.startMs); put("e", l.endMs)
        when (l) {
            is TextLayer -> {
                put("t", "text"); put("text", l.text); put("color", l.color); put("bg", l.bgColor)
                put("size", l.sizeFrac.toDouble()); put("bold", l.bold)
                put("x", l.x.toDouble()); put("y", l.y.toDouble())
            }
            is ImageLayer -> {
                put("t", "image"); l.uri?.let { put("uri", it.toString()) }; put("logo", l.isLogo)
                put("x", l.x.toDouble()); put("y", l.y.toDouble())
                put("w", l.widthFrac.toDouble()); put("opacity", l.opacity.toDouble())
            }
            is TagLayer -> {
                put("t", "tag"); put("text", l.text); put("corner", l.corner.name)
                put("color", l.color); put("bg", l.bgColor); put("size", l.sizeFrac.toDouble())
            }
            is TickerLayer -> {
                put("t", "ticker"); put("text", l.text); put("color", l.color); put("bg", l.bgColor)
                put("barH", l.barHeightFrac.toDouble()); put("speed", l.speed.toDouble())
                put("bottom", l.bottomFrac.toDouble())
            }
            is NameBarLayer -> {
                put("t", "name"); put("name", l.name); put("title", l.title)
                put("accent", l.accent); put("bg", l.bgColor); put("textColor", l.textColor)
                put("bottom", l.bottomFrac.toDouble())
            }
            is AdLayer -> {
                put("t", "ad"); l.uri?.let { put("uri", it.toString()) }
                put("top", l.isTop); put("h", l.heightFrac.toDouble())
            }
            is OverlayLayer -> {
                put("t", "overlay"); put("color", l.color); put("alpha", l.alpha.toDouble())
            }
            is AdjustLayer -> {
                put("t", "adjust"); put("brightness", l.brightness.toDouble())
            }
            is AudioLayer -> {
                put("t", "audio"); put("kind", l.kind.name); l.uri?.let { put("uri", it.toString()) }
                put("name", l.name); put("volume", l.volume.toDouble()); put("off", l.sourceOffsetMs)
                l.sourceClipId?.let { put("src", it) }
                put("muted", l.muted); put("sdur", l.sourceDurationMs)
            }
        }
    }

    private fun layerFromJson(o: JSONObject): Layer? {
        val id = o.getLong("id"); val s = o.getLong("s"); val e = o.getLong("e")
        return when (o.getString("t")) {
            "text" -> TextLayer(
                id, s, e, o.optString("text"), o.optInt("color", -1), o.optInt("bg", 0),
                o.f("size", 0.07f), o.optBoolean("bold", true), o.f("x", 0.5f), o.f("y", 0.5f),
            )
            "image" -> ImageLayer(
                id, s, e, o.uriOrNull("uri"), o.optBoolean("logo"),
                o.f("x", 0.5f), o.f("y", 0.5f), o.f("w", 0.25f), o.f("opacity", 1f),
            )
            "tag" -> TagLayer(
                id, s, e, o.optString("text"),
                runCatching { Corner.valueOf(o.optString("corner")) }.getOrDefault(Corner.TOP_LEFT),
                o.optInt("color", -1), o.optInt("bg", 0xFFD32F2F.toInt()), o.f("size", 0.045f),
            )
            "ticker" -> TickerLayer(
                id, s, e, o.optString("text"), o.optInt("color", -1), o.optInt("bg", 0xFFD32F2F.toInt()),
                o.f("barH", 0.08f), o.f("speed", 0.15f), o.f("bottom", 0f),
            )
            "name" -> NameBarLayer(
                id, s, e, o.optString("name"), o.optString("title"),
                o.optInt("accent", 0xFFD32F2F.toInt()), o.optInt("bg", 0xFF1B1E26.toInt()),
                o.optInt("textColor", -1), o.f("bottom", 0.12f),
            )
            "ad" -> AdLayer(id, s, e, o.uriOrNull("uri"), o.optBoolean("top"), o.f("h", 0.18f))
            "overlay" -> OverlayLayer(id, s, e, o.optInt("color", 0xFF000000.toInt()), o.f("alpha", 0.35f))
            "adjust" -> AdjustLayer(id, s, e, o.f("brightness", 0f))
            "audio" -> AudioLayer(
                id, s, e,
                runCatching { LayerKind.valueOf(o.optString("kind")) }.getOrDefault(LayerKind.AUDIO),
                o.uriOrNull("uri"), o.optString("name"), o.f("volume", 1f), o.optLong("off"),
                if (o.has("src")) o.getLong("src") else null,
                o.optBoolean("muted"), o.optLong("sdur"),
            )
            else -> null
        }
    }
}
