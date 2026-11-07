package jp.kjtr.cycleroutelogger.importer

/**
 * パーサから来た点を [store] に書き、続いた線（[RunInfo]）にまとめる。
 *
 * - 線を切る: パーサの区切り（[beginRun]）、ファイルの境目、[DayPlanner.GAP_SPLIT_MS]（10 分）以上の時間の空き。
 * - 捨てる点: 緯度経度が範囲外・(0, 0)、2000 年より前・[nowMs] の 1 日後より後の時刻（時計の合っていない機器）、前の点より時刻が戻る・同じ時刻の点。
 * - 2 点に満たない線は使わない。
 */
class RunCollector(
    private val store: PointStore,
    private val nowMs: Long = System.currentTimeMillis(),
    /** 4096 点ごとに呼ぶ（キャンセルの確認。投げればそこで止まる）。 */
    private val check: () -> Unit = {},
) : PointSink {
    val runs = ArrayList<RunInfo>()
    /** 書いた点の数（進み具合の表示）。 */
    var totalPoints = 0L
        private set

    private var file = -1
    private var format = ActivityFormat.GPX
    private var fileSport = Sport.UNKNOWN
    private var fileRunsFrom = 0
    private var filePoints = 0

    private var runFirst = -1L
    private var runCount = 0
    private var runStart = 0L
    private var runLast = 0L

    fun startFile(index: Int, format: ActivityFormat) {
        closeRun()
        file = index
        this.format = format
        fileSport = Sport.UNKNOWN
        fileRunsFrom = runs.size
        filePoints = 0
    }

    /** ファイルを読み終えた。戻り値はこのファイルで線に入った点の数。 */
    fun endFile(): Int {
        closeRun()
        if (fileSport != Sport.UNKNOWN) {
            for (i in fileRunsFrom until runs.size) runs[i] = runs[i].copy(sport = fileSport)
        }
        return filePoints
    }

    override fun sport(s: Sport) {
        if (s != Sport.UNKNOWN) fileSport = s
    }

    override fun beginRun() = closeRun()

    override fun point(timeMs: Long, lat: Double, lon: Double, ele: Double?, speedMps: Float?) {
        if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        if (lat == 0.0 && lon == 0.0) return
        if (timeMs < MIN_TIME_MS || timeMs > nowMs + 86_400_000L) return
        if (runCount > 0) {
            if (timeMs <= runLast) return
            if (timeMs - runLast >= DayPlanner.GAP_SPLIT_MS) closeRun()
        }
        if (runCount == 0) {
            runFirst = store.size
            runStart = timeMs
        }
        store.append(timeMs, lat, lon, ele?.takeIf { it.isFinite() }, speedMps?.takeIf { it.isFinite() && it >= 0f })
        runCount++
        runLast = timeMs
        filePoints++
        totalPoints++
        if (totalPoints % 4096 == 0L) check()
    }

    private fun closeRun() {
        if (runCount >= 2) {
            runs.add(RunInfo(runs.size, file, format, fileSport, runFirst, runCount, runStart, runLast))
        } else if (runCount == 1) {
            filePoints--
        }
        runCount = 0
    }

    companion object {
        /** 2000-01-01T00:00:00Z。これより前の時刻は機器の時計が合っていないとみなす。 */
        const val MIN_TIME_MS = 946_684_800_000L
    }
}
