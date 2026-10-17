package jp.kjtr.cycleroutelogger.record

import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.stats.ElevationGain
import kotlin.math.max

/** 測位 1 回分（記録・再計算の入力）。 */
data class Sample(
    /** UTC の時刻 [ms]（Location.time）。 */
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val altitudeM: Double? = null,
    /** 端末が出した速度（GPS のドップラー）[m/s]。無ければ null。 */
    val speedMps: Float? = null,
    /** 高度の精度 [m]（0.9.0〜。獲得標高で使う）。無ければ null。 */
    val altAccuracyM: Float? = null,
    /** 測位の出どころ（[jp.kjtr.cycleroutelogger.data.TrackPointEntity.PROVIDER_GPS] など。0.9.0〜）。 */
    val provider: Int = 0,
)

data class TrackerConfig(
    val autoPause: Boolean = true,
    /** これより精度の悪い点は捨てる [m]。 */
    val maxAccuracyM: Float = 30f,
    /** 前の点からこれより速く跳んだ点は捨てる [m/s]（100 km/h）。 */
    val maxSpeedMps: Double = 100.0 / 3.6,
    /** この速さ未満を「止まっている」とみなす [m/s]（3 km/h）。 */
    val stillSpeedMps: Double = 3.0 / 3.6,
    /** 止まってからこの時間で自動一時停止にする [ms]。 */
    val autoPauseDelayMs: Long = 5_000,
    /** 距離はこの長さ以上動いたときにまとめて足す（止まっているときの GPS の揺れを数えない）[m]。 */
    val minStepM: Double = 3.0,
    /** 跳びとして捨てた点がこれだけ続いたら、そこから測り直す（最初の点が外れ値だったとき用）。 */
    val jumpResetCount: Int = 5,
)

/**
 * 走行時間・距離に数えた 1 回分（点と点の間）。[RideTracker.onCounted] に、数えた順に渡す。
 * すべての [dtMs] の和が [RideTracker.movingMs]、[distM] の和が [RideTracker.distanceM] にちょうど一致する。
 */
data class CountedStep(
    /** この間の終わりの点の時刻 [ms]。 */
    val timeMs: Long,
    /** 数えた時間 [ms]。 */
    val dtMs: Long,
    /** 数えた距離 [m]（3 m 未満の動きは次にまとめて足すので 0 のこともある）。 */
    val distM: Double,
    /** この点での速さ（直近 3 回の中央値）[m/s]。 */
    val speedMps: Double,
)

/**
 * 記録中の統計（純ロジック。単体テスト: RideTrackerTest）。
 *
 * - 精度 [TrackerConfig.maxAccuracyM] 超・前の点から 100 km/h 超の跳び・時刻が戻った点は捨てる。
 * - 距離: 採用点（anchor）から [TrackerConfig.minStepM] 以上動いたら足して anchor を進める。
 * - 速さ: 端末の速度（GPS のドップラー）、無ければ 5 秒前の点からの移動距離÷時間。直近 3 回の中央値。
 * - 自動一時停止: 速さが 3 km/h 未満のまま 5 秒たったら、止まり始めからの時間と距離を数えない。
 *   動き出したら、止まった地点（anchor）からの距離を足して再開。
 * - 獲得標高（記録中の表示用）: [ElevationGain.Live]（精度の悪い高度・ネットワーク位置を除き、中央値と後ろ向きの移動平均でならして 10 m 以上の登りだけ。
 *   0.8.0 までは指数移動平均＋4 m で、GPS の高度の揺れを登りとして数えていた）。記録を終えたら [ElevationGain.of]（前後の窓）で数え直して保存する。
 * - 手動の一時停止の後は [breakSegment] で区間を切る（間の時間・距離は数えない）。
 *
 * 保存した点（採用された点だけ）を同じ順に流し直すと同じ統計になる（記録の途中で落ちたときの再開用）。
 * そのため「跳びが続いて測り直した」点は [Result.newSegment] を返し、呼び出し側が区間を切って保存する。
 */
