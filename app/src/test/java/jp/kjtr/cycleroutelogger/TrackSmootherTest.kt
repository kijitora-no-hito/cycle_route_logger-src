package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.data.GpxSource
import jp.kjtr.cycleroutelogger.data.TrackPointEntity
import jp.kjtr.cycleroutelogger.data.TrackRepository
import jp.kjtr.cycleroutelogger.geo.SmoothConfig
import jp.kjtr.cycleroutelogger.geo.TrackSmoother
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 軌跡のなめらかさ（0.9.0。ドライブロガー 0.8.0 の TrackSmootherTest を自転車の値で）。GPS 1 Hz のギザギザ（白色 σ 2.5 m＋ゆっくりしたずれ σ 1.5 m）を入れた
 * 直線・L 字（交差点）・円弧で、真の位置からのずれと線の長さが生の点より小さくなること、交差点の角が消えない（角の近くのずれが生の点より大きくならない）こと、
 * 飛んだ点を使わないこと、記録中の前向きのフィルタに遅れが出ないことを確かめる。
 */
class TrackSmootherTest {
    private val lat0 = 35.70
    private val lon0 = 139.70
    private val mLat = 111_320.0
    private val mLon = 111_320.0 * cos(lat0 * PI / 180)
    private val cfg = SmoothConfig.BICYCLE

    /** 真の軌跡（1 秒ごとの東・北 [m]）。 */
    private fun straight(seconds: Int, v: Double): List<Pair<Double, Double>> = (0 until seconds).map { s -> (v * s * 0.6) to (v * s * 0.8) }

    /** 東へ [v] で 60 秒 → 半径 [radius] で左へ 90° 曲がる → 北へ 60 秒。曲がり始めの添字も返す。 */
    private fun lShape(v: Double, radius: Double): Pair<List<Pair<Double, Double>>, Int> {
        val out = ArrayList<Pair<Double, Double>>()
        val dt = 0.02
        var x = 0.0
        var y = 0.0
        var h = PI / 2 // 東
        var t = 0.0
        val turnTime = (PI / 2) * radius / v
        var nextSample = 0.0
        var cornerIdx = -1
        while (t < 120 + turnTime) {
            if (t >= nextSample - 1e-9) {
                out.add(x to y)
                nextSample += 1.0
            }
            val turning = t >= 60 && t < 60 + turnTime
            if (turning && cornerIdx < 0) cornerIdx = out.size
            if (turning) h -= (v / radius) * dt
            x += v * sin(h) * dt
            y += v * cos(h) * dt
            t += dt
        }
        return out to cornerIdx
    }

    private fun arc(seconds: Int, v: Double, radius: Double): List<Pair<Double, Double>> = (0 until seconds).map { s ->
        val a = v * s / radius
        radius * sin(a) to radius * (1 - cos(a))
    }

    private fun noisy(truth: List<Pair<Double, Double>>, seed: Long): List<TrackSmoother.In> {
        val rnd = Random(seed)
        var ex = 0.0
        var ey = 0.0
        val a = exp(-1.0 / 10.0)
        val q = 1.5 * sqrt(1 - a * a)
        return truth.mapIndexed { i, (x, y) ->
            ex = a * ex + q * rnd.nextGaussian()
            ey = a * ey + q * rnd.nextGaussian()
            val gx = x + ex + rnd.nextGaussian() * 2.5
            val gy = y + ey + rnd.nextGaussian() * 2.5
            TrackSmoother.In(i * 1000L, lat0 + gy / mLat, lon0 + gx / mLon, 5f)
        }
    }

    private class M(val rmsRaw: Double, val rmsSmooth: Double, val lenRaw: Double, val lenSmooth: Double, val lenTrue: Double, val maxRaw: Double, val maxSmooth: Double)

    private fun measure(truth: List<Pair<Double, Double>>, input: List<TrackSmoother.In>, out: TrackSmoother.Out, range: IntRange = truth.indices): M {
        val raw = input.map { (it.lon - lon0) * mLon to (it.lat - lat0) * mLat }
        val sm = input.indices.map { (out.lon[it] - lon0) * mLon to (out.lat[it] - lat0) * mLat }
        fun err(p: List<Pair<Double, Double>>) = range.map { hypot(p[it].first - truth[it].first, p[it].second - truth[it].second) }
        fun len(p: List<Pair<Double, Double>>) = p.zipWithNext().sumOf { (a, b) -> hypot(b.first - a.first, b.second - a.second) }
        val er = err(raw)
        val es = err(sm)
        return M(rms(er), rms(es), len(raw), len(sm), len(truth), er.max(), es.max())
    }

