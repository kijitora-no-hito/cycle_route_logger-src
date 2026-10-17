package jp.kjtr.cycleroutelogger.record

import android.location.Location
import jp.kjtr.cycleroutelogger.data.AppDatabase
import jp.kjtr.cycleroutelogger.data.TrackEntity
import jp.kjtr.cycleroutelogger.data.TrackEventEntity
import jp.kjtr.cycleroutelogger.data.TrackPointEntity
import jp.kjtr.cycleroutelogger.data.TrackRepository
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.geo.SmoothConfig
import jp.kjtr.cycleroutelogger.geo.TrackSmoother
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 記録中の表示用の状態。 */
data class RecordingState(
    val trackId: Long,
    val name: String,
    val paused: Boolean,
    val startedAt: Long,
    val mode: String,
    val planId: Long?,
    val distanceM: Double = 0.0,
    val movingMs: Long = 0,
    val maxSpeedMps: Double = 0.0,
    val averageSpeedMps: Double = 0.0,
    val currentSpeedMps: Double = 0.0,
    val ascentM: Double = 0.0,
    val autoPaused: Boolean = false,
    val pointCount: Int = 0,
    val lastFixAt: Long? = null,
)

/**
 * 記録 1 本の進行（アプリ全体で 1 つ）。位置は [RecordingService] が [onLocation] に渡す（メインスレッド）。
 *
 * 採用した点はその場で Room に書く（書き込みは 1 本のキューで順番に）。統計は 10 点ごとと、一時停止・終了で保存する。
 * アプリやサービスが落ちても点は残り、[resumeUnfinished] で点を流し直して続きから記録できる。
 */
class RecordingSession(private val db: AppDatabase, private val scope: CoroutineScope) {
    private val dao = db.tracks()

    private val _state = MutableStateFlow<RecordingState?>(null)
    val state: StateFlow<RecordingState?> = _state

    private val _line = MutableStateFlow<List<List<LatLon>>>(emptyList())
    /** 記録中の軌跡（区間ごと）。 */
    val line: StateFlow<List<List<LatLon>>> = _line

    private var tracker = RideTracker()
    private var entity: TrackEntity? = null
    private var segment = 0
    private var segmentHasPoints = false
    private var sinceSave = 0

    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /**
     * 記録中の線を前向きだけのフィルタでならす（0.9.0〜 設定「軌跡をなめらかにする」。遅れが出ないよう後ろからは戻さない。
     * 記録した点・記録中の統計は測位のまま。終えたら記録全体を前後からならして距離を計算し直す）。[jp.kjtr.cycleroutelogger.CycleApp] が設定から入れる。
     */
    var smoothingEnabled: () -> Boolean = { false }
    private var liveSmoother: TrackSmoother.Forward? = null

    /** 続いている出来事（位置情報 OFF・位置が届かない）。 */
    private val openConditions = HashSet<String>()

    init {
        scope.launch(Dispatchers.IO) {
            for (w in writes) runCatching { w() }
        }
    }

    val isActive: Boolean get() = _state.value != null

    /** 新しい記録を始める（DB に行を作る）。 */
    suspend fun start(name: String, mode: String, planId: Long?, planName: String?, autoPause: Boolean): Long {
        val now = System.currentTimeMillis()
        val t = TrackEntity(
            name = name, startedAt = now, status = TrackEntity.STATUS_RECORDING,
            mode = mode, planId = planId, planName = planName, autoPause = autoPause,
        )
        val id = withContext(Dispatchers.IO) { dao.insertTrack(t) }
        withContext(Dispatchers.Main) {
            entity = t.copy(id = id)
            tracker = RideTracker(TrackerConfig(autoPause = autoPause))
            segment = 0
            segmentHasPoints = false
            sinceSave = 0
            liveSmoother = null
            _line.value = emptyList()
            publish()
        }
        return id
    }

    /**
     * 途中で終わった記録を続ける（保存済みの点を流し直す）。新しい区間から記録する。
     * [gapType]（[TrackEventEntity.TYPE_RESTARTED] など）があれば、最後の点から今までを出来事として残す。
     */
    suspend fun resumeUnfinished(trackId: Long, gapType: String? = null): Boolean {
        val t = withContext(Dispatchers.IO) { dao.trackNow(trackId) } ?: return false
        val pts = withContext(Dispatchers.IO) { dao.points(trackId) }
        if (gapType != null) {
            val from = pts.lastOrNull()?.time ?: t.startedAt
            withContext(Dispatchers.IO) { dao.insertEvent(TrackEventEntity(trackId = trackId, type = gapType, startedAt = from, endedAt = System.currentTimeMillis())) }
        }
        withContext(Dispatchers.Main) {
            tracker = TrackRepository.replay(pts, t.autoPause)
            tracker.breakSegment()
            entity = t.copy(status = TrackEntity.STATUS_RECORDING)
            segment = (pts.maxOfOrNull { it.segment } ?: -1) + 1
            segmentHasPoints = false
            sinceSave = 0
            openConditions.clear()
            liveSmoother = null
            val shown = TrackRepository.smoothed(pts, if (smoothingEnabled()) SmoothConfig.BICYCLE else null)
            _line.value = TrackRepository.segmentsOf(shown).map { s -> s.map { LatLon(it.lat, it.lon) } }
            publish()
            saveEntity()
        }
        return true
    }