class RideTracker(private val config: TrackerConfig = TrackerConfig()) {

    enum class Reject { INACCURATE, DUPLICATE, JUMP }

    sealed interface Result {
        data class Accepted(val newSegment: Boolean, val smoothedSpeedMps: Double) : Result
        data class Rejected(val reason: Reject) : Result
    }

    var distanceM = 0.0
        private set
    var movingMs = 0L
        private set
    var maxSpeedMps = 0.0
        private set
    /** 直近の速さ（中央値）[m/s]。 */
    var currentSpeedMps = 0.0
        private set
    var autoPaused = false
        private set
    var acceptedCount = 0
        private set
    var lastAccepted: Sample? = null
        private set

    val ascentM: Double get() = ascent.ascentM

    /**
     * 走行時間・距離に数えた分を受け取る（記録の詳細の区間・速度の分布用。stats/RideAnalysis）。null なら何もしない。
     * 止まりかけ（自動一時停止になる前）の分は、動き出して数えると決まったときにまとめて渡し、自動一時停止になったら捨てる。
     */
    var onCounted: ((CountedStep) -> Unit)? = null

    private val ascent = ElevationGain.Live()
    private val pendingSteps = ArrayList<CountedStep>()
    private var anchor: Sample? = null
    private var lastMovingAt = 0L
    private var pendingMs = 0L
    private var pendingM = 0.0
    private var jumpStreak = 0
    private val speeds = ArrayDeque<Double>()
    private val recent = ArrayDeque<Sample>()

    fun feed(s: Sample): Result {
        val acc = s.accuracyM
        if (acc != null && acc > config.maxAccuracyM) return Result.Rejected(Reject.INACCURATE)
        val last = lastAccepted
        if (last == null) {
            startFrom(s)
            return Result.Accepted(newSegment = true, smoothedSpeedMps = 0.0)
        }
        val dtMs = s.timeMs - last.timeMs
        if (dtMs <= 0) return Result.Rejected(Reject.DUPLICATE)
        val d = Geo.distanceM(last.lat, last.lon, s.lat, s.lon)
        val v = d / (dtMs / 1000.0)
        if (v > config.maxSpeedMps) {
            jumpStreak++
            if (jumpStreak >= config.jumpResetCount) {
                // 前の点の方が外れ値だった。ここから測り直す（間の距離・時間は数えない）。
                startFrom(s)
                return Result.Accepted(newSegment = true, smoothedSpeedMps = 0.0)
            }
            return Result.Rejected(Reject.JUMP)
        }
        jumpStreak = 0
        acceptedCount++

        // 端末の速度が無ければ、数秒前の点からの移動距離で測る（1 秒ごとの位置の揺れを速さと取り違えない）
        recent.addLast(s)
        while (recent.size > 2 && s.timeMs - recent[1].timeMs >= SPEED_BASELINE_MS) recent.removeFirst()
        val base = recent.first()
        val windowed = if (base !== s && s.timeMs > base.timeMs) {
            Geo.distanceM(base.lat, base.lon, s.lat, s.lon) / ((s.timeMs - base.timeMs) / 1000.0)
        } else {
            v
        }
        val device = s.speedMps?.toDouble()?.takeIf { it >= 0.0 && it <= config.maxSpeedMps }
        // 端末が「止まっている（0）」と言っていても、5 秒で 10 m 以上動いていれば位置の方を信じる
        // （速度を 0 のまま返す端末・エミュレータの geo fix がある）
        val inst = if (device == null || (device < config.stillSpeedMps && windowed >= BOGUS_ZERO_MPS)) windowed else device
        speeds.addLast(inst)
        while (speeds.size > SPEED_WINDOW) speeds.removeFirst()
        currentSpeedMps = median(speeds)
        val moving = currentSpeedMps >= config.stillSpeedMps

        val listener = onCounted
        if (!config.autoPause) {
            movingMs += dtMs
            val step = takeStep(s)
            distanceM += step
            if (moving) maxSpeedMps = max(maxSpeedMps, currentSpeedMps)
            listener?.invoke(CountedStep(s.timeMs, dtMs, step, currentSpeedMps))
        } else if (moving) {
            if (autoPaused) {
                // 再開: 止まった地点（anchor）から今までの距離を足す。止まっていた時間は数えない。
                autoPaused = false
                movingMs += dtMs
            } else {
                movingMs += pendingMs + dtMs
                distanceM += pendingM
                if (listener != null) pendingSteps.forEach(listener)
            }
            pendingSteps.clear()
            val step = takeStep(s)
            distanceM += step
            pendingMs = 0
            pendingM = 0.0
            lastMovingAt = s.timeMs
            maxSpeedMps = max(maxSpeedMps, currentSpeedMps)
            listener?.invoke(CountedStep(s.timeMs, dtMs, step, currentSpeedMps))
        } else if (!autoPaused) {
            pendingMs += dtMs
            val step = takeStep(s)
            pendingM += step
            if (listener != null) pendingSteps.add(CountedStep(s.timeMs, dtMs, step, currentSpeedMps))
            if (s.timeMs - lastMovingAt >= config.autoPauseDelayMs) {
                autoPaused = true
                pendingMs = 0
                pendingM = 0.0
                pendingSteps.clear()
            }
        }
        // 自動一時停止中は anchor を動かさない（止まっている間の揺れを足さない）

        ascent.add(s)
        lastAccepted = s
        return Result.Accepted(newSegment = false, smoothedSpeedMps = currentSpeedMps)
    }

