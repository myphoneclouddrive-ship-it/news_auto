package com.digitalcrafting25.newsapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.HijrahDate
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** ধাপ ৪: 🌅 শুভ সকাল কার্ড — ওয়েবের MN_* হিসাবের হুবহু অনুবাদ */

/** ঢাকার আজকের আবহাওয়া (Open-Meteo) */
class MnWeather(val day: String, val code: Int, val max: Int, val min: Int, val rain: Int?)

class MnSpec(val k: Int, val name: String, val ac: String, val tint: String, val ln: String)

val MN_SPECS = listOf(
    MnSpec(1, "সোনালি সূর্যোদয়", "#c45c0e", "#f9eee6", "#efd3be"),
    MnSpec(2, "গোলাপি ভোর", "#6e3896", "#f0ebf4", "#d7c9e2"),
    MnSpec(3, "সবুজ প্রকৃতি", "#246e30", "#e9f0ea", "#c4d7c7"),
    MnSpec(4, "নীল আকাশ", "#185cbe", "#e7eef8", "#c0d3ed"),
    MnSpec(5, "ক্রিম মিনিমাল", "#d67814", "#faf1e7", "#f3dac0"),
)

/** শুভ সকাল টেমপ্লেটের id ("morning1".."morning5") */
val MORNING_TPLS = MN_SPECS.map { "morning${it.k}" to it.name }

fun isMorningId(id: String) = id.startsWith("morning")

object MnCal {
    /** বাংলাদেশে আরবি তারিখ সৌদির (উম্মুল কুরা) চেয়ে সাধারণত ১ দিন পেছনে থাকে; মিলছে না মনে হলে 0 বা -2 করুন */
    private const val HIJRI_ADJ = -1L

    val WD = listOf("রবিবার", "সোমবার", "মঙ্গলবার", "বুধবার", "বৃহস্পতিবার", "শুক্রবার", "শনিবার")
    val EM = listOf("জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন", "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর")
    val BM = listOf("বৈশাখ", "জ্যৈষ্ঠ", "আষাঢ়", "শ্রাবণ", "ভাদ্র", "আশ্বিন", "কার্তিক", "অগ্রহায়ণ", "পৌষ", "মাঘ", "ফাল্গুন", "চৈত্র")
    val SE = listOf("গ্রীষ্মকাল", "গ্রীষ্মকাল", "বর্ষাকাল", "বর্ষাকাল", "শরৎকাল", "শরৎকাল", "হেমন্তকাল", "হেমন্তকাল", "শীতকাল", "শীতকাল", "বসন্তকাল", "বসন্তকাল")
    val HM = listOf("মুহাররম", "সফর", "রবিউল আউয়াল", "রবিউস সানি", "জমাদিউল আউয়াল", "জমাদিউস সানি", "রজব", "শাবান", "রমজান", "শাওয়াল", "জিলকদ", "জিলহজ")

    fun n(v: Any): String = v.toString().map { if (it in '0'..'9') "০১২৩৪৫৬৭৮৯"[it - '0'] else it }.joinToString("")

    /** ঢাকার (UTC+6) আজকের তারিখ; ডিভাইসের টাইমজোন যা-ই হোক */
    fun today(): LocalDate = LocalDate.now(ZoneId.of("Asia/Dhaka"))

    fun iso(d: LocalDate): String = d.toString()   // yyyy-MM-dd

    /** রবিবার = 0 … শনিবার = 6 */
    fun wd(d: LocalDate): Int = d.dayOfWeek.value % 7

    class Bn(val d: Int, val m: Int, val y: Int)

    /** বাংলাদেশের সংশোধিত বাংলা পঞ্জিকা (বৈশাখ ১ = ১৪ এপ্রিল) */
    fun bangla(date: LocalDate): Bn {
        val y = date.year; val m = date.monthValue; val d = date.dayOfMonth
        val s = listOf(
            4 to 14, 5 to 15, 6 to 15, 7 to 16, 8 to 16, 9 to 16,
            10 to 16, 11 to 15, 12 to 15, 1 to 14, 2 to 13, 3 to 15,
        )
        val t = date.toEpochDay()
        var best = Long.MIN_VALUE; var bi = 0
        s.forEachIndexed { i, (sm, sd) ->
            for (yy in intArrayOf(y, y - 1)) {
                val st = LocalDate.of(yy, sm, sd).toEpochDay()
                if (st <= t && st > best) { best = st; bi = i }
            }
        }
        val by = if (m > 4 || (m == 4 && d >= 14)) y - 593 else y - 594
        return Bn((t - best).toInt() + 1, bi, by)
    }

