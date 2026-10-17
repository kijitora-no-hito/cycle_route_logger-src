package jp.kjtr.cycleroutelogger.stats

import jp.kjtr.cycleroutelogger.data.TrackEntity
import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.geo.GeoBounds
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.geo.Simplify
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

/*
 * 記録の一覧の絞り込み・並べ替え（0.11.0。DESIGN.md §4.11）。純ロジック（単体テスト: TrackFilterTest）。
 * 時期は記録の開始時刻を端末のタイムゾーンで見る。場所は記録ごとに前もって作った [TrackGeo]（外接矩形・始点・終点・間引いた線）で判定する。
 */

/** 季節（開始の月。春 3〜5・夏 6〜8・秋 9〜11・冬 12〜2）。 */
enum class Season { SPRING, SUMMER, AUTUMN, WINTER;
    companion object {
        fun ofMonth(month: Int): Season = when (month) {
            3, 4, 5 -> SPRING
            6, 7, 8 -> SUMMER
            9, 10, 11 -> AUTUMN
            else -> WINTER
        }
    }
}

/** 時間帯（開始の時刻。朝 5〜10 時・昼 10〜16 時・夕方 16〜19 時・夜 19〜5 時）。 */
enum class DayPart { MORNING, DAYTIME, EVENING, NIGHT;
    companion object {
        fun ofHour(h: Int): DayPart = when (h) {
            in 5..9 -> MORNING
            in 10..15 -> DAYTIME
            in 16..18 -> EVENING
            else -> NIGHT
        }
    }
}

/** 記録の出どころ。 */
enum class SourceFilter { ALL, OWN, IMPORTED }

/** 場所の判定: 範囲を通った / そこから出発 / そこで終了。 */
enum class PlaceMatch { PASS, START, END }

/** 範囲の形: 円（中心＋半径）/ 矩形（地図に見えていた範囲）。 */
enum class PlaceShape { CIRCLE, RECT }

/** 数の範囲（両端を含む。null = その側は区切りなし）。 */
@Serializable
data class NumRange(val min: Double? = null, val max: Double? = null) {
    val isOpen: Boolean get() = min == null && max == null
    operator fun contains(v: Double): Boolean = (min == null || v >= min - EPS) && (max == null || v <= max + EPS)

    companion object {
        /** 表示の丸め（小数 1 桁）でちょうど境目の値が落ちないように。 */
        private const val EPS = 1e-9
        /** 両方 null なら null（条件なし）。下限 > 上限なら入れ替える。 */
        fun of(min: Double?, max: Double?): NumRange? {
            if (min == null && max == null) return null
            return if (min != null && max != null && min > max) NumRange(max, min) else NumRange(min, max)
        }
    }
}

