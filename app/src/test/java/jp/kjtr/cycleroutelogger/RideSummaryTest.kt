package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.data.TrackPointEntity
import jp.kjtr.cycleroutelogger.data.TrackRepository
import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.record.CountedStep
import jp.kjtr.cycleroutelogger.stats.GridCells
import jp.kjtr.cycleroutelogger.stats.ModeFilter
import jp.kjtr.cycleroutelogger.stats.RideFacts
import jp.kjtr.cycleroutelogger.stats.RideSummaries
import jp.kjtr.cycleroutelogger.stats.SummaryPeriod
import jp.kjtr.cycleroutelogger.stats.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** 全記録のまとめ（drive_logger の DriveSummaryTest を自転車向けの値にしたもの）。 */
class RideSummaryTest {
    private val jst = TimeZone.getTimeZone("Asia/Tokyo")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long = Calendar.getInstance(jst).apply {
        clear(); set(y, m - 1, d, h, min)
    }.timeInMillis

    private fun ride(
        id: Long, start: Long, minutes: Int, km: Double, maxKmh: Double, route: Boolean = false, photos: Int = 0,
        cells: Set<Long> = emptySet(), bins: Map<Int, Long> = emptyMap(), spans: List<Pair<Long, Long>> = emptyList(),
    ) = RideFacts(
        id, "r$id", start, start + minutes * 60_000L, km * 1000, minutes * 60_000L, maxKmh / 3.6, ascentM = 10.0,
        route = route, photos = photos, speedBinsMs = bins, cells = cells, movingSpans = spans,
    )

    @Test
    fun periodRanges() {
        val now = at(2026, 10, 13, 14)
        val year = RideSummaries.range(SummaryPeriod.THIS_YEAR, now, jst)
        assertEquals(at(2026, 1, 1, 0), year.fromMs)
        assertEquals(at(2027, 1, 1, 0), year.toMs)
        val month = RideSummaries.range(SummaryPeriod.THIS_MONTH, now, jst)
        assertEquals(at(2026, 10, 1, 0), month.fromMs)
        assertEquals(at(2026, 11, 1, 0), month.toMs)
        assertEquals(now - 30L * 86_400_000L, RideSummaries.range(SummaryPeriod.LAST_30_DAYS, now, jst).fromMs)
        assertEquals(TimeRange(null, null), RideSummaries.range(SummaryPeriod.ALL, now, jst))
        val custom = TimeRange(at(2026, 9, 1, 0), at(2026, 9, 2, 0))
        assertEquals(custom, RideSummaries.range(SummaryPeriod.CUSTOM, now, jst, custom))
    }

    @Test
    fun filterAndTotals() {
        val rides = listOf(
            ride(1, at(2026, 8, 30, 23, 30), 60, 25.0, 38.0, cells = setOf(1L, 2L), bins = mapOf(0 to 60_000L, 12 to 120_000L), photos = 3),
            ride(2, at(2026, 10, 5, 8), 30, 10.0, 52.0, route = true, cells = setOf(2L, 3L), bins = mapOf(5 to 30_000L)),
            ride(3, at(2026, 10, 5, 18), 90, 10.0, 30.0, photos = 2),
        )
        val oct = RideSummaries.filter(rides, RideSummaries.range(SummaryPeriod.THIS_MONTH, at(2026, 10, 13, 12), jst))
        assertEquals(listOf(2L, 3L), oct.map { it.id })
        assertEquals(listOf(2L), RideSummaries.filter(rides, TimeRange(null, null), ModeFilter.ROUTE).map { it.id })
        assertEquals(listOf(1L, 3L), RideSummaries.filter(rides, TimeRange(null, null), ModeFilter.FREE).map { it.id })

        val s = RideSummaries.summarize(rides, jst)
        assertEquals(3, s.rideCount)
        assertEquals(45_000.0, s.distanceM, 1e-6)
        assertEquals(180 * 60_000L, s.movingMs)
        assertEquals(45_000.0 / (180 * 60.0), s.avgSpeedMps, 1e-9)
        assertEquals(2L, s.maxSpeed!!.second.id)
        assertEquals(1L, s.longestDistance!!.id)
        assertEquals(3L, s.longestTime!!.id)
        assertEquals(30.0, s.ascentM, 1e-9)
        assertEquals(5, s.photos)
        // 8/30 と（日をまたいだ）8/31、10/5 = 3 日
        assertEquals(3, s.days)
        // 走らなかった 9 月も 0 で入る
        assertEquals(listOf("2026-08" to 25_000.0, "2026-09" to 0.0, "2026-10" to 20_000.0), s.monthlyM)
        assertEquals(3, s.cells)
        // 2 km/h ごと: 0, 5（10–12）, 12（24–26）
        assertEquals(2.0, s.histogram.binKmh, 0.0)
        assertEquals(13, s.histogram.bins.size)
        assertEquals(60_000L, s.histogram.bins[0].timeMs)
        assertEquals(30_000L, s.histogram.bins[5].timeMs)
        assertEquals(10.0, s.histogram.bins[5].fromKmh, 0.0)
        assertEquals(120_000L, s.histogram.bins[12].timeMs)
        assertEquals(210_000L, s.histogram.totalMs)
    }

