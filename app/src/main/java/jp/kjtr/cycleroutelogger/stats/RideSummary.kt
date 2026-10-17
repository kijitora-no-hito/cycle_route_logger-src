package jp.kjtr.cycleroutelogger.stats

import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.record.CountedStep
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** まとめの期間。 */
enum class SummaryPeriod { ALL, THIS_YEAR, THIS_MONTH, LAST_30_DAYS, CUSTOM }

/** まとめの対象（フリー / ルートの別）。 */
enum class ModeFilter { ALL, FREE, ROUTE }

/** 期間 [fromMs, toMs)（null = 区切りなし）。 */
data class TimeRange(val fromMs: Long?, val toMs: Long?) {
    operator fun contains(t: Long): Boolean = (fromMs == null || t >= fromMs) && (toMs == null || t < toMs)
}

/** まとめに使う記録 1 本分（記録の行と、点から計算したもの）。 */
data class RideFacts(
    val id: Long,
    val name: String,
    val startedAt: Long,
    val endedAt: Long,
    val distanceM: Double,
    val movingMs: Long,
    val maxSpeedMps: Double,
    /** 獲得標高 [m]（記録の行の値）。 */
    val ascentM: Double = 0.0,
    /** ルートサイクリングなら true。 */
    val route: Boolean = false,
    /** 記録に紐づいた写真の数。 */
    val photos: Int = 0,
    /** 速度の分布（[RideSummaries.BIN_KMH] ごとの bin 番号 → 時間 [ms]）。 */
    val speedBinsMs: Map<Int, Long> = emptyMap(),
    /** 速度の分布（bin 番号 → 距離 [m]）。 */
    val speedBinsM: Map<Int, Double> = emptyMap(),
    /** 走ったマス目（[GridCells.cellOf]）。 */
    val cells: Set<Long> = emptySet(),
    /**
     * 走行時間に数えた時間帯（開始, 終了）[ms]。曜日・時間帯のグラフに使う（自動一時停止・一時停止の間は入らない）。
     * 空なら記録の開始〜終了で数える。
     */
    val movingSpans: List<Pair<Long, Long>> = emptyList(),
)

/** まとめの結果。 */
data class RideSummary(
    val rideCount: Int,
    val distanceM: Double,
    val movingMs: Long,
    val avgSpeedMps: Double,
    /** 最高速度とその記録。 */
    val maxSpeed: Pair<Double, RideFacts>?,
    /** いちばん長い距離の記録。 */
    val longestDistance: RideFacts?,
    /** いちばん長い時間の記録（経過時間）。 */
    val longestTime: RideFacts?,
    val ascentM: Double,
    /** 走った日数（端末のタイムゾーンの日付で数える）。 */
    val days: Int,
    val photos: Int,
    /** 月ごとの距離（"2026-10" → m。古い順。間の 0 の月も入れる）。 */
    val monthlyM: List<Pair<String, Double>>,
    /** 曜日ごとの走行時間 [ms]（0 = 日曜 … 6 = 土曜）。 */
    val weekdayMs: LongArray,
    /** 時間帯ごとの走行時間 [ms]（0〜23 時）。 */
    val hourMs: LongArray,
    /** 速度の分布（記録の詳細と同じ 2 km/h ごと）。 */
    val histogram: SpeedHistogram,
    /** 開拓したマス（1 km 四方）の数。 */
    val cells: Int,
)

/**
 * 全記録のまとめ（純ロジック。単体テスト: RideSummaryTest）。drive_logger の DriveSummaries を自転車向けにしたもの。
 * 期間は記録の開始時刻で選ぶ。曜日・時間帯は走行時間に数えた時間帯（[RideFacts.movingSpans]）をその時刻の枠に割り振る（端末のタイムゾーン）。
 * 速度の分布・走行時間は記録の点を [jp.kjtr.cycleroutelogger.record.RideTracker] に流し直したもの（跳びの除去 100 km/h・自動一時停止は記録と同じ扱い）。
 */
object RideSummaries {
    /** 速度の分布の幅 [km/h]（記録の詳細のヒストグラムと同じ）。 */
    const val BIN_KMH = 2.0

