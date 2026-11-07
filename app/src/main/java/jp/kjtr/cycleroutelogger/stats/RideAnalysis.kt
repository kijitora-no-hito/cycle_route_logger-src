package jp.kjtr.cycleroutelogger.stats

import jp.kjtr.cycleroutelogger.record.CountedStep
import jp.kjtr.cycleroutelogger.record.RideTracker
import jp.kjtr.cycleroutelogger.record.Sample
import jp.kjtr.cycleroutelogger.record.TrackerConfig
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * 区間（スプリット）1 つ。[startM]〜[endM] は記録の始めからの走行距離 [m]（一時停止の切れ目をまたいでも通して数える）。
 * [movingMs] はその区間の走行時間（自動一時停止・手動の一時停止の間は入らない）。
 */
data class Split(
    val index: Int,
    val startM: Double,
    val endM: Double,
    val movingMs: Long,
    /** 区間の中の最高速度（平滑化した速さ）[m/s]。 */
    val maxSpeedMps: Double,
    val ascentM: Double,
    /** 最後の端数の区間（[endM] − [startM] が区切りの長さに満たない）。 */
    val partial: Boolean,
) {
    val distanceM: Double get() = endM - startM
    val avgSpeedMps: Double get() = if (movingMs > 0) distanceM / (movingMs / 1000.0) else 0.0
}

/** 速度の bin 1 つ。[fromKmh] 以上 [toKmh] 未満。 */
data class SpeedBin(val fromKmh: Double, val toKmh: Double, val timeMs: Long, val distanceM: Double)

data class SpeedHistogram(
    val binKmh: Double,
    val bins: List<SpeedBin>,
    /** bin の時間の合計（= 走行時間）。 */
    val totalMs: Long,
    val totalM: Double,
) {
    val avgKmh: Double get() = if (totalMs > 0) totalM / (totalMs / 1000.0) * 3.6 else 0.0
}

/**
 * 記録の詳細の区間の表・速度の分布（純ロジック。単体テスト: RideAnalysisTest）。
 *
 * 保存した点を [RideTracker] に流し直し、走行時間・距離に数えた分（[CountedStep]）をそのまま使う。
 * こうすると、区間の時間の合計・分布の時間の合計が記録の走行時間に、区間の距離の合計が記録の距離にちょうど一致し、
 * 跳び・精度の悪い点・自動一時停止の扱いも記録の統計と同じになる。
 */
