package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.geo.AscentCounter
import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.geo.Simplify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GeoTest {
    @Test
    fun haversine_knownDistances() {
        // 緯度 1 度 ≈ 111.195 km（平均半径）
        assertEquals(111_195.0, Geo.distanceM(35.0, 139.0, 36.0, 139.0), 5.0)
        // 東京駅 → 大阪駅 ≈ 403 km
        assertEquals(403_000.0, Geo.distanceM(35.681236, 139.767125, 34.702485, 135.495951), 2_000.0)
        assertEquals(0.0, Geo.distanceM(35.0, 139.0, 35.0, 139.0), 1e-9)
        // 赤道上の経度 1 度
        assertEquals(111_195.0, Geo.distanceM(0.0, 0.0, 0.0, 1.0), 5.0)
    }

    @Test
    fun offsetHelper_matchesHaversine() {
        val p = offset(TOKYO, 300.0, 400.0)
        assertEquals(500.0, Geo.distanceM(TOKYO, p), 0.5)
    }

    @Test
    fun lengthOfPolyline() {
        val pts = (0..10).map { offset(TOKYO, 0.0, it * 100.0) }
        assertEquals(1000.0, Geo.lengthM(pts), 0.5)
    }

    @Test
    fun ascent_countsClimbIgnoresNoise() {
        val rnd = Random(1)
        val climb = AscentCounter()
        for (i in 0..200) climb.add(i * 0.25 + rnd.nextDouble(-1.5, 1.5)) // 50 m 登る + ±1.5 m の揺れ
        assertTrue("climb=${climb.ascentM}", climb.ascentM in 40.0..56.0)

        val flat = AscentCounter()
        for (i in 0..600) flat.add(100.0 + rnd.nextDouble(-2.0, 2.0))
        assertTrue("flat=${flat.ascentM}", flat.ascentM < 4.0)

        // 下りは数えない
        val down = AscentCounter()
        for (i in 0..200) down.add(200.0 - i * 0.5)
        assertEquals(0.0, down.ascentM, 1e-9)
    }

    @Test
    fun ascent_forRoute_sumsSegmentsAndSkipsNull() {
        val seg1 = (0..40).map { if (it == 7) null else 10.0 + it * 0.5 } // 10 → 30 m（途中に標高なしの点）
        val seg2 = (0..20).map { 100.0 - it * 0.5 } + (1..40).map { 90.0 + it * 0.5 } // 100 → 90 → 110 m
        val a = AscentCounter.forRoute(listOf(seg1, seg2))
        // 1 区間目 +20、2 区間目 90→110 で +20（区間の間の 30→100 は数えない）。
        // 3 m 刻みで数えるので、各区間の終わりの 3 m 未満の登りと平滑化の遅れの分だけ少なめになる。
        assertTrue("ascent=$a", a in 34.0..40.5)
    }

    @Test
    fun simplify_keepsEndsAndShape() {
        val line = (0..1000).map { offset(TOKYO, if (it == 500) 50.0 else 0.0, it * 1.0) }
        val s = Simplify.douglasPeucker(line, 3.0)
        assertTrue(s.size in 3..6)
        assertEquals(line.first(), s.first())
        assertEquals(line.last(), s.last())
        assertTrue(s.contains(line[500]))
        val st = Simplify.stride((0 until 10_001).toList(), 1000)
        assertTrue(st.size <= 1001)
        assertEquals(0, st.first())
        assertEquals(10_000, st.last())
    }

    @Suppress("unused")
    private fun unused(l: LatLon) = l
}