    /** [period] の範囲（[now] 基準。CUSTOM は [custom]）。 */
    fun range(period: SummaryPeriod, now: Long, zone: TimeZone = TimeZone.getDefault(), custom: TimeRange? = null): TimeRange {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = now
        fun startOfDay(c: Calendar) {
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        }
        return when (period) {
            SummaryPeriod.ALL -> TimeRange(null, null)
            SummaryPeriod.THIS_YEAR -> {
                startOfDay(cal); cal.set(Calendar.DAY_OF_YEAR, 1)
                val from = cal.timeInMillis
                cal.add(Calendar.YEAR, 1)
                TimeRange(from, cal.timeInMillis)
            }
            SummaryPeriod.THIS_MONTH -> {
                startOfDay(cal); cal.set(Calendar.DAY_OF_MONTH, 1)
                val from = cal.timeInMillis
                cal.add(Calendar.MONTH, 1)
                TimeRange(from, cal.timeInMillis)
            }
            SummaryPeriod.LAST_30_DAYS -> TimeRange(now - 30L * 86_400_000L, null)
            SummaryPeriod.CUSTOM -> custom ?: TimeRange(null, null)
        }
    }

    fun filter(rides: List<RideFacts>, r: TimeRange, mode: ModeFilter = ModeFilter.ALL): List<RideFacts> = rides.filter {
        it.startedAt in r && when (mode) {
            ModeFilter.ALL -> true
            ModeFilter.FREE -> !it.route
            ModeFilter.ROUTE -> it.route
        }
    }

    fun summarize(rides: List<RideFacts>, zone: TimeZone = TimeZone.getDefault()): RideSummary {
        val distance = rides.sumOf { it.distanceM }
        val moving = rides.sumOf { it.movingMs }
        // 同じ値なら新しい方（開始が後）を選ぶ
        val newestFirst = rides.sortedByDescending { it.startedAt }
        val fastest = newestFirst.maxByOrNull { it.maxSpeedMps }?.takeIf { it.maxSpeedMps > 0 }
        val longestD = newestFirst.maxByOrNull { it.distanceM }?.takeIf { it.distanceM > 0 }
        val longestT = newestFirst.maxByOrNull { it.endedAt - it.startedAt }?.takeIf { it.endedAt > it.startedAt }
        val cal = Calendar.getInstance(zone)
        val days = HashSet<Long>()
        val monthly = java.util.TreeMap<String, Double>()
        val weekday = LongArray(7)
        val hour = LongArray(24)
        for (t in rides) {
            cal.timeInMillis = t.startedAt
            days.add(dayKey(cal))
            val key = monthKey(cal)
            monthly[key] = (monthly[key] ?: 0.0) + t.distanceM
            if (t.movingSpans.isEmpty()) {
                spread(t.startedAt, t.endedAt, zone, weekday, hour, days)
            } else {
                for ((a, b) in t.movingSpans) spread(a, b, zone, weekday, hour, days)
            }
        }
        val binsMs = HashMap<Int, Long>()
        val binsM = HashMap<Int, Double>()
        for (t in rides) {
            for ((k, v) in t.speedBinsMs) binsMs[k] = (binsMs[k] ?: 0L) + v
            for ((k, v) in t.speedBinsM) binsM[k] = (binsM[k] ?: 0.0) + v
        }
        val top = max(binsMs.keys.maxOrNull() ?: -1, binsM.keys.maxOrNull() ?: -1)
        val bins = (0..top).map { i -> SpeedBin(i * BIN_KMH, (i + 1) * BIN_KMH, binsMs[i] ?: 0L, binsM[i] ?: 0.0) }
        return RideSummary(
            rideCount = rides.size,
            distanceM = distance,
            movingMs = moving,
            avgSpeedMps = if (moving > 0) distance / (moving / 1000.0) else 0.0,
            maxSpeed = fastest?.let { it.maxSpeedMps to it },
            longestDistance = longestD,
            longestTime = longestT,
            ascentM = rides.sumOf { it.ascentM },
            days = days.size,
            photos = rides.sumOf { it.photos },
            monthlyM = fillMonths(monthly),
            weekdayMs = weekday,
            hourMs = hour,
            histogram = SpeedHistogram(BIN_KMH, bins, bins.sumOf { it.timeMs }, bins.sumOf { it.distanceM }),
            cells = rides.flatMapTo(HashSet()) { it.cells }.size,
        )
    }