    private fun rms(v: List<Double>) = sqrt(v.sumOf { it * it } / v.size)

    @Test
    fun straightLineGetsCloserAndShorter() {
        // 自転車の速さ（上り坂 3 m/s・普通 5 m/s・下り 9 m/s）
        for (speed in listOf(3.0, 5.0, 9.0)) {
            for (seed in 1L..3L) {
                val truth = straight(180, speed)
                val input = noisy(truth, seed)
                val m = measure(truth, input, TrackSmoother.smooth(input, cfg))
                println("straight %.1f m/s seed %d: RMS raw %.2f → %.2f m, length raw ×%.3f → ×%.3f".format(speed, seed, m.rmsRaw, m.rmsSmooth, m.lenRaw / m.lenTrue, m.lenSmooth / m.lenTrue))
                assertTrue("RMS ${m.rmsSmooth} < raw ${m.rmsRaw}", m.rmsSmooth < m.rmsRaw * 0.8)
                assertTrue("length ${m.lenSmooth / m.lenTrue}", m.lenSmooth / m.lenTrue < 1.0 + (m.lenRaw / m.lenTrue - 1.0) * 0.5)
            }
        }
    }

    @Test
    fun cornerIsKept() {
        // 交差点: 4 m/s で半径 6 m、6 m/s で半径 10 m
        for ((v, r) in listOf(4.0 to 6.0, 6.0 to 10.0)) {
            for (seed in 1L..3L) {
                val (truth, corner) = lShape(v, r)
                val input = noisy(truth, seed)
                val out = TrackSmoother.smooth(input, cfg)
                val all = measure(truth, input, out)
                val near = measure(truth, input, out, (corner - 5)..(corner + 8))
                println(
                    "corner %.0f m/s r %.0f m seed %d: RMS raw %.2f → %.2f m; near the corner max raw %.2f → %.2f m (RMS %.2f → %.2f)".format(
                        v, r, seed, all.rmsRaw, all.rmsSmooth, near.maxRaw, near.maxSmooth, near.rmsRaw, near.rmsSmooth,
                    ),
                )
                assertTrue(all.rmsSmooth < all.rmsRaw * 0.85)
                // 角の近くのずれは生の点より大きくしない（丸めすぎない）
                assertTrue("corner RMS ${near.rmsSmooth} vs raw ${near.rmsRaw}", near.rmsSmooth <= near.rmsRaw * 1.0)
                assertTrue("corner max ${near.maxSmooth} vs raw ${near.maxRaw}", near.maxSmooth <= near.maxRaw * 1.1 + 0.5)
            }
        }
    }

    @Test
    fun arcIsFollowed() {
        for (seed in 1L..3L) {
            val truth = arc(120, 5.0, 30.0)
            val input = noisy(truth, seed)
            val m = measure(truth, input, TrackSmoother.smooth(input, cfg))
            println("arc r=30 m 5 m/s seed %d: RMS raw %.2f → %.2f m".format(seed, m.rmsRaw, m.rmsSmooth))
            assertTrue(m.rmsSmooth < m.rmsRaw * 0.85)
        }
    }

    @Test
    fun spikesAreRejectedAndJumpsRestart() {
        val truth = straight(120, 5.0)
        val input = noisy(truth, 5).toMutableList()
        // 80 m 横に飛んだ点が 2 つ（ビルの反射など）
        for (i in listOf(40, 80)) input[i] = input[i].copy(lon = input[i].lon + 80.0 / mLon)
        val out = TrackSmoother.smooth(input, cfg)
        assertTrue(out.rejected[40] && out.rejected[80])
        val m = measure(truth, input, out)
        println("spikes: max raw %.1f → %.1f m".format(m.maxRaw, m.maxSmooth))
        assertTrue("max ${m.maxSmooth}", m.maxSmooth < 8.0)
        // 本当に位置が変わった（測位し直して 200 m 先から続く）: 数点で付いていく
        val moved = noisy(straight(60, 5.0), 6) + noisy(straight(60, 5.0), 7).map { it.copy(timeMs = it.timeMs + 60_000, lat = it.lat + 200.0 / mLat) }
        val o2 = TrackSmoother.smooth(moved, cfg)
        val after = (65 until 120).maxOf { hypot((o2.lat[it] - moved[it].lat) * mLat, (o2.lon[it] - moved[it].lon) * mLon) }
        assertTrue("follows after the jump: $after m", after < 10.0)
    }

