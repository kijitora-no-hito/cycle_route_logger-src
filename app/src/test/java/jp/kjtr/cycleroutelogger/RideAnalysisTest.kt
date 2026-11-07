package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.data.TrackPointEntity
import jp.kjtr.cycleroutelogger.data.TrackRepository
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.record.CountedStep
import jp.kjtr.cycleroutelogger.record.Sample
import jp.kjtr.cycleroutelogger.stats.ElevationGain
import jp.kjtr.cycleroutelogger.stats.RideAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RideAnalysisTest {
    private val t0 = 1_700_000_000_000L

    private fun analysisOf(vararg steps: CountedStep) =
        RideAnalysis(steps.toList(), emptyList(), steps.sumOf { it.distM }, steps.sumOf { it.dtMs }, steps.maxOf { it.speedMps }, 0.0)

    private fun step(dtMs: Long, distM: Double, kmh: Double = distM / (dtMs / 1000.0) * 3.6) = CountedStep(0, dtMs, distM, kmh / 3.6)

    /** 北へ [mps] m/s で走る 1 Hz の点（[fromSec] 秒目から [seconds] 秒）。終わりの位置を返す。 */
    private fun ride(out: MutableList<Sample>, from: LatLon, fromSec: Int, seconds: Int, mps: Double, alt: (Int) -> Double? = { null }): LatLon {
        for (s in 1..seconds) {
            val p = offset(from, s * mps, 0.0)
            out.add(Sample(t0 + (fromSec + s) * 1000L, p.lat, p.lon, 5f, alt(fromSec + s), null))
        }
        return offset(from, seconds * mps, 0.0)
    }

    @Test
    fun boundaryIsInterpolatedBetweenPoints() {
        // 600 m を 100 秒 → 600 m を 100 秒。1 km の境目は 2 つ目の 400 m のところ（100 + 66.67 秒）
        val a = analysisOf(step(100_000, 600.0), step(100_000, 600.0))
        val sp = a.splits(1000.0)
        assertEquals(2, sp.size)
        assertEquals(0.0, sp[0].startM, 1e-9)
        assertEquals(1000.0, sp[0].endM, 1e-9)
        assertEquals(166_667L, sp[0].movingMs)
        assertFalse(sp[0].partial)
        // 端数 1000–1200 m
        assertEquals(1000.0, sp[1].startM, 1e-9)
        assertEquals(1200.0, sp[1].endM, 1e-9)
        assertEquals(33_333L, sp[1].movingMs)
        assertTrue(sp[1].partial)
        assertEquals(200_000L, sp.sumOf { it.movingMs })
        assertEquals(21.6, sp[0].avgSpeedMps * 3.6, 1e-3) // 1000 m / 166.667 s = 6 m/s
    }

    @Test
    fun oneStepCanCrossSeveralBoundaries() {
        val a = analysisOf(step(250_000, 2500.0))
        val sp = a.splits(1000.0)
        assertEquals(listOf(100_000L, 100_000L, 50_000L), sp.map { it.movingMs })
        assertEquals(listOf(false, false, true), sp.map { it.partial })
        assertEquals(2500.0, sp.last().endM, 1e-9)
    }

    @Test
    fun endingExactlyOnBoundary_noPartial_trailingTimeGoesToLastSplit() {
        // ちょうど 2 km で距離が終わり、その後に距離の増えない時間が 4 秒
        val a = analysisOf(step(200_000, 1000.0), step(200_000, 1000.0), step(4_000, 0.0, kmh = 1.0))
        val sp = a.splits(1000.0)
        assertEquals(2, sp.size)
        assertFalse(sp.last().partial)
        assertEquals(204_000L, sp.last().movingMs)
        assertEquals(404_000L, sp.sumOf { it.movingMs })
    }

    @Test
    fun shortRide_onlyPartialSplit_noExtremes() {
        val a = analysisOf(step(60_000, 300.0))
        val sp = a.splits(1000.0)
        assertEquals(1, sp.size)
        assertTrue(sp[0].partial)
        assertEquals(300.0, sp[0].distanceM, 1e-9)
        assertNull(RideAnalysis.extremes(sp))
    }

    @Test
    fun extremesIgnorePartialSplit() {
        // 1 km 目 200 秒（18 km/h）、2 km 目 150 秒（24 km/h）、3 km 目 250 秒、端数 100 m を 5 秒（72 km/h）
        val a = analysisOf(step(200_000, 1000.0), step(150_000, 1000.0), step(250_000, 1000.0), step(5_000, 100.0))
        val sp = a.splits(1000.0)
        assertEquals(4, sp.size)
        assertEquals(1 to 2, RideAnalysis.extremes(sp))
    }

    @Test
    fun maxSpeedPerSplit() {
        val a = analysisOf(step(100_000, 500.0, kmh = 20.0), step(100_000, 1000.0, kmh = 36.0), step(100_000, 500.0, kmh = 15.0))
        val sp = a.splits(1000.0)
        // 2 つ目の 1 回分は 500–1500 m なので 1・2 区間目の両方の最高になる
        assertEquals(36.0, sp[0].maxSpeedMps * 3.6, 1e-9)
        assertEquals(36.0, sp[1].maxSpeedMps * 3.6, 1e-9)
    }

    @Test
    fun fiveKmSplits() {
        val a = analysisOf(*Array(13) { step(120_000, 500.0) }) // 6.5 km
        val sp = a.splits(5000.0)
        assertEquals(2, sp.size)
        assertEquals(1_200_000L, sp[0].movingMs)
        assertEquals(1500.0, sp[1].distanceM, 1e-9)
        assertTrue(sp[1].partial)
    }

    @Test
    fun histogramBinEdges() {
        assertEquals(1, RideAnalysis.binIndex(2.0, 2.0))
        assertEquals(1, RideAnalysis.binIndex((2.0 / 3.6) * 3.6, 2.0))
        assertEquals(0, RideAnalysis.binIndex(1.999, 2.0))
        assertEquals(0, RideAnalysis.binIndex(0.0, 2.0))
        assertEquals(6, RideAnalysis.binIndex(12.0, 2.0))
        assertEquals(6, RideAnalysis.binIndex(13.99, 2.0))

        val a = analysisOf(step(10_000, 0.0, kmh = 2.0), step(20_000, 0.0, kmh = 1.0), step(30_000, 100.0, kmh = 13.0))
        val h = a.histogram(2.0)
        assertEquals(7, h.bins.size) // 0–2 … 12–14
        assertEquals(20_000L, h.bins[0].timeMs)
        assertEquals(10_000L, h.bins[1].timeMs)
        assertEquals(0L, h.bins[3].timeMs) // 間の空の bin も出す
        assertEquals(30_000L, h.bins[6].timeMs)
        assertEquals(12.0, h.bins[6].fromKmh, 1e-9)
        assertEquals(14.0, h.bins[6].toKmh, 1e-9)
        assertEquals(60_000L, h.totalMs)
        assertEquals(100.0, h.bins[6].distanceM, 1e-9)
    }

    @Test
    fun autoPauseIsExcluded_andTotalsMatchTracker() {
        val pts = ArrayList<Sample>()
        var p = ride(pts, TOKYO, 0, 120, 5.0)          // 18 km/h で 600 m
        for (s in 121..180) pts.add(Sample(t0 + s * 1000L, p.lat, p.lon, 5f, null, null)) // 60 秒止まる
        p = ride(pts, p, 180, 120, 7.0)                 // 25.2 km/h で 840 m
        val a = RideAnalysis.from(listOf(pts), autoPause = true)
        assertTrue("止まっていた 60 秒は走行時間に入らない: ${a.movingMs}", a.movingMs in 236_000L..250_000L)
        val sp = a.splits(1000.0)
        assertEquals(a.movingMs, sp.sumOf { it.movingMs })
        assertEquals(a.distanceM, sp.sumOf { it.distanceM }, 1e-6)
        val h = a.histogram()
        assertEquals(a.movingMs, h.totalMs)
        assertEquals(a.distanceM, h.totalM, 1e-6)
        // 止まっていた分は 0–2 km/h に入らない（自動一時停止になるまでの数秒だけ）
        assertTrue("0-2 km/h: ${h.bins[0].timeMs}", h.bins[0].timeMs <= 6_000L)
        // 18 km/h（bin 9 = 18–20）と 25.2 km/h（bin 12 = 24–26）がほとんど
        assertTrue(h.bins[9].timeMs >= 110_000L)
        assertTrue(h.bins[12].timeMs >= 110_000L)
        assertEquals(h.avgKmh, a.distanceM / (a.movingMs / 1000.0) * 3.6, 1e-9)
    }

    @Test
    fun withoutAutoPause_stoppedTimeIsCountedInLowestBin() {
        val pts = ArrayList<Sample>()
        val p = ride(pts, TOKYO, 0, 60, 5.0)
        for (s in 61..120) pts.add(Sample(t0 + s * 1000L, p.lat, p.lon, 5f, null, null))
        val a = RideAnalysis.from(listOf(pts), autoPause = false)
        val h = a.histogram()
        assertEquals(a.movingMs, h.totalMs)
        assertTrue(h.bins[0].timeMs >= 55_000L)
    }

    @Test
    fun manualPauseSegments_distanceContinuesAcrossSegments() {
        // 1 本目 800 m、（一時停止）、2 本目 800 m。1 km の境目は 2 本目の 200 m のところ
        val seg1 = ArrayList<Sample>()
        val p = ride(seg1, TOKYO, 0, 160, 5.0)
        val seg2 = ArrayList<Sample>()
        // 一時停止の間に 300 m 動いている（その間の距離・時間は数えない）
        val restart = offset(p, 300.0, 0.0)
        seg2.add(Sample(t0 + 400_000L, restart.lat, restart.lon, 5f, null, null))
        ride(seg2, restart, 400, 160, 5.0)
        val a = RideAnalysis.from(listOf(seg1, seg2), autoPause = true)
        assertEquals(1595.0, a.distanceM, 1.0) // 1 本目は 159 回分（最初の点は距離なし）
        assertEquals(319_000L, a.movingMs)
        val sp = a.splits(1000.0)
        assertEquals(2, sp.size)
        assertEquals(200_000.0, sp[0].movingMs.toDouble(), 2_000.0)
        assertEquals(a.movingMs, sp.sumOf { it.movingMs })
        assertTrue(sp[1].partial)
        assertEquals(595.0, sp[1].distanceM, 1.0)
    }

    @Test
    fun noisyRealisticRide_totalsMatchRecordStatsExactly() {
        val rnd = Random(7)
        val pts = ArrayList<TrackPointEntity>()
        var pos = TOKYO
        var t = 0
        var seg = 0
        var ele = 20.0
        // 速さを変えながら 6 km ほど。途中で止まり・手動の一時停止・GPS の跳び・精度の悪い点を混ぜる
        for (phase in 0 until 40) {
            val kmh = listOf(8.0, 14.0, 21.0, 27.0, 33.0, 0.0)[phase % 6]
            val secs = 20 + rnd.nextInt(30)
            if (phase == 23) { seg++; t += 120 }
            for (s in 0 until secs) {
                t++
                val mps = kmh / 3.6
                pos = offset(pos, mps + rnd.nextDouble(-0.5, 0.5).takeIf { kmh > 0 }.let { it ?: 0.0 }, rnd.nextDouble(-0.8, 0.8))
                ele += if (phase % 4 == 1) 0.4 else -0.1
                val jump = rnd.nextInt(200) == 0
                val q = if (jump) offset(pos, 400.0, 0.0) else pos
                val acc = if (rnd.nextInt(50) == 0) 60f else 4f + rnd.nextFloat() * 6f
                pts.add(TrackPointEntity(trackId = 1, segment = seg, time = t0 + t * 1000L, lat = q.lat, lon = q.lon, ele = ele, accuracy = acc, deviceSpeed = null, speed = null))
            }
        }
        for (autoPause in listOf(true, false)) {
            val a = TrackRepository.analyze(pts, autoPause)
            val tr = TrackRepository.replay(pts, autoPause)
            assertEquals(tr.movingMs, a.movingMs)
            assertEquals(tr.distanceM, a.distanceM, 1e-9)
            assertTrue(a.distanceM > 4000)
            for (unit in listOf(1000.0, 5000.0)) {
                val sp = a.splits(unit)
                assertEquals("unit=$unit autoPause=$autoPause", tr.movingMs, sp.sumOf { it.movingMs })
                assertEquals(tr.distanceM, sp.sumOf { it.distanceM }, 1e-6)
                // 獲得標高（0.9.0〜 ElevationGain）: 区間の登りの合計 = 記録の獲得標高（statsOf と同じ計算）
                assertEquals(ElevationGain.of(pts), sp.sumOf { it.ascentM }, 1e-6)
                assertEquals(a.ascentM, sp.sumOf { it.ascentM }, 1e-6)
                assertEquals(tr.maxSpeedMps, sp.maxOf { it.maxSpeedMps }, 1e-9)
                sp.dropLast(1).forEach { assertEquals(unit, it.distanceM, 1e-6); assertFalse(it.partial) }
            }
            val h = a.histogram()
            assertEquals(tr.movingMs, h.totalMs)
            assertEquals(tr.distanceM, h.totalM, 1e-6)
            // 最高速度を含む bin まで
            assertEquals(RideAnalysis.binIndex(tr.maxSpeedMps * 3.6, 2.0), h.bins.size - 1)
        }
    }

    @Test
    fun niceStep() {
        assertEquals(2.0, RideAnalysis.niceStep(7.0), 1e-9)
        assertEquals(5.0, RideAnalysis.niceStep(18.0), 1e-9)
        assertEquals(10.0, RideAnalysis.niceStep(32.0), 1e-9)
        assertEquals(0.5, RideAnalysis.niceStep(1.5), 1e-9)
    }
}