    class Hj(val d: Int, val m: Int, val y: Int)

    fun hijri(date: LocalDate): Hj? = runCatching {
        val h = HijrahDate.from(date.plusDays(HIJRI_ADJ))
        Hj(h.get(java.time.temporal.ChronoField.DAY_OF_MONTH), h.get(java.time.temporal.ChronoField.MONTH_OF_YEAR), h.get(java.time.temporal.ChronoField.YEAR))
    }.getOrNull()?.takeIf { it.d > 0 && it.m in 1..12 && it.y > 0 }

    /** ঢাকার সূর্যোদয়/সূর্যাস্ত (মিনিট, মধ্যরাত থেকে) */
    fun sun(date: LocalDate, rising: Boolean): Int {
        val r = Math.PI / 180; val lat = 23.8103; val lon = 90.4125
        val nd = date.dayOfYear; val lh = lon / 15
        val t = nd + ((if (rising) 6 else 18) - lh) / 24
        val mm = 0.9856 * t - 3.289
        var l = mm + 1.916 * sin(mm * r) + 0.020 * sin(2 * mm * r) + 282.634
        l = ((l % 360) + 360) % 360
        var ra = atan(0.91764 * tan(l * r)) / r
        ra = ((ra % 360) + 360) % 360
        ra += floor(l / 90) * 90 - floor(ra / 90) * 90
        ra /= 15
        val sd = 0.39782 * sin(l * r)
        val cd = cos(asin(sd))
        val ch = max(-1.0, min(1.0, (cos(90.833 * r) - sd * sin(lat * r)) / (cd * cos(lat * r))))
        val h = (if (rising) 360 - acos(ch) / r else acos(ch) / r) / 15
        val tt = h + ra - 0.06571 * t - 6.622
        val loc = (((tt - lh) % 24) + 24 + 6) % 24
        return if (rising) (loc * 60).roundToInt() else floor(loc * 60).toInt()
    }

    private fun period(h: Int) = when {
        h < 4 -> "রাত"; h < 6 -> "ভোর"; h < 12 -> "সকাল"; h < 15 -> "দুপুর"
        h < 17 -> "বিকাল"; h < 19 -> "সন্ধ্যা"; else -> "রাত"
    }

    /** মিনিট → "সকাল ৬:৩০ টা" */
    fun clock(min: Int): String {
        val h = min / 60
        return period(h) + " " + n(if (h % 12 == 0) 12 else h % 12) + ":" + n((min % 60).toString().padStart(2, '0')) + " টা"
    }

    /** আবহাওয়ার কোড → (বাংলা অবস্থা, আইকন: sun | part | cloud | rain) */
    fun wx(c: Int): Pair<String, String> = when {
        c <= 1 -> "রৌদ্রোজ্জ্বল" to "sun"
        c == 2 -> "আংশিক মেঘলা" to "part"
        c == 3 -> "মেঘলা" to "cloud"
        c <= 48 -> "কুয়াশাচ্ছন্ন" to "cloud"
        c <= 57 -> "গুঁড়ি বৃষ্টি" to "rain"
        c <= 67 -> "বৃষ্টি" to "rain"
        c <= 77 -> "মেঘলা" to "cloud"
        c <= 82 -> "ভারী বর্ষণ" to "rain"
        else -> "বজ্রসহ বৃষ্টি" to "rain"
    }

    /** ঢাকার আজকের আবহাওয়া (বিনা চাবি, Open-Meteo); না এলে null — তখন কার্ডে ওই বক্স আসে না */
    fun fetchWeather(): MnWeather? = runCatching {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=23.8103&longitude=90.4125" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=Asia%2FDhaka&forecast_days=1"
        val r = httpCall("GET", url, readMs = 15000)
        if (r.code !in 200..299) return@runCatching null
        val dly = JSONObject(r.text).getJSONObject("daily")
        val rain = dly.optJSONArray("precipitation_probability_max")?.let { if (it.isNull(0)) null else it.optDouble(0).roundToInt() }
        MnWeather(
            day = dly.getJSONArray("time").getString(0),
            code = dly.getJSONArray("weather_code").getInt(0),
            max = dly.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            min = dly.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            rain = rain,
        )
    }.getOrNull()

    /** ৫টি ইলাস্ট্রেশন (assets/morning/morning-N.jpg) */
    fun loadBgs(ctx: Context): Map<Int, Bitmap> {
        val out = HashMap<Int, Bitmap>()
        for (s in MN_SPECS) {
            runCatching {
                ctx.assets.open("morning/morning-${s.k}.jpg").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()?.let { out[s.k] = it }
        }
        return out
    }
}
