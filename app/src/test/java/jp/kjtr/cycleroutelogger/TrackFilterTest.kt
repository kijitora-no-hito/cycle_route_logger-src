package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.data.PointLL
import jp.kjtr.cycleroutelogger.data.TrackEntity
import jp.kjtr.cycleroutelogger.data.TrackGeoIndex
import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.stats.DayPart
import jp.kjtr.cycleroutelogger.stats.ModeFilter
import jp.kjtr.cycleroutelogger.stats.NumRange
import jp.kjtr.cycleroutelogger.stats.PlaceArea
import jp.kjtr.cycleroutelogger.stats.PlaceMatch
import jp.kjtr.cycleroutelogger.stats.PlaceShape
import jp.kjtr.cycleroutelogger.stats.Season
import jp.kjtr.cycleroutelogger.stats.SourceFilter
import jp.kjtr.cycleroutelogger.stats.TrackFilter
import jp.kjtr.cycleroutelogger.stats.TrackFilters
import jp.kjtr.cycleroutelogger.stats.TrackGeo
import jp.kjtr.cycleroutelogger.stats.TrackGeoCodec
import jp.kjtr.cycleroutelogger.stats.TrackSort
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Random
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sin

/** 記録の一覧の絞り込み・並べ替え・場所の索引（0.11.0。DESIGN.md §4.11）。 */
class TrackFilterTest {
    private val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
    private val utc = TimeZone.getTimeZone("UTC")
    private val ny = TimeZone.getTimeZone("America/New_York")

