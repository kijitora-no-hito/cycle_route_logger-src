package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.data.TrackPointEntity
import jp.kjtr.cycleroutelogger.data.TrackRepository
import jp.kjtr.cycleroutelogger.geo.AscentCounter
import jp.kjtr.cycleroutelogger.stats.ElevationGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 獲得標高（0.9.0。ドライブロガー 0.8.0 の ElevationGainTest を自転車の速さで）。GPS の高度の雑音（白色 σ 5 m＋ゆっくり動くずれ σ 4 m・τ 60 s、
 * ときどき 30 m の跳び）を入れた 1 Hz の点で、平地はほぼ 0、坂は正しく増えること、ネットワーク位置・精度の悪い点を使わないこと、
 * 区間ごとに数えること、0.8.0 の数え方（指数移動平均＋4 m）では平地で大きく出ていたことを確かめる。
 */
class ElevationGainTest {
    /** [speed] [m/s] で北へ進む（ならしの窓は距離でも区切る）。 */
    private fun noisy(seconds: Int, seed: Long, speed: Double = 5.0, truth: (Int) -> Double): List<TrackPointEntity> {
        val rnd = Random(seed)
        var wander = 0.0
        val a = exp(-1.0 / 60.0)
        val q = 4.0 * sqrt(1 - a * a)
        return (0 until seconds).map { s ->
            wander = a * wander + q * rnd.nextGaussian()
            var e = truth(s) + wander + rnd.nextGaussian() * 5.0
            if (rnd.nextInt(200) == 0) e += 30.0
            point(s.toLong() + 1, s * 1000L, e, altAcc = 8f, lat = 35.68 + s * speed / 111_320.0)
        }
    }

    private fun point(
        id: Long, time: Long, ele: Double?, altAcc: Float? = null, acc: Float? = 5f,
        provider: Int = TrackPointEntity.PROVIDER_GPS, segment: Int = 0, lat: Double = 35.68,
    ) = TrackPointEntity(
        id = id, trackId = 1, segment = segment, time = time, lat = lat, lon = 139.76, ele = ele, accuracy = acc,
        deviceSpeed = 5f, speed = 5f, altAccuracy = altAcc, provider = provider,
    )

    private fun oldAscent(pts: List<TrackPointEntity>): Double = AscentCounter().also { c -> pts.forEach { p -> p.ele?.let(c::add) } }.ascentM

    @Test
    fun flatGroundWithGpsNoiseIsNearZero() {
        for (seed in 1L..5L) {
            val pts = noisy(3600, seed) { 40.0 }
            val gain = ElevationGain.of(pts)
            println("flat 1 h seed $seed: gain %.1f m (0.8.0 の数え方 %.1f m)".format(gain, oldAscent(pts)))
            assertTrue("flat gain $gain", gain < 40.0)
            assertTrue(oldAscent(pts) > 500.0)
        }
    }

    @Test
    fun hillIsCounted() {
        // 10 分平ら → 20 分で 100 m 登る（上りは 3 m/s）→ 10 分平ら → 20 分で 100 m 下る
        for (seed in 1L..3L) {
            val pts = noisy(3600, seed, speed = 3.0) { s ->
                when {
                    s < 600 -> 40.0
                    s < 1800 -> 40.0 + 100.0 * (s - 600) / 1200
                    s < 2400 -> 140.0
                    else -> 140.0 - 100.0 * (s - 2400) / 1200
                }
            }
            val gain = ElevationGain.of(pts)
            println("hill +100 m seed $seed: gain %.1f m".format(gain))
            assertEquals(100.0, gain, 15.0)
        }
        // 雑音の無い坂: 60 m 登る → 最後の 10 m 未満の残りは数えない（ヒステリシス）
        val clean = (0 until 600).map { s -> point(s + 1L, s * 1000L, 10.0 + s * 0.1, altAcc = 3f, lat = 35.68 + s * 3.0 / 111_320.0) }
        assertEquals(59.9, ElevationGain.of(clean), 10.0)
    }

