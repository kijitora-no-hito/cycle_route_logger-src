package jp.kjtr.cycleroutelogger.stats

import jp.kjtr.cycleroutelogger.data.TrackPointEntity
import jp.kjtr.cycleroutelogger.data.TrackRepository
import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.record.Sample

/**
 * 獲得標高（登りの合計。純ロジック。単体テスト: ElevationGainTest）。0.9.0〜 記録の詳細・区間・まとめ・SNS 用の画像はすべてこれで数えた値
 * （記録を終えたとき・0.8.0 から上書きした最初の起動で `track.ascentM` に入れ直す）。ドライブロガー 0.8.0 の stats/ElevationGain の移植。
 *
 * 1. 使う点（[usable]）: 高度があり、ネットワーク位置ではなく、高度の精度（verticalAccuracy）が [MAX_ALT_ACCURACY_M] 以内。
 *    高度の精度が無い点（0.8.0 までの記録・Android 7 の端末など）は水平の精度が [MAX_H_ACCURACY_M] 以内（精度も無い＝バックアップから戻した点は使う）。
 * 2. ならし: 区間ごとに、前後 2 点ずつの中央値（跳びを消す）→ 前後 [HALF_WINDOW_MS] かつ走った距離で前後 [HALF_WINDOW_M] の移動平均
 *    （ゆっくり走ると時間で、速く下ると距離で窓が決まる）。
 * 3. 数え方（ヒステリシス）: 直近の底から [THRESHOLD_M] 以上上がったときだけその分を足し、そこを新しい基準にする。下がったら底を下げる。
 *    GPS の高度の数 m の揺れは数えない。10 m 未満の小さな上り下りも数えない。
 *
 * 0.8.0 までの数え方（指数移動平均 α 0.3＋直近の底から 4 m）は、GPS の高度の雑音（σ 数 m）を何度も登りとして数え、平地でも 1 時間で数百 m 出ていた
 * （ドライブロガーで報告された 671 m と同じ問題）。
 *
 * 限界: GPS の高度は水平より 1.5〜2 倍悪く、数分かけてゆっくり 10 m 前後ずれることがある。平地でも数 m〜十数 m 出ることがある（目安）。
 * 区間（一時停止・跳びの切れ目）の初めと終わりでは窓が片側だけになるので、短い区間の登りは少なめに出る。
 */
object ElevationGain {
    const val THRESHOLD_M = 10.0
    const val HALF_WINDOW_MS = 60_000L
    const val HALF_WINDOW_M = 300.0
    const val MAX_ALT_ACCURACY_M = 15f
    const val MAX_H_ACCURACY_M = 20f

    /** 高度の 1 点（[distM] = 区間の初めからの距離、[timeMs] = 時刻）。 */
    data class P(val timeMs: Long, val ele: Double, val distM: Double = 0.0)

    /** 登りが増えたところ（その点の時刻と増えた分 [m]）。区間の表で「どの区間の登りか」に使う。 */
    data class Gain(val timeMs: Long, val gainM: Double)

    /** 保存した点（id の順）から獲得標高 [m]。区間の境目では数え直す（間の上り下りは数えない）。 */
    fun of(points: List<TrackPointEntity>): Double = gainsOf(points).sumOf { it.gainM }

    /** 保存した点から、登りが増えたところ（時刻の順）。 */
    fun gainsOf(points: List<TrackPointEntity>): List<Gain> =
        gains(TrackRepository.segmentsOf(points).map { s -> s.map { TrackRepository.sampleOf(it) } })

    /** 区間ごとの測位から、登りが増えたところ。 */
    fun gains(segments: List<List<Sample>>): List<Gain> {
        val out = ArrayList<Gain>()
        for (seg in segments) {
            var d = 0.0
            val pts = ArrayList<P>()
            for (i in seg.indices) {
                if (i > 0) d += Geo.distanceM(seg[i - 1].lat, seg[i - 1].lon, seg[i].lat, seg[i].lon)
                val s = seg[i]
                if (usable(s)) pts.add(P(s.timeMs, s.altitudeM!!, d))
            }
            gainsOfSegment(pts).forEach { (i, g) -> out.add(Gain(pts[i].timeMs, g)) }
        }
        return out
    }

    /** 獲得標高に使える点か。 */
    fun usable(s: Sample): Boolean = usable(s.altitudeM, s.accuracyM, s.altAccuracyM, s.provider)