    private fun at(s: String, zone: TimeZone): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).apply { timeZone = zone }.parse(s)!!.time

    private fun track(
        id: Long, start: Long, km: Double = 20.0, movingMin: Double = 60.0, maxKmh: Double = 40.0, ascent: Double = 100.0,
        name: String = "走行 $id", mode: String = TrackEntity.MODE_FREE, planName: String? = null, importFormat: String? = null,
    ) = TrackEntity(
        id = id, name = name, startedAt = start, endedAt = start + (movingMin * 60_000).toLong(), status = TrackEntity.STATUS_FINISHED,
        mode = mode, planName = planName, autoPause = true, distanceM = km * 1000.0, movingMs = (movingMin * 60_000).toLong(),
        maxSpeedMps = maxKmh / 3.6, ascentM = ascent, pointCount = 10, importFormat = importFormat,
    )

    private fun ids(list: List<TrackEntity>, f: TrackFilter, zone: TimeZone = tokyo, photos: Map<Long, Int> = emptyMap(), geo: Map<Long, TrackGeo> = emptyMap()) =
        TrackFilters.apply(list, f, TrackSort.OLDEST, photos, geo, zone).items.map { it.id }.sorted()

    private fun day(y: Int, m: Int, d: Int): Long = TrackFilters.dayOfPickerMs(at(String.format(Locale.ROOT, "%04d-%02d-%02d 00:00:00.000", y, m, d), utc))

    // ---- 時期 ----

    @Test
    fun year_usesDeviceTimeZone() {
        // 2024-12-31 15:00 UTC = 東京では 2025-01-01 0:00
        val t = listOf(track(1, at("2024-12-31 15:00:00.000", utc)))
        assertEquals(listOf(1L), ids(t, TrackFilter(year = 2025), tokyo))
        assertEquals(emptyList<Long>(), ids(t, TrackFilter(year = 2024), tokyo))
        assertEquals(listOf(1L), ids(t, TrackFilter(year = 2024), utc))
        assertEquals(listOf(1L), ids(t, TrackFilter(year = 2025, month = 1), tokyo))
        assertEquals(listOf(1L), ids(t, TrackFilter(year = 2024, month = 12), utc))
    }

    @Test
    fun monthWithoutYear_isEveryYear() {
        val t = listOf(
            track(1, at("2020-08-03 07:00:00.000", tokyo)), track(2, at("2023-08-31 23:59:59.999", tokyo)),
            track(3, at("2023-09-01 00:00:00.000", tokyo)), track(4, at("2023-07-31 23:59:59.999", tokyo)),
        )
        assertEquals(listOf(1L, 2L), ids(t, TrackFilter(month = 8)))
    }

    @Test
    fun dayRange_includesBothEnds_inLocalDays() {
        val from = day(2025, 1, 1)
        val to = day(2025, 1, 3)
        val t = listOf(
            track(1, at("2024-12-31 23:59:59.999", tokyo)), // 前の日
            track(2, at("2025-01-01 00:00:00.000", tokyo)), // 開始日の 0 時
            track(3, at("2025-01-03 23:59:59.999", tokyo)), // 終了日の最後
            track(4, at("2025-01-04 00:00:00.000", tokyo)), // 次の日
        )
        assertEquals(listOf(2L, 3L), ids(t, TrackFilter(fromDay = from, toDay = to)))
        // 片側だけ
        assertEquals(listOf(2L, 3L, 4L), ids(t, TrackFilter(fromDay = from)))
        assertEquals(listOf(1L, 2L, 3L), ids(t, TrackFilter(toDay = to)))
        // 1 日だけ
        assertEquals(listOf(2L), ids(t, TrackFilter(fromDay = from, toDay = from)))
        // 東京の 1/1 0:00 は UTC では 12/31。同じ記録でもタイムゾーンで日付が変わる
        assertEquals(listOf(1L, 2L), ids(t, TrackFilter(fromDay = day(2024, 12, 31), toDay = day(2024, 12, 31)), utc))
    }

    @Test
    fun dayRange_acrossDaylightSaving() {
        // ニューヨークの夏時間の始まり（2024-03-10 2:00 EST → 3:00 EDT）
        val t = listOf(
            track(1, at("2024-03-10 04:59:59.999", utc)), // 3/9 23:59 EST
            track(2, at("2024-03-10 05:00:00.000", utc)), // 3/10 0:00 EST
            track(3, at("2024-03-11 03:59:59.999", utc)), // 3/10 23:59 EDT
            track(4, at("2024-03-11 04:00:00.000", utc)), // 3/11 0:00 EDT
        )
        assertEquals(listOf(2L, 3L), ids(t, TrackFilter(fromDay = day(2024, 3, 10), toDay = day(2024, 3, 10)), ny))
    }

    @Test
    fun seasons_weekdays_dayParts() {
        val t = listOf(
            track(1, at("2025-12-01 04:59:00.000", tokyo)), // 冬・月曜・夜（5 時前）
            track(2, at("2025-01-04 05:00:00.000", tokyo)), // 冬・土曜・朝
            track(3, at("2025-02-28 16:00:00.000", tokyo)), // 冬・金曜・夕方
            track(4, at("2025-03-01 23:30:00.000", tokyo)), // 春・土曜・夜
            track(5, at("2025-08-10 10:00:00.000", tokyo)), // 夏・日曜・昼
            track(6, at("2025-11-30 09:59:59.999", tokyo)), // 秋・日曜・朝
        )
        assertEquals(listOf(1L, 2L, 3L), ids(t, TrackFilter(seasons = setOf(Season.WINTER))))
        assertEquals(listOf(4L, 5L), ids(t, TrackFilter(seasons = setOf(Season.SPRING, Season.SUMMER))))
        assertEquals(listOf(2L, 4L, 5L, 6L), ids(t, TrackFilter(weekdays = setOf(1, 7))))
        assertEquals(listOf(1L, 4L), ids(t, TrackFilter(dayParts = setOf(DayPart.NIGHT))))
        assertEquals(listOf(2L, 6L), ids(t, TrackFilter(dayParts = setOf(DayPart.MORNING))))
        assertEquals(listOf(4L), ids(t, TrackFilter(dayParts = setOf(DayPart.NIGHT), weekdays = setOf(7))))
        assertEquals(DayPart.NIGHT, DayPart.ofHour(4))
        assertEquals(DayPart.MORNING, DayPart.ofHour(5))
        assertEquals(DayPart.DAYTIME, DayPart.ofHour(15))
        assertEquals(DayPart.EVENING, DayPart.ofHour(18))
        assertEquals(DayPart.NIGHT, DayPart.ofHour(19))
    }

    // ---- 数の範囲 ----

    @Test
    fun numericRanges_inclusiveAndOpenEnds() {
        val s = at("2025-05-01 08:00:00.000", tokyo)
        val t = listOf(
            track(1, s, km = 19.999), track(2, s + 1, km = 20.0), track(3, s + 2, km = 50.0), track(4, s + 3, km = 50.001),
        )
        assertEquals(listOf(2L, 3L), ids(t, TrackFilter(distanceKm = NumRange(20.0, 50.0))))
        assertEquals(listOf(2L, 3L, 4L), ids(t, TrackFilter(distanceKm = NumRange(min = 20.0))))
        assertEquals(listOf(1L, 2L, 3L), ids(t, TrackFilter(distanceKm = NumRange(max = 50.0))))
        // 下限 > 上限は入れ替える（NumRange.of）。両方 null は条件なし
        assertEquals(NumRange(20.0, 50.0), NumRange.of(50.0, 20.0))
        assertNull(NumRange.of(null, null))
    }

    @Test
    fun avgMaxMovingAscent() {
        val s = at("2025-05-01 08:00:00.000", tokyo)
        val t = listOf(
            track(1, s, km = 20.0, movingMin = 60.0, maxKmh = 35.0, ascent = 0.0),      // 平均 20 km/h
            track(2, s + 1, km = 30.0, movingMin = 60.0, maxKmh = 55.0, ascent = 850.0), // 30 km/h
            track(3, s + 2, km = 0.0, movingMin = 0.0, maxKmh = 0.0, ascent = 0.0),     // 走行時間 0 → 平均 0
            track(4, s + 3, km = 100.0, movingMin = 300.0, maxKmh = 60.0, ascent = 2000.0), // 20 km/h
        )
        assertEquals(listOf(1L, 4L), ids(t, TrackFilter(avgKmh = NumRange(20.0, 25.0))))
        assertEquals(listOf(1L, 2L, 4L), ids(t, TrackFilter(avgKmh = NumRange(min = 0.1))))
        assertEquals(listOf(3L), ids(t, TrackFilter(avgKmh = NumRange(max = 0.0))))
        assertEquals(listOf(2L, 4L), ids(t, TrackFilter(maxKmh = NumRange(min = 50.0))))
        assertEquals(listOf(1L, 2L), ids(t, TrackFilter(movingMin = NumRange(1.0, 120.0))))
        assertEquals(listOf(2L, 4L), ids(t, TrackFilter(ascentM = NumRange(min = 500.0))))
        // 極端な値（上限がとても大きい・0 から 0）
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(t, TrackFilter(distanceKm = NumRange(0.0, 1e12))))
        assertEquals(listOf(3L), ids(t, TrackFilter(distanceKm = NumRange(0.0, 0.0))))
        // 全部の条件を重ねる
        assertEquals(listOf(2L), ids(t, TrackFilter(avgKmh = NumRange(min = 25.0), maxKmh = NumRange(max = 55.0), ascentM = NumRange(800.0, 900.0))))
    }

    // ---- 種類・名前 ----

    @Test
    fun modeSourceFormatsPhotos() {
        val s = at("2025-05-01 08:00:00.000", tokyo)
        val t = listOf(
            track(1, s), track(2, s + 1, mode = TrackEntity.MODE_ROUTE, planName = "房総"),
            track(3, s + 2, importFormat = "fit"), track(4, s + 3, importFormat = "fit,gpx"), track(5, s + 4, importFormat = "kml"),
        )
        assertEquals(listOf(1L, 3L, 4L, 5L), ids(t, TrackFilter(mode = ModeFilter.FREE)))
        assertEquals(listOf(2L), ids(t, TrackFilter(mode = ModeFilter.ROUTE)))
        assertEquals(listOf(1L, 2L), ids(t, TrackFilter(source = SourceFilter.OWN)))
        assertEquals(listOf(3L, 4L, 5L), ids(t, TrackFilter(source = SourceFilter.IMPORTED)))
        assertEquals(listOf(4L), ids(t, TrackFilter(source = SourceFilter.IMPORTED, formats = setOf("gpx"))))
        assertEquals(listOf(3L, 4L, 5L), ids(t, TrackFilter(source = SourceFilter.IMPORTED, formats = setOf("fit", "kml"))))
        // 形式は取り込みを選んだときだけ見る
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(t, TrackFilter(formats = setOf("gpx"))))
        assertEquals(listOf(2L, 5L), ids(t, TrackFilter(withPhotos = true), photos = mapOf(2L to 3, 5L to 1, 1L to 0)))
    }

    @Test
    fun nameSearch_normalizesWidthAndCase_andAllWords() {
        val s = at("2025-05-01 08:00:00.000", tokyo)
        val t = listOf(
            track(1, s, name = "2013-05-02 Morning Ride"), track(2, s + 1, name = "2014-06-01 荒川 ロング"),
            track(3, s + 2, name = "2015-01-01 ｱﾗｶﾜ 朝練"), track(4, s + 3, name = "2026-10-11 10:42", mode = TrackEntity.MODE_ROUTE, planName = "荒川サイクリングロード"),
        )
        assertEquals(listOf(1L), ids(t, TrackFilter(name = "ＲＩＤＥ")))
        assertEquals(listOf(1L), ids(t, TrackFilter(name = "morning ride")))
        assertEquals(listOf(3L), ids(t, TrackFilter(name = "アラカワ")))
        assertEquals(listOf(2L, 4L), ids(t, TrackFilter(name = "荒川")))
        assertEquals(listOf(2L), ids(t, TrackFilter(name = "荒川　ロング"))) // 全角空白でも区切る
        assertEquals(emptyList<Long>(), ids(t, TrackFilter(name = "荒川 朝練")))
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(t, TrackFilter(name = "   ")))
        assertFalse(TrackFilter(name = "  ").isActive)
        assertTrue(TrackFilter(name = "a").isActive)
        assertEquals(3, TrackFilter(year = 2024, month = 3, place = PlaceArea(), withPhotos = true).count)
    }

    // ---- 並べ替え ----

    @Test
    fun sorting() {
        val s = at("2025-05-01 08:00:00.000", tokyo)
        val t = listOf(
            track(1, s, km = 10.0, movingMin = 60.0, ascent = 300.0),
            track(2, s + 3_600_000, km = 40.0, movingMin = 90.0, ascent = 100.0),
            track(3, s + 7_200_000, km = 40.0, movingMin = 200.0, ascent = 100.0),
            track(4, s - 3_600_000, km = 5.0, movingMin = 10.0, ascent = 0.0),
        )
        fun order(sort: TrackSort) = TrackFilters.sorted(t, sort).map { it.id }
        assertEquals(listOf(3L, 2L, 1L, 4L), order(TrackSort.NEWEST))
        assertEquals(listOf(4L, 1L, 2L, 3L), order(TrackSort.OLDEST))
        assertEquals(listOf(3L, 2L, 1L, 4L), order(TrackSort.DISTANCE)) // 同じ距離は新しい方
        assertEquals(listOf(3L, 2L, 1L, 4L), order(TrackSort.MOVING))
        // 平均: 2 = 26.7, 4 = 30, 3 = 12, 1 = 10
        assertEquals(listOf(4L, 2L, 3L, 1L), order(TrackSort.AVG_SPEED))
        assertEquals(listOf(1L, 3L, 2L, 4L), order(TrackSort.ASCENT))
        // 絞り込みと一緒に
        assertEquals(listOf(3L, 2L), TrackFilters.apply(t, TrackFilter(distanceKm = NumRange(min = 30.0)), TrackSort.NEWEST, zone = tokyo).items.map { it.id })
    }

    // ---- 場所 ----

    /** 東京駅の近くの東西の直線（中心から北へ dyM ずらす）。 */
    private fun lineGeo(id: Long, dyM: Double, fromXm: Double = -3000.0, toXm: Double = 3000.0, n: Int = 61): TrackGeo {
        val lat0 = 35.681
        val lon0 = 139.767
        val kx = Geo.metersPerDegLon(lat0)
        val pts = (0 until n).map { i ->
            val x = fromXm + (toXm - fromXm) * i / (n - 1)
            LatLon(lat0 + dyM / Geo.METERS_PER_DEG_LAT, lon0 + x / kx)
        }
        return TrackGeo.build(id, n, listOf(pts))
    }

    @Test
    fun circle_passStartEnd() {
        val c = PlaceArea(PlaceShape.CIRCLE, lat = 35.681, lon = 139.767, radiusM = 500.0)
        assertTrue(lineGeo(1, 0.0).matches(c))
        assertTrue(lineGeo(2, 480.0).matches(c))
        assertFalse(lineGeo(3, 520.0).matches(c))
        // 頂点が円の中に無くても、線分が円を横切れば「通った」（直線は 2 点に間引かれる）
        val sparse = lineGeo(4, 100.0, n = 2)
        assertEquals(2, sparse.linePoints)
        assertTrue(sparse.matches(c))
        // 出発・終了は最初・最後の点
        val fromCenter = lineGeo(5, 0.0, fromXm = 0.0, toXm = 5000.0)
        assertTrue(fromCenter.matches(c.copy(match = PlaceMatch.START)))
        assertFalse(fromCenter.matches(c.copy(match = PlaceMatch.END)))
        val toCenter = lineGeo(6, 0.0, fromXm = -5000.0, toXm = 100.0)
        assertFalse(toCenter.matches(c.copy(match = PlaceMatch.START)))
        assertTrue(toCenter.matches(c.copy(match = PlaceMatch.END)))
        // 通っただけ（出発・終了は外）
        val through = lineGeo(7, 0.0)
        assertFalse(through.matches(c.copy(match = PlaceMatch.START)))
        assertFalse(through.matches(c.copy(match = PlaceMatch.END)))
    }

    @Test
    fun rect_crossingAndInvalid() {
        val lat0 = 35.681
        val r = PlaceArea(PlaceShape.RECT, south = lat0 - 0.001, west = 139.766, north = lat0 + 0.001, east = 139.768)
        assertTrue(lineGeo(1, 0.0, n = 2).matches(r)) // 頂点は外、線分が横切る
        assertFalse(lineGeo(2, 300.0).matches(r))
        // 南北が逆の矩形は何にも当たらない
        assertFalse(lineGeo(3, 0.0).matches(r.copy(south = r.north, north = r.south)))
        // 斜めに角だけかすめる線
        val g = TrackGeo.build(4, 2, listOf(listOf(LatLon(lat0 + 0.0005, 139.7655), LatLon(lat0 + 0.0012, 139.7665))))
        assertTrue(g.matches(r))
        val miss = TrackGeo.build(5, 2, listOf(listOf(LatLon(lat0 + 0.0011, 139.7655), LatLon(lat0 + 0.0021, 139.7665))))
        assertFalse(miss.matches(r))
    }

    @Test
    fun extremeAreas() {
        // 半径は 50 m 〜 200 km に丸める
        assertEquals(PlaceArea.MIN_RADIUS_M, PlaceArea(radiusM = 0.0).radius(), 0.0)
        assertEquals(PlaceArea.MAX_RADIUS_M, PlaceArea(radiusM = 1e12).radius(), 0.0)
        val g = lineGeo(1, 0.0)
        assertTrue(g.matches(PlaceArea(PlaceShape.CIRCLE, lat = 36.5, lon = 140.0, radiusM = 1e12)))
        assertFalse(g.matches(PlaceArea(PlaceShape.CIRCLE, lat = 38.0, lon = 140.0, radiusM = 1e12)))
        // 極の近く・経度の端でも例外にならない
        val polar = PlaceArea(PlaceShape.CIRCLE, lat = 89.999, lon = 0.0, radiusM = 5000.0)
        val b = polar.bounds()
        assertTrue(b.north <= 90.0 && b.south < b.north)
        assertTrue(TrackGeo.build(2, 2, listOf(listOf(LatLon(89.9995, -170.0), LatLon(89.9995, 170.0)))).matches(polar))
        val edge = TrackGeo.build(3, 2, listOf(listOf(LatLon(0.0, 179.99), LatLon(0.0, 179.999))))
        assertTrue(edge.matches(PlaceArea(PlaceShape.CIRCLE, lat = 0.0, lon = 179.995, radiusM = 1000.0)))
        // 点が 1 つだけの区間・点の無い記録
        val one = TrackGeo.build(4, 1, listOf(listOf(LatLon(35.681, 139.767))))
        assertTrue(one.matches(PlaceArea(lat = 35.681, lon = 139.767, radiusM = 100.0)))
        val empty = TrackGeo.build(5, 0, emptyList())
        assertNull(empty.bounds)
        assertFalse(empty.matches(PlaceArea(lat = 35.681, lon = 139.767, radiusM = 100.0)))
        assertFalse(empty.matches(PlaceArea(lat = 35.681, lon = 139.767, radiusM = 100.0, match = PlaceMatch.START)))
    }

    /** ランダムな走行（区間 1〜3、ばらつく速さと向き）。 */
    private fun randomRide(rng: Random, lat0: Double, lon0: Double, n: Int): List<List<LatLon>> {
        val segs = ArrayList<List<LatLon>>()
        var lat = lat0
        var lon = lon0
        var hd = rng.nextDouble() * 360
        val k = 1 + rng.nextInt(3)
        repeat(k) {
            val seg = ArrayList<LatLon>()
            repeat(n / k) {
                seg.add(LatLon(lat, lon))
                hd += rng.nextGaussian() * 12
                val v = 15 + rng.nextDouble() * 20
                lat += v * cos(Math.toRadians(hd)) / Geo.METERS_PER_DEG_LAT
                lon += v * sin(Math.toRadians(hd)) / Geo.metersPerDegLon(lat)
            }
            segs.add(seg)
            // 区間の切れ目で少し飛ぶ（つながない）
            lat += 0.01
        }
        return segs
    }

    /** 元の全点の線分で測った、円の中心からいちばん近い距離 [m]（総当たり）。 */
    private fun minDistance(segs: List<List<LatLon>>, cLat: Double, cLon: Double): Double {
        val kx = Geo.metersPerDegLon(cLat)
        var best = Double.MAX_VALUE
        for (s in segs) {
            for (i in s.indices) {
                val ax = (s[i].lon - cLon) * kx
                val ay = (s[i].lat - cLat) * Geo.METERS_PER_DEG_LAT
                if (s.size == 1) { best = minOf(best, Math.hypot(ax, ay)); continue }
                if (i == 0) continue
                val bx = (s[i - 1].lon - cLon) * kx
                val by = (s[i - 1].lat - cLat) * Geo.METERS_PER_DEG_LAT
                val dx = ax - bx
                val dy = ay - by
                val l2 = dx * dx + dy * dy
                val t = if (l2 == 0.0) 0.0 else ((-bx * dx - by * dy) / l2).coerceIn(0.0, 1.0)
                best = minOf(best, Math.hypot(bx + t * dx, by + t * dy))
            }
        }
        return best
    }

    @Test
    fun indexAgreesWithBruteForce_exceptWithinSimplifyTolerance() {
        val rng = Random(7)
        var hits = 0
        var checks = 0
        repeat(150) { id ->
            val segs = randomRide(rng, 35.5 + rng.nextDouble() * 0.4, 139.4 + rng.nextDouble() * 0.6, 400 + rng.nextInt(800))
            val geo = TrackGeo.build(id.toLong(), segs.sumOf { it.size }, segs)
            assertTrue(geo.linePoints <= segs.sumOf { it.size })
            // 外接矩形は全点を含む
            val b = geo.bounds!!
            assertTrue(segs.all { s -> s.all { it.lat in b.south..b.north && it.lon in b.west..b.east } })
            repeat(20) {
                // 軌跡の近く（ときどき遠く）に円を置く
                val anchor = segs[rng.nextInt(segs.size)].let { it[rng.nextInt(it.size)] }
                val cLat = anchor.lat + rng.nextGaussian() * 0.01
                val cLon = anchor.lon + rng.nextGaussian() * 0.01
                val r = 100.0 + rng.nextDouble() * 3000.0
                val area = PlaceArea(PlaceShape.CIRCLE, lat = cLat, lon = cLon, radiusM = r)
                val d = minDistance(segs, cLat, cLon)
                val got = geo.matches(area)
                checks++
                if (got) hits++
                // 間引き（10 m）で境目の ±15 m だけはどちらもありうる。それ以外は総当たりと同じ
                if (d < r - 15.0) assertTrue("id=$id d=$d r=$r", got)
                if (d > r + 15.0) assertFalse("id=$id d=$d r=$r", got)
            }
        }
        assertTrue("hits=$hits / $checks", hits in 300 until checks - 300)
    }

    @Test
    fun placeFilter_skipsTracksWithoutIndex_andCountsThem() {
        val s = at("2025-05-01 08:00:00.000", tokyo)
        val t = listOf(track(1, s), track(2, s + 1), track(3, s + 2))
        val c = PlaceArea(PlaceShape.CIRCLE, lat = 35.681, lon = 139.767, radiusM = 500.0)
        val geo = mapOf(1L to lineGeo(1, 0.0), 2L to lineGeo(2, 3000.0))
        val out = TrackFilters.apply(t, TrackFilter(place = c), TrackSort.NEWEST, geo = geo, zone = tokyo)
        assertEquals(listOf(1L), out.items.map { it.id })
        assertEquals(1, out.geoPending)
        // 場所の条件が無ければ索引は見ない
        assertEquals(0, TrackFilters.apply(t, TrackFilter(), TrackSort.NEWEST, zone = tokyo).geoPending)
    }

    // ---- 索引の保存・作り直し ----

    @Test
    fun codecRoundTrip_andBrokenBlob() {
        val lines = listOf(intArrayOf(35_681_000, 139_767_000, 35_682_000, 139_768_000), intArrayOf(), intArrayOf(-33_000_000, -70_000_000))
        val back = TrackGeoCodec.decode(TrackGeoCodec.encode(lines))
        assertEquals(3, back.size)
        for (i in lines.indices) assertArrayEquals(lines[i], back[i])
        assertTrue(TrackGeoCodec.decode(ByteArray(0)).isEmpty())
        assertTrue(TrackGeoCodec.decode(byteArrayOf(1, 0, 0, 0, 100, 0, 0, 0, 1)).isEmpty()) // 長さが足りない
        assertTrue(TrackGeoCodec.decode(byteArrayOf(-1, -1, -1, -1)).isEmpty())
    }

    @Test
    fun buildFromPoints_splitsSegments_andKeepsEnds() {
        val pts = listOf(
            PointLL(0, 35.0, 139.0), PointLL(0, 35.001, 139.0), PointLL(0, 35.002, 139.0),
            PointLL(2, 35.1, 139.1), PointLL(2, 35.101, 139.1),
        )
        val g = TrackGeoIndex.buildFromPoints(9, 5, pts)
        assertEquals(2, g.lines.size)
        // 直線の真ん中の点は間引かれる
        assertEquals(2, g.lines[0].size / 2)
        assertEquals(LatLon(35.0, 139.0), g.start)
        assertEquals(LatLon(35.101, 139.1), g.end)
        assertEquals(35.0, g.bounds!!.south, 0.0)
        assertEquals(35.101, g.bounds!!.north, 0.0)
        // 区間の間（35.002 → 35.1）はつながない: その間の円には当たらない
        assertFalse(g.matches(PlaceArea(lat = 35.05, lon = 139.05, radiusM = 1000.0)))
        // 保存の形にして戻しても同じ
        val back = TrackGeoIndex.toGeo(TrackGeoIndex.toEntity(g))
        assertEquals(g.start, back.start)
        assertEquals(g.end, back.end)
        assertEquals(g.bounds, back.bounds)
        assertEquals(g.sourcePoints, back.sourcePoints)
        for (i in g.lines.indices) assertArrayEquals(g.lines[i], back.lines[i])
        assertNull(TrackGeoIndex.toGeo(TrackGeoIndex.toEntity(TrackGeoIndex.buildFromPoints(1, 0, emptyList()))).bounds)
    }

    @Test
    fun staleOf_buildsMissingAndChangedOnly() {
        val tracks = listOf(1L to 100, 2L to 200, 3L to 300)
        // 1 は同じ、2 は点が増えた（取り込みで区間を足した）、3 は無い
        assertEquals(listOf(2L to 200, 3L to 300), TrackGeoIndex.staleOf(tracks, mapOf(1L to 100, 2L to 150, 9L to 1)))
        assertEquals(tracks, TrackGeoIndex.staleOf(tracks, emptyMap()))
        assertTrue(TrackGeoIndex.staleOf(tracks, mapOf(1L to 100, 2L to 200, 3L to 300)).isEmpty())
    }

    // ---- 保存（JSON） ----

    @Test
    fun filterJson_roundTripAndTolerant() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val f = TrackFilter(
            year = 2024, month = 8, seasons = setOf(Season.SUMMER), weekdays = setOf(1, 7), dayParts = setOf(DayPart.MORNING),
            place = PlaceArea(PlaceShape.CIRCLE, lat = 35.6, lon = 139.7, radiusM = 2500.0, match = PlaceMatch.START, label = "東京駅"),
            distanceKm = NumRange(20.0, null), avgKmh = NumRange(null, 25.5), mode = ModeFilter.FREE, source = SourceFilter.IMPORTED,
            formats = setOf("fit"), withPhotos = true, name = "荒川",
        )
        val s = json.encodeToString(TrackFilter.serializer(), f)
        assertEquals(f, json.decodeFromString(TrackFilter.serializer(), s))
        assertEquals("{}", json.encodeToString(TrackFilter.serializer(), TrackFilter()))
        // 知らない項目（新しい版で足したもの）は無視
        assertEquals(TrackFilter(year = 2020), json.decodeFromString(TrackFilter.serializer(), """{"year":2020,"future":true}"""))
    }

    // ---- 速さ（目安） ----

    @Test
    fun speed_1200Tracks() {
        val rng = Random(3)
        val base = at("2011-01-01 00:00:00.000", tokyo)
        val tracks = (1..1200L).map { i ->
            track(i, base + rng.nextInt(15 * 365).toLong() * 86_400_000L + rng.nextInt(86_400_000), km = 5 + rng.nextDouble() * 120,
                movingMin = 20 + rng.nextDouble() * 300, ascent = rng.nextDouble() * 1500, name = "合成ライド $i ${if (i % 3 == 0L) "荒川" else "多摩川"}",
                importFormat = if (i % 2 == 0L) "fit" else null)
        }
        val geo = tracks.associate { t -> t.id to TrackGeo.build(t.id, 800, randomRide(rng, 35.5 + rng.nextDouble() * 0.4, 139.4 + rng.nextDouble() * 0.6, 800)) }
        val f = TrackFilter(year = 2020, distanceKm = NumRange(20.0, 80.0), name = "荒川", source = SourceFilter.IMPORTED)
        repeat(3) { TrackFilters.apply(tracks, f, TrackSort.DISTANCE, zone = tokyo) }
        var t0 = System.nanoTime()
        repeat(10) { TrackFilters.apply(tracks, f, TrackSort.DISTANCE, zone = tokyo) }
        val basicMs = (System.nanoTime() - t0) / 1e6 / 10
        val c = PlaceArea(PlaceShape.CIRCLE, lat = 35.7, lon = 139.7, radiusM = 5000.0)
        repeat(3) { TrackFilters.apply(tracks, TrackFilter(place = c), TrackSort.NEWEST, geo = geo, zone = tokyo) }
        t0 = System.nanoTime()
        val out = TrackFilters.apply(tracks, TrackFilter(place = c), TrackSort.NEWEST, geo = geo, zone = tokyo)
        val placeMs = (System.nanoTime() - t0) / 1e6
        println("filter 1200: basic ${"%.2f".format(basicMs)} ms, place ${"%.1f".format(placeMs)} ms (${out.items.size} hits, ${geo.values.sumOf { it.linePoints }} line points)")
        assertTrue(basicMs < 200.0)
        assertTrue(placeMs < 1000.0)
    }
}