class RideAnalysis(
    val steps: List<CountedStep>,
    /** 獲得標高が増えたところ（その時点の走行距離 [m] と増えた分 [m]）。 */
    private val ascentEvents: List<Pair<Double, Double>>,
    val distanceM: Double,
    val movingMs: Long,
    val maxSpeedMps: Double,
    val ascentM: Double,
) {
    /**
     * [unitM] ごとの区間（1 km なら 1000）。区切りが点と点の間にあるときは、その間を距離で線形補間して境目の時刻を決める
     * （1 回分の時間を距離の割合で前後の区間に分ける）。距離が増えない間の時間（止まりかけ・3 m 未満の動き）はその時点の区間に入る。
     * 最後の端数（6.37 km なら 6–6.37 km）は [Split.partial]。距離がちょうど区切りで終わり、その後に時間だけ残った分は最後の区間に足す。
     */
    fun splits(unitM: Double): List<Split> {
        require(unitM > 0)
        val out = ArrayList<Split>()
        var d = 0.0
        var t = 0.0 // 走行時間の累計 [ms]（補間するので小数）
        var startM = 0.0
        var startT = 0.0
        var next = unitM
        var maxV = 0.0
        var any = false // この区間に何か入ったか

        fun close(endM: Double, endT: Double, partial: Boolean) {
            out.add(
                Split(
                    index = out.size,
                    startM = startM,
                    endM = endM,
                    // 境目の時刻を丸めてから引く（合計が走行時間にちょうど一致する）
                    movingMs = endT.roundToLong() - startT.roundToLong(),
                    maxSpeedMps = maxV,
                    ascentM = 0.0,
                    partial = partial,
                ),
            )
            startM = endM
            startT = endT
            maxV = 0.0
            any = false
        }

        for (s in steps) {
            var remD = s.distM
            var remT = s.dtMs.toDouble()
            // 区切りをまたぐ分を前の区間に入れて閉じる（1 回分で 2 つ以上またぐこともある）
            while (remD > 0 && d + remD >= next - EPS_M) {
                val part = (next - d).coerceIn(0.0, remD)
                val partT = remT * (part / remD)
                t += partT
                maxV = max(maxV, s.speedMps)
                close(next, t, partial = false)
                d = next
                remD -= part
                remT -= partT
                next += unitM
            }
            d += remD.coerceAtLeast(0.0)
            t += remT
            if (remD > 0 || remT > 0) {
                maxV = max(maxV, s.speedMps)
                any = true
            }
        }
        if (d - startM > EPS_M) {
            close(d, t, partial = true)
        } else if (any && out.isNotEmpty()) {
            // 区切りちょうどで距離が終わり、時間だけ残った → 最後の区間に足す
            val last = out.removeAt(out.size - 1)
            out.add(last.copy(movingMs = t.roundToLong() - (startT.roundToLong() - last.movingMs), maxSpeedMps = max(last.maxSpeedMps, maxV)))
        }
        if (out.isEmpty()) return out
        // 獲得標高はその時点の走行距離の区間に入れる（ちょうど区切りなら前の区間）
        val asc = DoubleArray(out.size)
        for ((at, gain) in ascentEvents) {
            var i = (kotlin.math.ceil(at / unitM - 1e-9).toInt() - 1).coerceAtLeast(0)
            if (i >= out.size) i = out.size - 1
            asc[i] += gain
        }
        return out.mapIndexed { i, sp -> sp.copy(ascentM = asc[i]) }
    }

    /**
     * 速度の分布。[binKmh]（既定 2 km/h）ごとの bin に、数えた 1 回分の時間・距離をその点の速さの bin に足す。
     * bin は下端を含み上端を含まない（ちょうど 2.0 km/h は 2–4）。0 から、数えた速さの最高を含む bin まで並べる（間の空の bin も出す）。
     * 自動一時停止中・手動の一時停止中は数えないので入らない（自動一時停止 OFF の記録では止まっていた時間も 0–2 に入る）。
     */
    fun histogram(binKmh: Double = 2.0): SpeedHistogram {
        require(binKmh > 0)
        val time = HashMap<Int, Long>()
        val dist = HashMap<Int, Double>()
        var top = -1
        var totalMs = 0L
        var totalM = 0.0
        for (s in steps) {
            val i = binIndex(s.speedMps * 3.6, binKmh)
            time[i] = (time[i] ?: 0L) + s.dtMs
            dist[i] = (dist[i] ?: 0.0) + s.distM
            top = max(top, i)
            totalMs += s.dtMs
            totalM += s.distM
        }
        val bins = (0..top).map { i -> SpeedBin(i * binKmh, (i + 1) * binKmh, time[i] ?: 0L, dist[i] ?: 0.0) }
        return SpeedHistogram(binKmh, bins, totalMs, totalM)
    }

    companion object {
        private const val EPS_M = 1e-6

        /** 速さ [km/h] の bin の番号（下端を含む。m/s → km/h の丸め誤差でちょうどの値が下に落ちないよう少し足す）。 */
        fun binIndex(kmh: Double, binKmh: Double): Int = floor(kmh / binKmh + 1e-9).toInt().coerceAtLeast(0)

        /**
         * 区間（一時停止・跳びの切れ目）ごとの点から作る。区間の間で [RideTracker.breakSegment] を呼ぶ
         * （`TrackRepository.replay` と同じ流し方なので、統計は記録と同じになる）。
         * 獲得標高（0.9.0〜）は [ElevationGain]: [gains]（記録した点から数えたもの。null なら [segments] から数える）を、その時刻の走行距離の区間に入れる。
         */
        fun from(segments: List<List<Sample>>, autoPause: Boolean, gains: List<ElevationGain.Gain>? = null): RideAnalysis {
            val tr = RideTracker(TrackerConfig(autoPause = autoPause))
            val steps = ArrayList<CountedStep>()
            // 各点の時刻とその時点の走行距離（登りをどの区間に入れるか）
            val times = ArrayList<Long>()
            val dists = ArrayList<Double>()
            var dist = 0.0
            tr.onCounted = { steps.add(it); dist += it.distM }
            segments.forEachIndexed { k, seg ->
                if (k > 0) tr.breakSegment()
                for (s in seg) {
                    tr.feed(s)
                    times.add(s.timeMs)
                    dists.add(dist)
                }
            }
            val g = gains ?: ElevationGain.gains(segments)
            val ascents = g.map { gain ->
                // その時刻（以前で最後）の点の走行距離
                var i = times.binarySearch(gain.timeMs)
                if (i < 0) i = (-i - 2).coerceAtLeast(0)
                (if (dists.isEmpty()) 0.0 else dists[i.coerceAtMost(dists.size - 1)]) to gain.gainM
            }
            return RideAnalysis(steps, ascents, tr.distanceM, tr.movingMs, tr.maxSpeedMps, g.sumOf { it.gainM })
        }

        /** 一番速い・遅い区間の番号（端数の区間は除く。比べる区間が 2 つ未満なら null）。 */
        fun extremes(splits: List<Split>): Pair<Int, Int>? {
            val full = splits.filter { !it.partial && it.movingMs > 0 }
            if (full.size < 2) return null
            val fast = full.maxBy { it.avgSpeedMps }.index
            val slow = full.minBy { it.avgSpeedMps }.index
            return if (fast == slow) null else fast to slow
        }

        /** 目盛りの間隔（1・2・5 × 10^n のうち、[maxValue] を [maxTicks] 本以内で刻めるもの）。 */
        fun niceStep(maxValue: Double, maxTicks: Int = 4): Double {
            if (maxValue <= 0) return 1.0
            var mag = 1.0
            while (mag * 10 <= maxValue) mag *= 10
            while (mag > maxValue) mag /= 10
            for (m in listOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0)) {
                val step = mag * m
                if (maxValue / step <= maxTicks) return step
            }
            return mag * 10
        }
    }
}