    fun usable(ele: Double?, accuracy: Float?, altAccuracy: Float?, provider: Int): Boolean {
        val e = ele ?: return false
        if (e.isNaN() || e.isInfinite()) return false
        if (provider == TrackPointEntity.PROVIDER_NETWORK) return false
        if (altAccuracy != null && altAccuracy > 0f) return altAccuracy <= MAX_ALT_ACCURACY_M
        // 高度の精度が無い（0.8.0 までの点など）: 水平の精度で代える
        val h = accuracy ?: return true
        return h <= MAX_H_ACCURACY_M
    }

    /** 1 区間（時刻の順）の獲得標高。 */
    fun ofSegment(points: List<P>, threshold: Double = THRESHOLD_M, halfWindowMs: Long = HALF_WINDOW_MS): Double =
        gainsOfSegment(points, threshold, halfWindowMs).sumOf { it.second }

    /** 1 区間の、登りを足した点の添字と増えた分。 */
    fun gainsOfSegment(points: List<P>, threshold: Double = THRESHOLD_M, halfWindowMs: Long = HALF_WINDOW_MS): List<Pair<Int, Double>> {
        if (points.size < 2) return emptyList()
        val smooth = smooth(points, halfWindowMs)
        val out = ArrayList<Pair<Int, Double>>()
        var ref = smooth[0]
        for (i in 1 until smooth.size) {
            val s = smooth[i]
            if (s - ref >= threshold) {
                out.add(i to s - ref)
                ref = s
            } else if (s < ref) {
                ref = s
            }
        }
        return out
    }

    /** 中央値（前後 2 点）→ 時間と距離の窓の移動平均（前後 [halfWindowMs]・[HALF_WINDOW_M]）。 */
    fun smooth(points: List<P>, halfWindowMs: Long = HALF_WINDOW_MS): DoubleArray {
        val n = points.size
        val med = DoubleArray(n) { i ->
            val a = maxOf(0, i - 2)
            val b = minOf(n - 1, i + 2)
            val w = (a..b).map { points[it].ele }.sorted()
            w[w.size / 2]
        }
        val out = DoubleArray(n)
        var lo = 0
        var hi = -1
        var sum = 0.0
        for (i in 0 until n) {
            val t = points[i].timeMs
            val d = points[i].distM
            // 時刻も距離も増えるだけなので、窓は連続した範囲
            while (hi + 1 < n && points[hi + 1].timeMs <= t + halfWindowMs && points[hi + 1].distM <= d + HALF_WINDOW_M) { hi++; sum += med[hi] }
            while (points[lo].timeMs < t - halfWindowMs || points[lo].distM < d - HALF_WINDOW_M) { sum -= med[lo]; lo++ }
            out[i] = sum / (hi - lo + 1)
        }
        return out
    }

    /**
     * 記録中の表示用（前向きだけ。[jp.kjtr.cycleroutelogger.record.RideTracker] が持つ）。使う点・中央値・ヒステリシスは [of] と同じで、
     * 中央値は直近 5 点、移動平均は後ろ向きの窓（直近 2 × [HALF_WINDOW_MS]・2 × [HALF_WINDOW_M]。[of] の前後の窓と同じ幅＝同じ点の数でならす）。
     * 後ろ向きなので登りは 1 分ほど遅れて増え、
     * 記録を終えて [of] で数え直すと少し変わることがある（区間の初めと終わりの扱いが違う）。
     */
    class Live {
        var ascentM = 0.0
            private set
        private val recent = ArrayDeque<Double>()
        private val window = ArrayDeque<P>()
        private var sum = 0.0
        private var ref: Double? = null
        private var dist = 0.0
        private var last: Sample? = null

        fun add(s: Sample) {
            last?.let { dist += Geo.distanceM(it.lat, it.lon, s.lat, s.lon) }
            last = s
            if (!usable(s)) return
            recent.addLast(s.altitudeM!!)
            while (recent.size > 5) recent.removeFirst()
            val med = recent.sorted()[recent.size / 2]
            window.addLast(P(s.timeMs, med, dist))
            sum += med
            while (window.first().timeMs < s.timeMs - 2 * HALF_WINDOW_MS || window.first().distM < dist - 2 * HALF_WINDOW_M) sum -= window.removeFirst().ele
            val v = sum / window.size
            val r = ref
            if (r == null) {
                ref = v
            } else if (v - r >= THRESHOLD_M) {
                ascentM += v - r
                ref = v
            } else if (v < r) {
                ref = v
            }
        }

        /** 区間の切れ目（一時停止の後など）。合計は残し、ならしと基準だけやり直す。 */
        fun breakSegment() {
            recent.clear()
            window.clear()
            sum = 0.0
            ref = null
            dist = 0.0
            last = null
        }
    }
}