    /**
     * 坂を繰り返す（40 m を 3 分で登って 1 分で下る、を 6 回。間は 1 分ずつ平ら）。真の値 240 m に対して 170〜195 m（前後 60 秒のならしで頂上が
     * 低くなる分、短い坂は 2〜3 割少なめ。窓を前後 30 秒にすると 212 m になるが、平地の 1 時間が 10〜50 m に増えるのでドライブロガーと同じ 60 秒のまま）。
     */
    @Test
    fun repeatedHills() {
        for (seed in 1L..3L) {
            val pts = noisy(6 * 420, seed, speed = 4.0) { s ->
                val k = s % 420
                when {
                    k < 60 -> 20.0
                    k < 240 -> 20.0 + 40.0 * (k - 60) / 180
                    k < 300 -> 60.0
                    k < 360 -> 60.0 - 40.0 * (k - 300) / 60
                    else -> 20.0
                }
            }
            val gain = ElevationGain.of(pts)
            println("hills 6 × 40 m seed $seed: gain %.1f m".format(gain))
            assertTrue("hills $gain", gain in 160.0..250.0)
        }
    }

    @Test
    fun badPointsAreNotUsed() {
        // GPS の点は平ら（40 m）、ネットワーク位置は高度の基準が違って 0 m・80 m を行き来する（0.8.0 までは全部数えていた）
        val pts = ArrayList<TrackPointEntity>()
        for (s in 0 until 600) {
            val net = s % 30 in 10..14
            pts.add(
                if (net) point(s + 1L, s * 1000L, if (s % 60 < 30) 0.0 else 80.0, acc = 25f, provider = TrackPointEntity.PROVIDER_NETWORK)
                else point(s + 1L, s * 1000L, 40.0, altAcc = 6f),
            )
        }
        println("network altitude: 0.8.0 の数え方 %.1f m".format(oldAscent(pts)))
        assertTrue(oldAscent(pts) > 100)
        assertEquals(0.0, ElevationGain.of(pts), 0.01)
        // 使わない点
        assertFalse(ElevationGain.usable(TrackRepository.sampleOf(point(1, 0, 50.0, altAcc = 30f))))
        assertFalse(ElevationGain.usable(TrackRepository.sampleOf(point(1, 0, null))))
        assertFalse(ElevationGain.usable(TrackRepository.sampleOf(point(1, 0, 50.0, provider = TrackPointEntity.PROVIDER_NETWORK))))
        // 0.8.0 までの点（高度の精度・出どころなし）は水平の精度で判断。精度も無い（バックアップから戻した点）は使う
        assertTrue(ElevationGain.usable(TrackRepository.sampleOf(point(1, 0, 50.0, altAcc = null, acc = 8f, provider = TrackPointEntity.PROVIDER_UNKNOWN))))
        assertFalse(ElevationGain.usable(TrackRepository.sampleOf(point(1, 0, 50.0, altAcc = null, acc = 25f, provider = TrackPointEntity.PROVIDER_UNKNOWN))))
        assertTrue(ElevationGain.usable(TrackRepository.sampleOf(point(1, 0, 50.0, altAcc = null, acc = null, provider = TrackPointEntity.PROVIDER_UNKNOWN))))
        // Google の融合測位の点も高度の精度で判断
        assertTrue(ElevationGain.usable(TrackRepository.sampleOf(point(1, 0, 50.0, altAcc = 6f, provider = TrackPointEntity.PROVIDER_FUSED))))
    }

    @Test
    fun segmentsAreCountedSeparately() {
        // 区間 0 は 40 m、区間 1 は 100 m（一時停止の間に登った分は数えない）
        val pts = (0 until 120).map { s -> point(s + 1L, s * 1000L, 40.0, altAcc = 5f, segment = 0) } +
            (0 until 120).map { s -> point(s + 200L, 500_000L + s * 1000L, 100.0, altAcc = 5f, segment = 1) }
        assertEquals(0.0, ElevationGain.of(pts), 0.01)
    }

    /** 記録の統計と区間の登りは同じ計算（statsOf の獲得標高 = 区間の合計 = ElevationGain.of）。記録中の表示（Live）も近い値。 */
    @Test
    fun statsSplitsAndLiveAgree() {
        val pts = noisy(2400, 4, speed = 4.0) { s -> if (s < 1200) 30.0 + s * 0.05 else 90.0 }
        val st = TrackRepository.statsOf(pts, autoPause = true, smooth = null)
        val a = TrackRepository.analyze(pts, autoPause = true)
        assertEquals(ElevationGain.of(pts), st.ascentM, 1e-9)
        assertEquals(st.ascentM, a.splits(1000.0).sumOf { it.ascentM }, 1e-6)
        assertEquals(st.ascentM, a.ascentM, 1e-9)
        val live = TrackRepository.replay(pts, autoPause = true).ascentM
        println("+60 m: batch %.1f m / live %.1f m".format(st.ascentM, live))
        assertEquals(60.0, st.ascentM, 15.0)
        assertEquals(st.ascentM, live, 20.0)
    }
}