    fun onLocation(l: Location) {
        val e = entity ?: return
        if (e.status != TrackEntity.STATUS_RECORDING) return
        val s = Sample(
            timeMs = l.time,
            lat = l.latitude,
            lon = l.longitude,
            accuracyM = if (l.hasAccuracy()) l.accuracy else null,
            altitudeM = if (l.hasAltitude()) l.altitude else null,
            speedMps = if (l.hasSpeed()) l.speed else null,
            altAccuracyM = if (androidx.core.location.LocationCompat.hasVerticalAccuracy(l)) androidx.core.location.LocationCompat.getVerticalAccuracyMeters(l) else null,
            provider = TrackPointEntity.providerOf(l.provider),
        )
        when (val r = tracker.feed(s)) {
            is RideTracker.Result.Rejected -> publish()
            is RideTracker.Result.Accepted -> {
                if (r.newSegment && segmentHasPoints) {
                    segment++
                    segmentHasPoints = false
                }
                val seg = segment
                val newSeg = !segmentHasPoints
                segmentHasPoints = true
                val p = TrackPointEntity(
                    trackId = e.id, segment = seg, time = s.timeMs, lat = s.lat, lon = s.lon, ele = s.altitudeM,
                    accuracy = s.accuracyM, deviceSpeed = s.speedMps, speed = r.smoothedSpeedMps.toFloat(),
                    altAccuracy = s.altAccuracyM, provider = s.provider,
                )
                writes.trySend { dao.insertPoint(p) }
                if (newSeg) liveSmoother?.reset()
                val ll = if (smoothingEnabled()) {
                    val f = liveSmoother ?: TrackSmoother.Forward(SmoothConfig.BICYCLE).also { liveSmoother = it }
                    f.add(TrackSmoother.In(s.timeMs, s.lat, s.lon, s.accuracyM))
                } else {
                    LatLon(s.lat, s.lon)
                }
                val cur = _line.value
                _line.value = if (newSeg || cur.isEmpty()) cur + listOf(listOf(ll)) else cur.dropLast(1) + listOf(cur.last() + ll)
                if (++sinceSave >= 10) {
                    sinceSave = 0
                    saveEntity()
                }
                publish()
            }
        }
    }

    fun pause() {
        val e = entity ?: return
        if (e.status != TrackEntity.STATUS_RECORDING) return
        entity = e.copy(status = TrackEntity.STATUS_PAUSED)
        tracker.breakSegment()
        if (segmentHasPoints) {
            segment++
            segmentHasPoints = false
        }
        saveEntity()
        publish()
    }

    fun resume() {
        val e = entity ?: return
        if (e.status != TrackEntity.STATUS_PAUSED) return
        entity = e.copy(status = TrackEntity.STATUS_RECORDING)
        tracker.breakSegment()
        saveEntity()
        publish()
    }

    /**
     * 記録中の端末の状態（[TrackEventEntity.TYPE_LOCATION_OFF] / [TrackEventEntity.TYPE_NO_FIX]）が始まった・終わった。
     * 記録に出来事として残す（メインスレッドから呼ぶ）。
     */
    fun setCondition(type: String, active: Boolean) {
        val e = entity ?: return
        val now = System.currentTimeMillis()
        if (active) {
            if (!openConditions.add(type)) return
            writes.trySend { dao.insertEvent(TrackEventEntity(trackId = e.id, type = type, startedAt = now)) }
        } else {
            if (!openConditions.remove(type)) return
            writes.trySend { dao.endEvents(e.id, type, now) }
        }
    }

    /** 記録を終える。点が無ければ記録ごと消して null。 */
    suspend fun finish(): Long? {
        withContext(Dispatchers.Main) { openConditions.toList().forEach { setCondition(it, false) } }
        val e = withContext(Dispatchers.Main) { entity } ?: return null
        val count = tracker.acceptedCount
        val done = withContext(Dispatchers.Main) {
            val d = withStats(e).copy(status = TrackEntity.STATUS_FINISHED, endedAt = tracker.lastAccepted?.timeMs ?: System.currentTimeMillis())
            entity = null
            _state.value = null
            _line.value = emptyList()
            liveSmoother = null
            d
        }
        val finished = CompletableDeferred<Long?>()
        writes.trySend {
            val n = dao.pointCount(done.id)
            if (n == 0 && count == 0) {
                dao.deleteTrack(done.id)
                finished.complete(null)
            } else {
                dao.updateTrack(done.copy(pointCount = n))
                // 0.9.0〜 距離はなめらかにした点（設定が ON のとき）、獲得標高は ElevationGain で数え直す（記録の詳細・まとめと同じ値にする）
                runCatching { TrackRepository(db).recompute(done.id) }
                finished.complete(done.id)
            }
        }
        return finished.await()
    }

    private fun withStats(e: TrackEntity) = e.copy(
        distanceM = tracker.distanceM,
        movingMs = tracker.movingMs,
        maxSpeedMps = tracker.maxSpeedMps,
        ascentM = tracker.ascentM,
        pointCount = _line.value.sumOf { it.size },
    )

    private fun saveEntity() {
        val e = entity ?: return
        val snapshot = withStats(e)
        writes.trySend { dao.updateTrack(snapshot) }
    }

    private fun publish() {
        val e = entity ?: run { _state.value = null; return }
        _state.value = RecordingState(
            trackId = e.id,
            name = e.name,
            paused = e.status == TrackEntity.STATUS_PAUSED,
            startedAt = e.startedAt,
            mode = e.mode,
            planId = e.planId,
            distanceM = tracker.distanceM,
            movingMs = tracker.movingMs,
            maxSpeedMps = tracker.maxSpeedMps,
            averageSpeedMps = tracker.averageSpeedMps,
            currentSpeedMps = tracker.currentSpeedMps,
            ascentM = tracker.ascentM,
            autoPaused = tracker.autoPaused,
            pointCount = _line.value.sumOf { it.size },
            lastFixAt = tracker.lastAccepted?.timeMs,
        )
    }
}