    @Test
    fun weekdayAndHourSpreadAcrossMidnight() {
        // 走行の時間帯が無い（古い形）: 2026-09-30（水）23:30 から 60 分 → 水 23 時に 30 分、木 0 時に 30 分
        val s = RideSummaries.summarize(listOf(ride(1, at(2026, 9, 30, 23, 30), 60, 10.0, 30.0)), jst)
        assertEquals(30 * 60_000L, s.hourMs[23])
        assertEquals(30 * 60_000L, s.hourMs[0])
        assertEquals(30 * 60_000L, s.weekdayMs[3]) // 水
        assertEquals(30 * 60_000L, s.weekdayMs[4]) // 木
        assertEquals(60 * 60_000L, s.weekdayMs.sum())
    }

    @Test
    fun weekdayAndHourUseMovingSpans() {
        // 10:30〜13:30 の記録で、走ったのは 10:30–11:00 と 13:00–13:20 だけ（昼休みは自動一時停止）
        val start = at(2026, 10, 4, 10, 30) // 日曜
        val spans = listOf(start to at(2026, 10, 4, 11, 0), at(2026, 10, 4, 13, 0) to at(2026, 10, 4, 13, 20))
        val r = ride(1, start, 180, 15.0, 30.0, spans = spans).copy(movingMs = 50 * 60_000L)
        val s = RideSummaries.summarize(listOf(r), jst)
        assertEquals(30 * 60_000L, s.hourMs[10])
        assertEquals(0L, s.hourMs[11])
        assertEquals(0L, s.hourMs[12])
        assertEquals(20 * 60_000L, s.hourMs[13])
        assertEquals(50 * 60_000L, s.weekdayMs[0])
        assertEquals(s.movingMs, s.hourMs.sum())
        assertEquals(1, s.days)
    }

    @Test
    fun tiesPickTheNewerRideAndEmptyIsSafe() {
        val a = ride(1, at(2026, 1, 1, 10), 10, 5.0, 30.0)
        val b = ride(2, at(2026, 2, 1, 10), 10, 5.0, 30.0)
        val s = RideSummaries.summarize(listOf(a, b), jst)
        assertEquals(2L, s.longestDistance!!.id)
        assertEquals(2L, s.maxSpeed!!.second.id)
        val e = RideSummaries.summarize(emptyList(), jst)
        assertNull(e.maxSpeed)
        assertNull(e.longestDistance)
        assertEquals(0, e.days)
        assertEquals(0, e.photos)
        assertTrue(e.histogram.bins.isEmpty())
        assertTrue(e.monthlyM.isEmpty())
    }

    @Test
    fun movingSpansJoinAdjacentStepsAndSplitAtPauses() {
        val t0 = 1_700_000_000_000L
        val steps = listOf(
            CountedStep(t0 + 1000, 1000, 5.0, 5.0),
            CountedStep(t0 + 2000, 1000, 5.0, 5.0),
            CountedStep(t0 + 3000, 1000, 5.0, 5.0),
            // 60 秒の一時停止の後
            CountedStep(t0 + 64_000, 1000, 5.0, 5.0),
            CountedStep(t0 + 65_000, 0, 0.0, 0.0),
            CountedStep(t0 + 66_000, 1000, 5.0, 5.0),
        )
        val spans = RideSummaries.movingSpans(steps)
        assertEquals(listOf(t0 to t0 + 3000, t0 + 63_000 to t0 + 64_000, t0 + 65_000 to t0 + 66_000), spans)
        assertEquals(steps.sumOf { it.dtMs }, spans.sumOf { it.second - it.first })
    }