    /** anchor から [TrackerConfig.minStepM] 以上離れていればその距離を返して anchor を進める。 */
    private fun takeStep(s: Sample): Double {
        val a = anchor ?: run { anchor = s; return 0.0 }
        val step = Geo.distanceM(a.lat, a.lon, s.lat, s.lon)
        if (step < config.minStepM) return 0.0
        anchor = s
        return step
    }

    private fun startFrom(s: Sample) {
        lastAccepted = s
        anchor = s
        lastMovingAt = s.timeMs
        pendingMs = 0
        pendingM = 0.0
        pendingSteps.clear()
        jumpStreak = 0
        autoPaused = false
        speeds.clear()
        recent.clear()
        recent.addLast(s)
        currentSpeedMps = 0.0
        ascent.breakSegment()
        ascent.add(s)
        acceptedCount++
    }

    /** 手動の一時停止 → 再開。次の点から新しい区間として測る。 */
    fun breakSegment() {
        lastAccepted = null
        anchor = null
        pendingMs = 0
        pendingM = 0.0
        pendingSteps.clear()
        jumpStreak = 0
        autoPaused = false
        speeds.clear()
        recent.clear()
        currentSpeedMps = 0.0
        ascent.breakSegment()
    }

    /** 最後の点から時間がたって速さが分からないとき（表示用）。 */
    fun speedAt(nowMs: Long): Double {
        val last = lastAccepted ?: return 0.0
        return if (nowMs - last.timeMs > STALE_MS) 0.0 else currentSpeedMps
    }

    val averageSpeedMps: Double get() = if (movingMs > 0) distanceM / (movingMs / 1000.0) else 0.0

    companion object {
        /** 速さの中央値を取る点の数。 */
        const val SPEED_WINDOW = 3
        /** 位置から速さを測るときの基準点（この時間だけ前の点）[ms]。 */
        const val SPEED_BASELINE_MS = 5_000L
        const val STALE_MS = 5_000L
        /** 端末の速度が 0 でも、位置からの速さがこれ以上なら動いているとみなす [m/s]（7.2 km/h）。 */
        const val BOGUS_ZERO_MPS = 2.0

        fun median(values: Collection<Double>): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val n = sorted.size
            return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        }
    }
}
