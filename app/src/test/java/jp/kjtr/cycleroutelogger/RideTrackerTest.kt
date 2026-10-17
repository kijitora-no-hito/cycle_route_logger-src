package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.data.TrackPointEntity
import jp.kjtr.cycleroutelogger.data.TrackRepository
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.record.RideTracker
import jp.kjtr.cycleroutelogger.record.Sample
import jp.kjtr.cycleroutelogger.record.TrackerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RideTrackerTest {
    private val t0 = 1_700_000_000_000L

    private fun sample(i: Int, p: LatLon, acc: Float? = 5f, alt: Double? = null, speed: Float? = null) =
        Sample(t0 + i * 1000L, p.lat, p.lon, acc, alt, speed)

    /** 北へ [mps] m/s で [seconds] 秒走るサンプル（1 Hz）。 */
    private fun ride(start: LatLon, fromSec: Int, seconds: Int, mps: Double): List<Sample> =
        (0..seconds).map { s -> sample(fromSec + s, offset(start, s * mps, 0.0)) }

    @Test
    fun straightRide_distanceTimeSpeed() {
        val tr = RideTracker()
        ride(TOKYO, 0, 60, 5.0).forEach { tr.feed(it) }
        assertEquals(300.0, tr.distanceM, 2.0)
        assertEquals(60_000L, tr.movingMs)
        assertEquals(5.0, tr.averageSpeedMps, 0.1)
        assertEquals(5.0, tr.maxSpeedMps, 0.1)
        assertEquals(5.0, tr.currentSpeedMps, 0.1)
        assertFalse(tr.autoPaused)
    }

    @Test
    fun deviceSpeedIsPreferred() {
        val tr = RideTracker()
        (0..10).forEach { s -> tr.feed(sample(s, offset(TOKYO, s * 5.0, 0.0), speed = 6f)) }
        assertEquals(6.0, tr.currentSpeedMps, 1e-6)
    }

    @Test
    fun bogusZeroDeviceSpeed_usesPositions() {
        // 速度を常に 0 で返す（エミュレータの geo fix など）
        val tr = RideTracker()
        (0..30).forEach { s -> tr.feed(sample(s, offset(TOKYO, s * 5.0, 0.0), speed = 0f)) }
        assertEquals(150.0, tr.distanceM, 2.0)
        assertTrue(tr.movingMs >= 25_000L)
        assertFalse(tr.autoPaused)
        // 本当に止まっているときは 0 のまま（自動一時停止になる）
        val stop = offset(TOKYO, 150.0, 0.0)
        (31..45).forEach { s -> tr.feed(sample(s, stop, speed = 0f)) }
        assertTrue(tr.autoPaused)
    }

    @Test
    fun inaccurateAndDuplicatePointsAreRejected() {
        val tr = RideTracker()
        tr.feed(sample(0, TOKYO))
        val r1 = tr.feed(sample(1, offset(TOKYO, 5.0, 0.0), acc = 50f))
        assertEquals(RideTracker.Result.Rejected(RideTracker.Reject.INACCURATE), r1)
        val r2 = tr.feed(sample(0, offset(TOKYO, 5.0, 0.0)))
        assertEquals(RideTracker.Result.Rejected(RideTracker.Reject.DUPLICATE), r2)
        assertEquals(0.0, tr.distanceM, 1e-9)
    }

    @Test
    fun jumpIsRejected() {
        val tr = RideTracker()
        val pts = ride(TOKYO, 0, 20, 5.0).toMutableList()
        // 10 秒目に 1 km 先へ跳んだ点（360 km/h）
        val jump = sample(10, offset(TOKYO, 1000.0, 0.0)).copy(timeMs = t0 + 10_500)
        pts.add(11, jump)
        val results = pts.map { tr.feed(it) }
        assertEquals(1, results.count { it == RideTracker.Result.Rejected(RideTracker.Reject.JUMP) })
        assertEquals(100.0, tr.distanceM, 2.0)
    }

    @Test
    fun jumpStreak_restartsFromNewPosition() {
        val tr = RideTracker(TrackerConfig(jumpResetCount = 5))
        // 最初の 1 点が 10 km 離れた外れ値
        tr.feed(sample(0, offset(TOKYO, 10_000.0, 0.0)))
        val results = (1..20).map { s -> tr.feed(sample(s, offset(TOKYO, s * 5.0, 0.0))) }
        assertEquals(4, results.count { it is RideTracker.Result.Rejected })
        val restart = results[4] as RideTracker.Result.Accepted
        assertTrue(restart.newSegment)
        // 5 秒目から 20 秒目まで 15 秒 × 5 m/s
        assertEquals(75.0, tr.distanceM, 2.0)
    }

    @Test
    fun autoPause_excludesStillTimeAndJitter() {
        val rnd = Random(7)
        fun scenario(autoPause: Boolean): RideTracker {
            val tr = RideTracker(TrackerConfig(autoPause = autoPause))
            ride(TOKYO, 0, 30, 5.0).forEach { tr.feed(it) }
            val stop = offset(TOKYO, 150.0, 0.0)
            // 60 秒止まる（±1.5 m の揺れ）
            for (s in 31..90) tr.feed(sample(s, offset(stop, rnd.nextDouble(-1.5, 1.5), rnd.nextDouble(-1.5, 1.5))))
            ride(stop, 91, 30, 5.0).drop(1).forEach { tr.feed(it) }
            return tr
        }
        val on = scenario(true)
        assertTrue("moving=${on.movingMs}", on.movingMs in 58_000L..70_000L)
        assertTrue("dist=${on.distanceM}", on.distanceM in 290.0..315.0)
        val off = scenario(false)
        assertEquals(121_000L, off.movingMs)
        assertTrue(off.distanceM >= on.distanceM)
    }

    @Test
    fun autoPause_flagWhileStopped() {
        val tr = RideTracker()
        ride(TOKYO, 0, 10, 5.0).forEach { tr.feed(it) }
        val stop = offset(TOKYO, 50.0, 0.0)
        for (s in 11..30) tr.feed(sample(s, stop))
        assertTrue(tr.autoPaused)
        ride(stop, 31, 10, 5.0).drop(1).forEach { tr.feed(it) }
        assertFalse(tr.autoPaused)
    }

    @Test
    fun breakSegment_gapIsNotCounted() {
        val tr = RideTracker()
        ride(TOKYO, 0, 10, 5.0).forEach { tr.feed(it) }
        tr.breakSegment()
        // 10 分後に 1 km 先から再開
        val far = offset(TOKYO, 1050.0, 0.0)
        ride(far, 610, 10, 5.0).forEach { tr.feed(it) }
        assertEquals(100.0, tr.distanceM, 2.0)
        assertEquals(20_000L, tr.movingMs)
    }

    @Test
    fun ascentFromGpsAltitude() {
        // 0.9.0〜 記録中の獲得標高は ElevationGain.Live（後ろ向きの窓・10 m のヒステリシス）。10 分で 60 m 登って 5 分平ら
        val rnd = Random(3)
        val tr = RideTracker()
        for (s in 0..900) tr.feed(sample(s, offset(TOKYO, s * 4.0, 0.0), alt = 20.0 + minOf(s, 600) * 0.1 + rnd.nextDouble(-1.0, 1.0)))
        assertTrue("ascent=${tr.ascentM}", tr.ascentM in 50.0..64.0)
        // 平らな道で GPS の高度が σ 4 m で揺れる（0.8.0 の数え方の AscentCounter では数十 m 以上になっていた）
        val noise = java.util.Random(5)
        val flat = RideTracker()
        val old = jp.kjtr.cycleroutelogger.geo.AscentCounter()
        for (s in 0..1800) {
            val alt = 30.0 + noise.nextGaussian() * 4.0
            flat.feed(sample(s, offset(TOKYO, s * 5.0, 0.0), alt = alt))
            old.add(alt)
        }
        println("flat 30 min: live %.1f m（0.8.0 の数え方 %.1f m）".format(flat.ascentM, old.ascentM))
        assertTrue("flat=${flat.ascentM}", flat.ascentM < 10.0)
        assertTrue(old.ascentM > 100.0)
    }

    @Test
    fun replayOfStoredPoints_givesSameStats() {
        val rnd = Random(11)
        val tr = RideTracker()
        val stored = ArrayList<TrackPointEntity>()
        var segment = 0
        var segHasPoints = false
        fun feed(s: Sample) {
            val r = tr.feed(s)
            if (r is RideTracker.Result.Accepted) {
                if (r.newSegment && segHasPoints) {
                    segment++; segHasPoints = false
                }
                segHasPoints = true
                stored.add(TrackPointEntity(trackId = 1, segment = segment, time = s.timeMs, lat = s.lat, lon = s.lon, ele = s.altitudeM, accuracy = s.accuracyM, deviceSpeed = s.speedMps, speed = null))
            }
        }
        var p = TOKYO
        for (s in 0..300) {
            p = offset(p, if (s in 100..140) rnd.nextDouble(-1.0, 1.0) else 5.0 + rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-0.5, 0.5))
            val acc = if (s % 37 == 0) 60f else 6f
            feed(sample(s, p, acc = acc, alt = 30.0 + s * 0.1))
            if (s == 200) {
                // 手動の一時停止 → 再開
                tr.breakSegment()
                if (segHasPoints) {
                    segment++; segHasPoints = false
                }
            }
        }
        val re = TrackRepository.replay(stored, autoPause = true)
        assertEquals(tr.distanceM, re.distanceM, 1e-6)
        assertEquals(tr.movingMs, re.movingMs)
        assertEquals(tr.maxSpeedMps, re.maxSpeedMps, 1e-9)
        assertEquals(tr.ascentM, re.ascentM, 1e-9)
    }

    @Test
    fun medianOfSpeeds() {
        assertEquals(3.0, RideTracker.median(listOf(1.0, 3.0, 100.0)), 1e-9)
        assertEquals(2.5, RideTracker.median(listOf(1.0, 2.0, 3.0, 4.0)), 1e-9)
        assertEquals(0.0, RideTracker.median(emptyList()), 1e-9)
    }
}