    @Test
    fun speedBinsAreTwoKmhWide() {
        val steps = listOf(
            CountedStep(0, 1000, 1.0, 1.99 / 3.6),  // 0–2
            CountedStep(0, 2000, 3.0, 2.0 / 3.6),   // 2–4（下端を含む）
            CountedStep(0, 3000, 50.0, 25.0 / 3.6), // 24–26
        )
        val (ms, m) = RideSummaries.speedBins(steps)
        assertEquals(mapOf(0 to 1000L, 1 to 2000L, 12 to 3000L), ms)
        assertEquals(50.0, m[12]!!, 1e-9)
    }

    @Test
    fun replayedRideMatchesTheRecordAndDropsJumps() {
        // 実際の流し直し（TrackRepository.analyze）: 北へ 5 m/s で 120 秒 → 60 秒止まる → 120 秒。途中に 100 km/h を超える跳び 1 点
        val t0 = 1_700_000_000_000L
        val pts = ArrayList<TrackPointEntity>()
        var here = TOKYO
        var sec = 0
        fun add(p: LatLon) {
            sec++
            pts.add(TrackPointEntity(0, 1, 0, t0 + sec * 1000L, p.lat, p.lon, null, 5f, null, null))
        }
        repeat(120) { here = offset(here, 5.0, 0.0); add(here) }
        add(offset(here, 2000.0, 0.0)) // 跳び（1 秒で 2 km）
        repeat(60) { add(here) }
        repeat(120) { here = offset(here, 5.0, 0.0); add(here) }

        val autoPause = TrackRepository.analyze(pts, autoPause = true)
        val tracker = TrackRepository.replay(pts, autoPause = true)
        val spans = RideSummaries.movingSpans(autoPause.steps)
        val (ms, m) = RideSummaries.speedBins(autoPause.steps)
        // 時間帯・分布の合計が記録の走行時間・距離に一致する
        assertEquals(tracker.movingMs, spans.sumOf { it.second - it.first })
        assertEquals(tracker.movingMs, ms.values.sum())
        assertEquals(tracker.distanceM, m.values.sum(), 1e-6)
        // 止まっていた間は自動一時停止で走行の時間帯に入らない（2 つに分かれる）
        assertTrue("spans=$spans", spans.size >= 2)
        assertTrue(tracker.movingMs < 250_000L)
        // 跳びは数えない（距離はおよそ 240 s × 5 m/s、100 km/h 以上の bin は無い）
        assertEquals(1200.0, tracker.distanceM, 30.0)
        assertTrue(ms.keys.all { it * RideSummaries.BIN_KMH < 100.0 })

        // 自動一時停止 OFF なら止まっていた時間も 0–2 km/h に入る
        val noPause = TrackRepository.analyze(pts, autoPause = false)
        val (ms2, _) = RideSummaries.speedBins(noPause.steps)
        assertTrue((ms2[0] ?: 0L) >= 50_000L)
        assertEquals(TrackRepository.replay(pts, autoPause = false).movingMs, RideSummaries.movingSpans(noPause.steps).sumOf { it.second - it.first })
    }
}

class GridCellsTest {
    private val mLat = Geo.METERS_PER_DEG_LAT

    @Test
    fun sameCellWithinOneKm() {
        val lat = 35.0005
        val base = GridCells.cellOf(lat, 139.0001)
        assertEquals(base, GridCells.cellOf(lat + 100 / mLat, 139.0001 + 100 / Geo.metersPerDegLon(lat)))
        assertNotEquals(base, GridCells.cellOf(lat + 1500 / mLat, 139.0001))
        assertNotEquals(base, GridCells.cellOf(-lat, 139.0001))
        assertNotEquals(base, GridCells.cellOf(lat, -139.0001))
    }

    @Test
    fun countsCellsAlongALineAndFillsGaps() {
        val lat0 = 35.0
        // 北へ 5 km（10 m おき）→ 5〜6 マス
        val dense = (0..500).map { (lat0 + it * 10 / mLat) to 139.0 }
        val n = GridCells.cellsOf(dense).size
        assertTrue("n=$n", n in 5..6)
        // 同じ 5 km を両端の 2 点だけで渡しても、間を補って同じ数
        assertEquals(GridCells.cellsOf(dense), GridCells.cellsOf(listOf(dense.first(), dense.last())))
        // 自転車では 10 km 以上の欠け（GPS の長い欠け・輪行など）は補わない
        assertEquals(2, GridCells.cellsOf(listOf(lat0 to 139.0, (lat0 + 12_000 / mLat) to 139.0)).size)
        // 区間ごとに足し込める
        val acc = HashSet<Long>()
        GridCells.cellsOf(dense.take(100), acc)
        GridCells.cellsOf(dense.drop(100), acc)
        assertEquals(GridCells.cellsOf(dense), acc)
    }
}