    /** 数えた 1 回分の列（[CountedStep]。時刻順）から走行の時間帯を作る（隣り合う分はつなげる）。 */
    fun movingSpans(steps: List<CountedStep>): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        var from = Long.MIN_VALUE
        var to = Long.MIN_VALUE
        for (s in steps) {
            if (s.dtMs <= 0) continue
            val a = s.timeMs - s.dtMs
            if (from != Long.MIN_VALUE && a <= to) {
                to = max(to, s.timeMs)
            } else {
                if (from != Long.MIN_VALUE) out.add(from to to)
                from = a
                to = s.timeMs
            }
        }
        if (from != Long.MIN_VALUE) out.add(from to to)
        return out
    }

    /** 数えた 1 回分を速度の bin（2 km/h ごと）に足す（時間 [ms]・距離 [m]）。 */
    fun speedBins(steps: List<CountedStep>): Pair<Map<Int, Long>, Map<Int, Double>> {
        val ms = HashMap<Int, Long>()
        val m = HashMap<Int, Double>()
        for (s in steps) {
            val i = RideAnalysis.binIndex(s.speedMps * 3.6, BIN_KMH)
            ms[i] = (ms[i] ?: 0L) + s.dtMs
            m[i] = (m[i] ?: 0.0) + s.distM
        }
        return ms to m
    }

    private fun dayKey(cal: Calendar) = cal.get(Calendar.YEAR) * 1000L + cal.get(Calendar.DAY_OF_YEAR)

    private fun monthKey(cal: Calendar) =
        String.format(java.util.Locale.ROOT, "%04d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)

    /** 最初の月から最後の月まで、走らなかった月も 0 で並べる（グラフの間が詰まらないように）。 */
    private fun fillMonths(m: java.util.TreeMap<String, Double>): List<Pair<String, Double>> {
        if (m.isEmpty()) return emptyList()
        fun parse(k: String) = k.substring(0, 4).toInt() * 12 + (k.substring(5, 7).toInt() - 1)
        val a = parse(m.firstKey())
        val b = parse(m.lastKey())
        if (b - a > 240) return m.entries.map { it.key to it.value }
        return (a..b).map { i ->
            val k = String.format(java.util.Locale.ROOT, "%04d-%02d", i / 12, i % 12 + 1)
            k to (m[k] ?: 0.0)
        }
    }

    /** [from]〜[to] を時間の枠（1 時間ごと）に割り振って曜日・時間帯に足す。日をまたいだら走った日も足す。 */
    private fun spread(from: Long, to: Long, zone: TimeZone, weekday: LongArray, hour: LongArray, days: MutableSet<Long>) {
        if (to <= from) return
        val cal = Calendar.getInstance(zone)
        var t = from
        var guard = 0
        while (t < to && guard++ < 24 * 31) {
            cal.timeInMillis = t
            cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            cal.add(Calendar.HOUR_OF_DAY, 1)
            val end = min(cal.timeInMillis, to)
            cal.timeInMillis = t
            val ms = end - t
            weekday[cal.get(Calendar.DAY_OF_WEEK) - 1] += ms
            hour[cal.get(Calendar.HOUR_OF_DAY)] += ms
            days.add(dayKey(cal))
            t = end
        }
    }
}

/**
 * 1 km 四方のマス目（「開拓したマス」）。緯度は 1 km ごとの帯、経度はその帯の中央の緯度で 1 km ごと。
 * マスの番号は (行, 列) を 1 つの Long にまとめたもの。
 */
object GridCells {
    private const val KM = 1000.0

    /** 点の間を補う最大の距離 [m]。これより離れた点の間（GPS の長い欠け・輪行など）は補わない。 */
    const val MAX_FILL_GAP_M = 10_000.0

    fun cellOf(lat: Double, lon: Double): Long {
        val row = floor(lat * Geo.METERS_PER_DEG_LAT / KM).toLong()
        val centerLat = (row + 0.5) * KM / Geo.METERS_PER_DEG_LAT
        val mPerLon = max(1.0, Geo.EARTH_RADIUS_M * Math.PI / 180.0 * cos(Math.toRadians(centerLat)))
        val col = floor(lon * mPerLon / KM).toLong()
        return (row shl 32) xor (col and 0xffffffffL)
    }

    /**
     * 点の列（1 区間）が通ったマス。点と点の間が 1 km より離れていれば、間も 500 m おきに補って数える
     * （[MAX_FILL_GAP_M] 以上離れていれば補わない）。一時停止・跳びの切れ目をまたいで補わないよう、区間ごとに呼ぶ。
     */
    fun cellsOf(points: List<Pair<Double, Double>>, out: MutableSet<Long> = HashSet()): MutableSet<Long> {
        var prev: Pair<Double, Double>? = null
        for (p in points) {
            val q = prev
            if (q != null) {
                val d = Geo.distanceM(q.first, q.second, p.first, p.second)
                if (d > 1000.0 && d < MAX_FILL_GAP_M) {
                    val n = (d / 500.0).toInt()
                    for (i in 1 until n) {
                        val f = i.toDouble() / n
                        out.add(cellOf(q.first + (p.first - q.first) * f, q.second + (p.second - q.second) * f))
                    }
                }
            }
            out.add(cellOf(p.first, p.second))
            prev = p
        }
        return out
    }
}