    @Test
    fun forwardFilterHasLittleLag() {
        // 記録中の線（前向きだけ）: 直線で生の点より真の位置に近く、遅れ（進む向きの平均のずれ）は 1 m 未満
        for (speed in listOf(5.0, 9.0)) {
            val truth = straight(180, speed)
            val input = noisy(truth, 9)
            val f = TrackSmoother.Forward(cfg)
            val out = input.map { f.add(it) }
            val errs = (10 until 180).map { i -> hypot((out[i].lon - lon0) * mLon - truth[i].first, (out[i].lat - lat0) * mLat - truth[i].second) }
            val raw = (10 until 180).map { i -> hypot((input[i].lon - lon0) * mLon - truth[i].first, (input[i].lat - lat0) * mLat - truth[i].second) }
            val along = (10 until 180).map { i -> ((out[i].lon - lon0) * mLon - truth[i].first) * 0.6 + ((out[i].lat - lat0) * mLat - truth[i].second) * 0.8 }.average()
            println("forward %.0f m/s: RMS raw %.2f → %.2f m, mean along-track %.2f m".format(speed, rms(raw), rms(errs), along))
            assertTrue(rms(errs) < rms(raw))
            assertEquals(0.0, along, 1.0)
        }
    }

    /** 保存した点の形（区間ごとになめらかにする・数と時刻と高度は変えない・距離が真の長さに近づく・GPX の位置の選択）。 */
    @Test
    fun storedPointsAreSmoothedPerSegmentAndDistanceShrinks() {
        val truth = straight(300, 5.0)
        val input = noisy(truth, 11)
        val pts = input.mapIndexed { i, p ->
            TrackPointEntity(
                id = i + 1L, trackId = 1, segment = if (i < 150) 0 else 1, time = p.timeMs, lat = p.lat, lon = p.lon, ele = 20.0,
                accuracy = p.accuracyM, deviceSpeed = 5f, speed = 5f,
            )
        }
        val sm = TrackRepository.smoothed(pts, SmoothConfig.BICYCLE)
        assertEquals(pts.size, sm.size)
        assertTrue(pts.indices.all { sm[it].time == pts[it].time && sm[it].ele == pts[it].ele && sm[it].segment == pts[it].segment && sm[it].id == pts[it].id })
        assertTrue(pts.indices.count { sm[it].lat != pts[it].lat } > 250)
        // 設定 OFF（null）なら記録のまま
        assertTrue(TrackRepository.smoothed(pts, null) === pts)
        // 距離（区間ごとに流し直す）: 生の点はギザギザの分だけ長い
        val raw = TrackRepository.statsOf(pts, autoPause = true, smooth = null)
        val smooth = TrackRepository.statsOf(pts, autoPause = true, smooth = SmoothConfig.BICYCLE)
        val trueLen = 5.0 * 298 // 区間の切れ目の 1 秒分は数えない
        println("distance: raw %.0f m → smoothed %.0f m (true %.0f m)".format(raw.distanceM, smooth.distanceM, trueLen))
        assertTrue(raw.distanceM > trueLen * 1.03)
        assertTrue(kotlin.math.abs(smooth.distanceM - trueLen) < kotlin.math.abs(raw.distanceM - trueLen))
        // 速度（端末の速度）・時間は変わらない
        assertEquals(raw.movingMs, smooth.movingMs)
        // 区間・分布の合計は、なめらかにした点の統計と一致する
        val a = TrackRepository.analyze(pts, autoPause = true, smooth = SmoothConfig.BICYCLE)
        assertEquals(smooth.distanceM, a.splits(1000.0).sumOf { it.distanceM }, 1e-6)
        assertEquals(smooth.movingMs, a.histogram().totalMs)
        // GPX: 記録した位置はそのまま、なめらかにした位置は設定によらず smoothed
        assertTrue(TrackRepository.pointsFor(pts, GpxSource.RECORDED) === pts)
        assertEquals(sm.map { it.lat }, TrackRepository.pointsFor(pts, GpxSource.SMOOTHED).map { it.lat })
    }
}