/** 場所の範囲。円は [lat]/[lon]/[radiusM]、矩形は [south]〜[north]・[west]〜[east]。[label] は場所名の検索で選んだ名前（無ければ null）。 */
@Serializable
data class PlaceArea(
    val shape: PlaceShape = PlaceShape.CIRCLE,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val radiusM: Double = 0.0,
    val south: Double = 0.0,
    val west: Double = 0.0,
    val north: Double = 0.0,
    val east: Double = 0.0,
    val match: PlaceMatch = PlaceMatch.PASS,
    val label: String? = null,
) {
    /** 範囲の外接矩形（円は緯度経度の幅で。極の近くは経度いっぱい）。 */
    fun bounds(): GeoBounds = when (shape) {
        PlaceShape.RECT -> GeoBounds(south, west, north, east)
        PlaceShape.CIRCLE -> {
            val r = radius()
            val dLat = r / Geo.METERS_PER_DEG_LAT
            val mLon = Geo.metersPerDegLon(lat.coerceIn(-89.9, 89.9))
            val dLon = if (mLon < 1.0) 180.0 else min(180.0, r / mLon)
            GeoBounds(max(-90.0, lat - dLat), lon - dLon, min(90.0, lat + dLat), lon + dLon)
        }
    }

    fun radius(): Double = radiusM.coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)

    /** 点が範囲に入るか。 */
    fun contains(pLat: Double, pLon: Double): Boolean = when (shape) {
        PlaceShape.RECT -> pLat in south..north && pLon in west..east
        PlaceShape.CIRCLE -> {
            val (x, y) = local(pLat, pLon)
            x * x + y * y <= radius() * radius()
        }
    }

    /** 線分 (aLat, aLon)–(bLat, bLon) が範囲にかかるか（端の点が入る・範囲を横切る）。 */
    fun hitsSegment(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Boolean = when (shape) {
        PlaceShape.RECT -> segmentHitsRect(aLat, aLon, bLat, bLon)
        PlaceShape.CIRCLE -> {
            val (ax, ay) = local(aLat, aLon)
            val (bx, by) = local(bLat, bLon)
            segDist2(0.0, 0.0, ax, ay, bx, by) <= radius() * radius()
        }
    }

    /** 中心を原点にした東・北の m（円の判定。中心の緯度で経度を測る正距円筒。半径 100 km までなら誤差は 1% 未満）。 */
    private fun local(pLat: Double, pLon: Double): Pair<Double, Double> {
        val kx = max(1.0, Geo.metersPerDegLon(lat.coerceIn(-89.9, 89.9)))
        return (pLon - lon) * kx to (pLat - lat) * Geo.METERS_PER_DEG_LAT
    }

    /** Liang–Barsky で線分を矩形に切り取れるか（緯度経度のまま）。 */
    private fun segmentHitsRect(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Boolean {
        if (south > north || west > east) return false
        var t0 = 0.0
        var t1 = 1.0
        val dx = bLon - aLon
        val dy = bLat - aLat
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(aLon - west, east - aLon, aLat - south, north - aLat)
        for (i in 0 until 4) {
            if (p[i] == 0.0) {
                if (q[i] < 0) return false
            } else {
                val r = q[i] / p[i]
                if (p[i] < 0) {
                    if (r > t1) return false
                    if (r > t0) t0 = r
                } else {
                    if (r < t0) return false
                    if (r < t1) t1 = r
                }
            }
        }
        return t0 <= t1
    }

    companion object {
        const val MIN_RADIUS_M = 50.0
        const val MAX_RADIUS_M = 200_000.0

        private fun segDist2(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val fx = ax + t * dx - px
            val fy = ay + t * dy - py
            return fx * fx + fy * fy
        }
    }
}

/**
 * 一覧の絞り込みの条件（すべて AND。空 = 条件なし）。SharedPreferences に JSON で保存する（[jp.kjtr.cycleroutelogger.data.TrackBrowser]）。
 * 時期の「年・月」と「期間（開始日〜終了日）」はどちらか一方（画面で片方を選ぶともう片方を外す）。
 */
@Serializable
data class TrackFilter(
    /** 年（開始の日付の年）。 */
    val year: Int? = null,
    /** 月 1〜12（年が無ければ毎年のその月）。 */
    val month: Int? = null,
    /** 期間の開始日・終了日（両端を含む。1970-01-01 からの日数。端末のタイムゾーンの日付で比べる）。 */
    val fromDay: Long? = null,
    val toDay: Long? = null,
    val seasons: Set<Season> = emptySet(),
    /** 曜日（[Calendar.DAY_OF_WEEK]: 1 = 日曜 … 7 = 土曜）。 */
    val weekdays: Set<Int> = emptySet(),
    val dayParts: Set<DayPart> = emptySet(),
    val place: PlaceArea? = null,
    val distanceKm: NumRange? = null,
    val avgKmh: NumRange? = null,
    val maxKmh: NumRange? = null,
    /** 走行時間 [分]。 */
    val movingMin: NumRange? = null,
    val ascentM: NumRange? = null,
    val mode: ModeFilter = ModeFilter.ALL,
    val source: SourceFilter = SourceFilter.ALL,
    /** 取り込みの形式（"fit" など。[source] が IMPORTED のときだけ見る。空 = どれでも）。 */
    val formats: Set<String> = emptySet(),
    /** 写真のある記録だけ。 */
    val withPhotos: Boolean = false,
    /** 名前（とルートのプラン名）に含む文字（空白で区切ると全部を含むもの）。 */
    val name: String = "",
) {
    /** 条件が 1 つでもあるか。 */
    val isActive: Boolean get() = count > 0

    /** 条件の数（［絞り込み］のボタンの数字）。 */
    val count: Int
        get() = listOf(
            year != null || month != null, fromDay != null || toDay != null, seasons.isNotEmpty(), weekdays.isNotEmpty(), dayParts.isNotEmpty(),
            place != null, distanceKm != null, avgKmh != null, maxKmh != null, movingMin != null, ascentM != null,
            mode != ModeFilter.ALL, source != SourceFilter.ALL, withPhotos, name.isNotBlank(),
        ).count { it }
}

/** 並べ替え。 */
enum class TrackSort { NEWEST, OLDEST, DISTANCE, MOVING, AVG_SPEED, ASCENT }

/**
 * 記録 1 本の場所の索引（Room 版 7 の track_geo。[TrackGeoCodec]）。外接矩形は記録した全点から、線は区間ごとに 10 m で間引いたもの
 * （緯度・経度 × 1e6 の整数の並び。[MapTiles.encode] と同じ形）。
 */
class TrackGeo(
    val trackId: Long,
    /** 作ったときの記録の点の数（track.pointCount。変われば作り直す）。 */
    val sourcePoints: Int,
    val bounds: GeoBounds?,
    val start: LatLon?,
    val end: LatLon?,
    val lines: List<IntArray>,
) {
    val linePoints: Int get() = lines.sumOf { it.size / 2 }

    /** [area] に当てはまるか（[PlaceArea.match] で 通った / 出発 / 終了）。 */
    fun matches(area: PlaceArea): Boolean = when (area.match) {
        PlaceMatch.START -> start?.let { area.contains(it.lat, it.lon) } == true
        PlaceMatch.END -> end?.let { area.contains(it.lat, it.lon) } == true
        PlaceMatch.PASS -> passes(area)
    }

    private fun passes(area: PlaceArea): Boolean {
        val b = bounds ?: return false
        val ab = area.bounds()
        if (b.north < ab.south || b.south > ab.north || b.east < ab.west || b.west > ab.east) return false
        for (seg in lines) {
            val n = seg.size / 2
            if (n == 0) continue
            if (n == 1) {
                if (area.contains(seg[0] / 1e6, seg[1] / 1e6)) return true
                continue
            }
            var aLat = seg[0] / 1e6
            var aLon = seg[1] / 1e6
            for (k in 1 until n) {
                val bLat = seg[2 * k] / 1e6
                val bLon = seg[2 * k + 1] / 1e6
                // 線分の外接矩形が範囲の外接矩形にかからなければ飛ばす（ほとんどの線分はここで外れる）
                if (!(max(aLat, bLat) < ab.south || min(aLat, bLat) > ab.north || max(aLon, bLon) < ab.west || min(aLon, bLon) > ab.east)) {
                    if (area.hitsSegment(aLat, aLon, bLat, bLon)) return true
                }
                aLat = bLat
                aLon = bLon
            }
        }
        return false
    }

    companion object {
        /** 索引の作り方の版（間引き方などを変えたら上げる。古い版の行は作り直す）。 */
        const val VERSION = 1
        /** 線の間引きの許容誤差 [m]。 */
        const val TOLERANCE_M = 10.0

        /** 区間ごとの点（記録の順）から作る。 */
        fun build(trackId: Long, sourcePoints: Int, segments: List<List<LatLon>>): TrackGeo {
            var s = Double.POSITIVE_INFINITY
            var n = Double.NEGATIVE_INFINITY
            var w = Double.POSITIVE_INFINITY
            var e = Double.NEGATIVE_INFINITY
            for (seg in segments) for (p in seg) {
                if (p.lat < s) s = p.lat
                if (p.lat > n) n = p.lat
                if (p.lon < w) w = p.lon
                if (p.lon > e) e = p.lon
            }
            val nonEmpty = segments.filter { it.isNotEmpty() }
            val bounds = if (nonEmpty.isEmpty()) null else GeoBounds(s, w, n, e)
            val lines = nonEmpty.map { MapTiles.encode(Simplify.douglasPeucker(it, TOLERANCE_M)) }
            return TrackGeo(trackId, sourcePoints, bounds, nonEmpty.firstOrNull()?.first(), nonEmpty.lastOrNull()?.last(), lines)
        }
    }
}

/** [TrackGeo.lines] を BLOB にする（リトルエンディアンの int: 区間の数、区間ごとに int の数と並び）。 */
object TrackGeoCodec {
    fun encode(lines: List<IntArray>): ByteArray {
        val size = 4 + lines.sumOf { 4 + it.size * 4 }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(lines.size)
        for (l in lines) {
            buf.putInt(l.size)
            for (v in l) buf.putInt(v)
        }
        return buf.array()
    }

    /** 壊れた・短い BLOB は読めたところまで（例外にしない）。 */
    fun decode(bytes: ByteArray): List<IntArray> {
        if (bytes.size < 4) return emptyList()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = buf.getInt()
        if (n < 0 || n > 100_000) return emptyList()
        val out = ArrayList<IntArray>(n)
        repeat(n) {
            if (buf.remaining() < 4) return out
            val len = buf.getInt()
            if (len < 0 || len.toLong() * 4 > buf.remaining()) return out
            out.add(IntArray(len) { buf.getInt() })
        }
        return out
    }
}

/** 絞り込みの結果。 */
data class FilterOutcome(
    val items: List<TrackEntity>,
    /** 場所の条件があるのに索引がまだ無かった記録の数（作っている途中。その記録は外している）。 */
    val geoPending: Int,
)

object TrackFilters {
    private const val DAY_MS = 86_400_000L

    /** 端末のタイムゾーンの日付（1970-01-01 からの日数）。 */
    fun localDay(t: Long, zone: TimeZone): Long = Math.floorDiv(t + zone.getOffset(t), DAY_MS)

    /** 名前の検索の正規化（全角・半角・大文字小文字をそろえる）。 */
    fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    fun tokens(q: String): List<String> = normalize(q).split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun avgKmh(t: TrackEntity): Double = if (t.movingMs > 0) t.distanceM / (t.movingMs / 1000.0) * 3.6 else 0.0

    /**
     * 絞り込んで並べる。[photos] = 記録 id → 写真の数、[geo] = 記録 id → 場所の索引（場所の条件があるときだけ見る）。
     */
    fun apply(
        tracks: List<TrackEntity>,
        f: TrackFilter,
        sort: TrackSort,
        photos: Map<Long, Int> = emptyMap(),
        geo: Map<Long, TrackGeo> = emptyMap(),
        zone: TimeZone = TimeZone.getDefault(),
    ): FilterOutcome {
        val cal = Calendar.getInstance(zone)
        val words = tokens(f.name)
        var pending = 0
        val out = ArrayList<TrackEntity>(tracks.size)
        for (t in tracks) {
            if (!matchesBasic(t, f, cal, zone, words, photos)) continue
            val area = f.place
            if (area != null) {
                val g = geo[t.id]
                if (g == null) {
                    pending++
                    continue
                }
                if (!g.matches(area)) continue
            }
            out.add(t)
        }
        return FilterOutcome(sorted(out, sort), pending)
    }

    /** 場所以外の条件。 */
    fun matchesBasic(
        t: TrackEntity,
        f: TrackFilter,
        cal: Calendar,
        zone: TimeZone,
        words: List<String> = tokens(f.name),
        photos: Map<Long, Int> = emptyMap(),
    ): Boolean {
        cal.timeInMillis = t.startedAt
        if (f.year != null && cal.get(Calendar.YEAR) != f.year) return false
        if (f.month != null && cal.get(Calendar.MONTH) + 1 != f.month) return false
        if (f.fromDay != null || f.toDay != null) {
            val d = localDay(t.startedAt, zone)
            if (f.fromDay != null && d < f.fromDay) return false
            if (f.toDay != null && d > f.toDay) return false
        }
        if (f.seasons.isNotEmpty() && Season.ofMonth(cal.get(Calendar.MONTH) + 1) !in f.seasons) return false
        if (f.weekdays.isNotEmpty() && cal.get(Calendar.DAY_OF_WEEK) !in f.weekdays) return false
        if (f.dayParts.isNotEmpty() && DayPart.ofHour(cal.get(Calendar.HOUR_OF_DAY)) !in f.dayParts) return false
        f.distanceKm?.let { if (t.distanceM / 1000.0 !in it) return false }
        f.avgKmh?.let { if (avgKmh(t) !in it) return false }
        f.maxKmh?.let { if (t.maxSpeedMps * 3.6 !in it) return false }
        f.movingMin?.let { if (t.movingMs / 60_000.0 !in it) return false }
        f.ascentM?.let { if (t.ascentM !in it) return false }
        when (f.mode) {
            ModeFilter.ALL -> {}
            ModeFilter.FREE -> if (t.mode == TrackEntity.MODE_ROUTE) return false
            ModeFilter.ROUTE -> if (t.mode != TrackEntity.MODE_ROUTE) return false
        }
        when (f.source) {
            SourceFilter.ALL -> {}
            SourceFilter.OWN -> if (t.imported) return false
            SourceFilter.IMPORTED -> {
                if (!t.imported) return false
                if (f.formats.isNotEmpty()) {
                    val have = t.importFormat.orEmpty().split(',').map { it.trim() }.toSet()
                    if (f.formats.none { it in have }) return false
                }
            }
        }
        if (f.withPhotos && (photos[t.id] ?: 0) <= 0) return false
        if (words.isNotEmpty()) {
            val hay = normalize(t.name + "\n" + (t.planName ?: ""))
            if (words.any { it !in hay }) return false
        }
        return true
    }

    /** 並べ替え（同じ値なら新しい順、さらに id の大きい順）。 */
    fun sorted(list: List<TrackEntity>, sort: TrackSort): List<TrackEntity> {
        val newest = compareByDescending<TrackEntity> { it.startedAt }.thenByDescending { it.id }
        val c: Comparator<TrackEntity> = when (sort) {
            TrackSort.NEWEST -> newest
            TrackSort.OLDEST -> compareBy<TrackEntity> { it.startedAt }.thenBy { it.id }
            TrackSort.DISTANCE -> compareByDescending<TrackEntity> { it.distanceM }.then(newest)
            TrackSort.MOVING -> compareByDescending<TrackEntity> { it.movingMs }.then(newest)
            TrackSort.AVG_SPEED -> compareByDescending<TrackEntity> { avgKmh(it) }.then(newest)
            TrackSort.ASCENT -> compareByDescending<TrackEntity> { it.ascentM }.then(newest)
        }
        return list.sortedWith(c)
    }

    /** 期間の日付（DatePicker の UTC 0 時の ms）→ 日数。 */
    fun dayOfPickerMs(ms: Long): Long = Math.floorDiv(ms, DAY_MS)

    /** 日数 → DatePicker の ms（UTC 0 時）。 */
    fun pickerMsOfDay(day: Long): Long = day * DAY_MS

    /** スライダーの上端に使う値（データの最大を切りのよい数に切り上げる。[step] の倍数、最低 [minTop]）。 */
    fun niceTop(maxValue: Double, step: Double, minTop: Double): Double {
        if (!maxValue.isFinite() || maxValue <= 0) return minTop
        return max(minTop, Math.ceil(maxValue / step) * step)
    }
}
