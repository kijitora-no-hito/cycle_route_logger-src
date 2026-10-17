package jp.kjtr.cycleroutelogger.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.kjtr.cycleroutelogger.BuildConfig
import jp.kjtr.cycleroutelogger.CycleApp
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.importer.FileKind
import jp.kjtr.cycleroutelogger.net.ContentKind
import jp.kjtr.cycleroutelogger.net.RemoteContent
import jp.kjtr.cycleroutelogger.net.RemoteGpxException
import jp.kjtr.cycleroutelogger.net.RemoteGpxFetcher
import jp.kjtr.cycleroutelogger.net.RemoteUrls
import jp.kjtr.cycleroutelogger.net.ResolvedUrl
import kotlinx.coroutines.Job
import jp.kjtr.cycleroutelogger.data.Destination
import jp.kjtr.cycleroutelogger.data.PlanRepository
import jp.kjtr.cycleroutelogger.data.RideSelection
import jp.kjtr.cycleroutelogger.data.RouteEntity
import jp.kjtr.cycleroutelogger.data.Settings
import jp.kjtr.cycleroutelogger.data.TrackEntity
import jp.kjtr.cycleroutelogger.data.TrackRepository
import jp.kjtr.cycleroutelogger.data.WaypointEntity
import jp.kjtr.cycleroutelogger.geo.GeoBounds
import jp.kjtr.cycleroutelogger.gpx.GpxData
import jp.kjtr.cycleroutelogger.gpx.GpxException
import jp.kjtr.cycleroutelogger.gpx.GpxParser
import jp.kjtr.cycleroutelogger.gpx.GpxPoint
import jp.kjtr.cycleroutelogger.gpx.GpxWriter
import jp.kjtr.cycleroutelogger.data.TrackEventEntity
import jp.kjtr.cycleroutelogger.record.RecorderStatus
import jp.kjtr.cycleroutelogger.record.RecordingService
import jp.kjtr.cycleroutelogger.record.TrackExport
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.record.StartFailure
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStreamWriter

/** GPX を読んだもの（プランへ入れる前）。[sourceUrl] は URL（QR を含む）から取ったときの URL。 */
data class ParsedGpx(val fileName: String, val data: GpxData, val sourceUrl: String? = null)

/** URL 入力のダイアログを開いている（[targetPlanId] が null なら、取り込むときにプランを聞く）。 */
data class UrlInputRequest(val targetPlanId: Long?, val initial: String = "")

/** 再読み込みで置き換えるもの。 */
data class ReplaceInfo(val url: String, val routes: Int, val waypoints: Int)

/** URL・QR からの取り込みの進み具合（取得中 → 確認 / 失敗）。 */
sealed interface UrlImportUi {
    data class Loading(val url: String, val bytes: Long = 0, val total: Long? = null) : UrlImportUi
    data class Confirm(
        val item: ParsedGpx,
        /** 取り込み元の表示（URL、または「QR コードの GPX」）。 */
        val sourceLabel: String,
        val distanceM: Double,
        val targetPlanId: Long?,
        val replace: ReplaceInfo?,
    ) : UrlImportUi
    data class Failed(val message: String, val retryInput: String?, val targetPlanId: Long?) : UrlImportUi
}

/** 読み込み先のプランを聞いている間の内容。 */
data class PendingImport(val items: List<ParsedGpx>, val suggestedName: String)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as CycleApp
    val settingsStore = app.settings
    val settings: StateFlow<Settings> = app.settings.settings
    val ride = app.settings.ride
    val plans = app.plans
    val tracks = app.tracks
    val session = app.session
    val navigator = app.navigator
    val locationHub = app.locationHub
    /** 更新の確認（DESIGN.md §10）。 */
    val updates = app.updates

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val _nav = Channel<String>(Channel.BUFFERED)
    /** 画面遷移の依頼（route 文字列）。 */
    val navRequests = _nav.receiveAsFlow()

    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport

    private val _unfinished = MutableStateFlow<TrackEntity?>(null)
    /** 記録の途中で終わったもの（起動時に確かめる）。 */
    val unfinished: StateFlow<TrackEntity?> = _unfinished

    private val _fitRequest = MutableStateFlow<GeoBounds?>(null)
    /** 走行画面の地図をこの範囲に合わせる依頼。 */
    val fitRequest: StateFlow<GeoBounds?> = _fitRequest

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)
    private fun say(id: Int, vararg args: Any) {
        _messages.trySend(str(id, *args))
    }

    init {
        viewModelScope.launch {
            if (!session.isActive) _unfinished.value = tracks.unfinished()
        }
    }

    fun updateSettings(f: (Settings) -> Settings) = settingsStore.update(f)

    // ---- モード ----

    fun chooseFree() {
        if (session.isActive) return
        settingsStore.setRide(RideSelection(TrackEntity.MODE_FREE, null))
        navigator.resetRideMemory()
    }

    fun chooseRoute(planId: Long) {
        if (session.isActive) return
        settingsStore.setRide(RideSelection(TrackEntity.MODE_ROUTE, planId))
        navigator.resetRideMemory()
    }

    fun requestFit(b: GeoBounds?) {
        _fitRequest.value = b
    }

    fun consumeFit() {
        _fitRequest.value = null
    }

    // ---- 記録 ----

    fun startRecording() = viewModelScope.launch {
        if (session.isActive) return@launch
        val r = ride.value ?: RideSelection(TrackEntity.MODE_FREE, null).also { settingsStore.setRide(it) }
        val plan = r.planId?.let { plans.planNow(it) }
        val now = System.currentTimeMillis()
        val name = if (r.mode == TrackEntity.MODE_ROUTE && plan != null) {
            str(R.string.track_name_route, Format.dateForName(now), plan.name)
        } else {
            str(R.string.track_name_free, Format.dateForName(now))
        }
        navigator.resetRideMemory()
        session.start(name, r.mode, plan?.id, plan?.name, settings.value.autoPause)
        val failure = startServiceAndWait()
        if (failure != null) {
            // サービスが始まらなければ記録は進まない（点を取り込むのはサービスだけ）。空の記録は消して知らせる。
            session.finish()
            RecordingService.stop(app)
            _recordStartError.value = failure
        }
    }

    private val _recordStartError = MutableStateFlow<StartFailure?>(null)
    /** 記録サービスを始められなかった（走行画面がダイアログで理由と対処を出す）。 */
    val recordStartError: StateFlow<StartFailure?> = _recordStartError

    fun dismissRecordStartError() {
        _recordStartError.value = null
    }

    val recorder get() = app.recorder

    /** 記録サービスを始め、動き出すか失敗が分かるまで待つ（最大 4 秒）。失敗なら理由。 */
    private suspend fun startServiceAndWait(): StartFailure? {
        app.recorder.setFailure(null)
        try {
            RecordingService.start(app)
        } catch (e: Exception) {
            return RecorderStatus.classify(e)
        }
        val ok = withTimeoutOrNull(4_000) {
            combine(app.recorder.serviceRunning, app.recorder.startFailure) { running, f -> running to f }
                .first { it.first || it.second != null }
        }
        return when {
            ok == null -> StartFailure.TIMEOUT
            ok.first -> null
            else -> ok.second
        }
    }

    /** 記録中なのにサービスが止まっている（走行画面の［記録を再開］）。 */
    fun restartRecordingService() = viewModelScope.launch {
        if (!session.isActive || app.recorder.serviceRunning.value) return@launch
        startServiceAndWait()?.let { _recordStartError.value = it }
    }

    fun pauseRecording() = session.pause()
    fun resumeRecording() = session.resume()

    fun finishRecording() = viewModelScope.launch {
        val id = session.finish()
        RecordingService.stop(app)
        if (id == null) say(R.string.msg_no_points) else _nav.trySend("track/$id?finished=true")
    }

    fun continueUnfinished() = viewModelScope.launch {
        val t = _unfinished.value ?: return@launch
        _unfinished.value = null
        if (session.isActive) return@launch
        settingsStore.setRide(RideSelection(t.mode, t.planId))
        if (session.resumeUnfinished(t.id, TrackEventEntity.TYPE_INTERRUPTED)) {
            _nav.trySend("ride")
            startServiceAndWait()?.let { _recordStartError.value = it }
        }
    }

    fun finalizeUnfinished() = viewModelScope.launch {
        val t = _unfinished.value ?: return@launch
        _unfinished.value = null
        val done = tracks.finalizeUnfinished(t.id)
        if (done == null) say(R.string.msg_no_points) else say(R.string.msg_saved_track, done.name)
    }

    // ---- 目的地 ----

    fun setDestination(d: Destination?) = navigator.setDestination(d)
    fun setDestinationWaypoint(w: WaypointEntity) = navigator.setDestination(Destination(w.lat, w.lon, w.name, w.id, w.type))

    // ---- プラン ----

    fun createPlan(name: String, open: Boolean = true) = viewModelScope.launch {
        val id = plans.createPlan(name.ifBlank { str(R.string.plan_default_name) })
        if (open) _nav.trySend("plan/$id")
    }

    fun duplicatePlan(id: Long, name: String) = viewModelScope.launch {
        plans.duplicatePlan(id, str(R.string.plan_copy_name, name))
        say(R.string.msg_duplicated)
    }

    fun deletePlan(id: Long) = viewModelScope.launch {
        if (ride.value?.planId == id && !session.isActive) settingsStore.setRide(null)
        plans.deletePlan(id)
    }

    fun updatePlan(id: Long, name: String? = null, memo: String? = null) = viewModelScope.launch { plans.updatePlan(id, name, memo) }
    fun updateRoute(r: RouteEntity) = viewModelScope.launch { plans.updateRoute(r) }
    fun deleteRoute(r: RouteEntity) = viewModelScope.launch { plans.deleteRoute(r) }
    fun moveRoute(r: RouteEntity, delta: Int) = viewModelScope.launch { plans.moveRoute(r, delta) }

    fun addWaypoint(w: WaypointEntity, thenGo: Boolean = false) = viewModelScope.launch {
        val id = plans.addWaypoint(w)
        if (thenGo) setDestinationWaypoint(w.copy(id = id))
    }

    fun updateWaypoint(w: WaypointEntity) = viewModelScope.launch {
        plans.updateWaypoint(w)
        // 目的地にしていた経由地を動かしたら目的地も動かす
        val d = settingsStore.destination.value
        if (d?.waypointId == w.id) setDestinationWaypoint(w)
    }

    fun deleteWaypoint(w: WaypointEntity) = viewModelScope.launch {
        plans.deleteWaypoint(w)
        if (settingsStore.destination.value?.waypointId == w.id) setDestination(null)
    }

    // ---- GPX の読み込み ----

    /** GPX を読む。[targetPlanId] が null なら、どのプランに入れるかを聞く。 */
    fun importUris(uris: List<Uri>, targetPlanId: Long?) = viewModelScope.launch {
        val parsed = ArrayList<ParsedGpx>()
        for (uri in uris) {
            val name = displayName(uri) ?: "route.gpx"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)?.use { GpxParser().parse(Xml.newPullParser(), it) }
                        ?: throw GpxException(GpxException.Reason.BROKEN)
                }
            }
            result.onSuccess { parsed.add(ParsedGpx(name, it)) }.onFailure { e ->
                val reason = (e as? GpxException)?.reason
                val msg = when (reason) {
                    GpxException.Reason.NOT_GPX -> R.string.err_not_gpx
                    GpxException.Reason.EMPTY -> R.string.err_empty_gpx
                    GpxException.Reason.TOO_LARGE -> R.string.err_too_large
                    else -> R.string.err_broken_gpx
                }
                say(msg, name)
            }
        }
        if (parsed.isEmpty()) return@launch
        if (targetPlanId != null) {
            importInto(targetPlanId, parsed)
        } else {
            val first = parsed.first()
            _pendingImport.value = PendingImport(parsed, first.data.name ?: first.fileName.substringBeforeLast('.'))
        }
    }

    fun confirmImport(planId: Long?, newPlanName: String?) = viewModelScope.launch {
        val p = _pendingImport.value ?: return@launch
        _pendingImport.value = null
        val id = planId ?: plans.createPlan(newPlanName?.ifBlank { null } ?: p.suggestedName)
        importInto(id, p.items)
        _nav.trySend("plan/$id")
    }

    fun cancelImport() {
        _pendingImport.value = null
    }

    private suspend fun importInto(planId: Long, items: List<ParsedGpx>) {
        var routes = 0
        var wps = 0
        for (it in items) {
            val r = plans.importGpx(planId, it.data, it.fileName, it.sourceUrl)
            routes += r.routes
            wps += r.waypoints
            if (r.decimated) say(R.string.msg_decimated, it.fileName)
            if (r.partial) say(R.string.msg_partial, it.fileName)
        }
        say(R.string.msg_imported, routes, wps)
    }

    // ---- URL・QR コードからの読み込み ----

    private val _urlInput = MutableStateFlow<UrlInputRequest?>(null)
    val urlInput: StateFlow<UrlInputRequest?> = _urlInput

    private val _urlImport = MutableStateFlow<UrlImportUi?>(null)
    val urlImport: StateFlow<UrlImportUi?> = _urlImport

    private var urlJob: Job? = null
    private var fetcher: RemoteGpxFetcher? = null

    fun openUrlInput(targetPlanId: Long?, initial: String = "") {
        _urlInput.value = UrlInputRequest(targetPlanId, initial)
    }

    fun closeUrlInput() {
        _urlInput.value = null
    }

    /**
     * 利用者が指定した URL から GPX を取って、確認画面を出す。[replaceUrl] があれば、プラン [targetPlanId] の中の
     * その URL から取り込んだものを置き換える（再読み込み）。通信するのは指定された URL（とそのリダイレクト先）だけ。
     */
    fun importFromUrl(input: String, targetPlanId: Long?, replaceUrl: String? = null) {
        cancelUrlImport()
        val f = RemoteGpxFetcher("CycleRouteLogger/${BuildConfig.VERSION_NAME} (Android)")
        fetcher = f
        val shown = input.trim()
        _urlImport.value = UrlImportUi.Loading(shown)
        urlJob = viewModelScope.launch {
            try {
                val fetched = withContext(Dispatchers.IO) {
                    f.fetch(input) { n, total ->
                        val cur = _urlImport.value
                        if (cur is UrlImportUi.Loading) _urlImport.value = cur.copy(bytes = n, total = total)
                    }
                }
                val data = withContext(Dispatchers.IO) {
                    RemoteContent.gpxStream(fetched.bytes).use { GpxParser().parse(Xml.newPullParser(), it) }
                }
                val src = replaceUrl ?: (RemoteUrls.resolve(input) as? ResolvedUrl.Ok)?.original ?: shown
                val fileName = fetched.fileName ?: data.name?.let { Format.safeFileName(it) + ".gpx" } ?: "route.gpx"
                showConfirm(ParsedGpx(fileName, data, src), src, targetPlanId, replaceUrl)
            } catch (e: RemoteGpxException) {
                _urlImport.value = UrlImportUi.Failed(remoteErrorMessage(e), shown, targetPlanId)
            } catch (e: GpxException) {
                _urlImport.value = UrlImportUi.Failed(gpxErrorMessage(e.reason, str(R.string.url_file_label)), shown, targetPlanId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _urlImport.value = UrlImportUi.Failed(str(R.string.url_err_network), shown, targetPlanId)
            } finally {
                if (fetcher === f) fetcher = null
            }
        }
    }

    /** QR コードの中身: URL なら取りに行き、GPX そのもの（短い GPX）ならそのまま読む。 */
    fun importFromQr(text: String, targetPlanId: Long?) {
        val t = text.trim()
        val bytes = t.toByteArray(Charsets.UTF_8)
        if (RemoteContent.classify(bytes) == ContentKind.GPX) {
            viewModelScope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { GpxParser().parse(Xml.newPullParser(), bytes.inputStream()) } }
                r.onSuccess { data ->
                    val name = data.name?.let { Format.safeFileName(it) + ".gpx" } ?: "qr.gpx"
                    showConfirm(ParsedGpx(name, data, null), str(R.string.url_source_qr_gpx), targetPlanId, null)
                }.onFailure { e ->
                    _urlImport.value = UrlImportUi.Failed(gpxErrorMessage((e as? GpxException)?.reason, str(R.string.url_qr_label)), null, targetPlanId)
                }
            }
            return
        }
        val resolved = RemoteUrls.resolve(t)
        if (resolved is ResolvedUrl.Rejected && resolved.reason == ResolvedUrl.Reason.NOT_URL) {
            _urlImport.value = UrlImportUi.Failed(str(R.string.qr_not_url, t.take(200)), null, targetPlanId)
            return
        }
        importFromUrl(t, targetPlanId)
    }

    /** QR の読み取りが使えない（Play 開発者サービスが無い・読み取り機能を入れられない）。 */
    fun qrUnavailable(targetPlanId: Long?, detail: String?) {
        val msg = str(R.string.qr_unavailable) + (detail?.takeIf { it.isNotBlank() }?.let { "\n\n($it)" } ?: "")
        _urlImport.value = UrlImportUi.Failed(msg, "", targetPlanId)
    }

    /** 取得中の取り消し・確認画面の［やめる］・エラーを閉じる。 */
    fun cancelUrlImport() {
        fetcher?.abort()
        fetcher = null
        urlJob?.cancel()
        urlJob = null
        _urlImport.value = null
    }

    /** 失敗したとき［URL を入力し直す］。 */
    fun retryUrlInput() {
        val f = _urlImport.value as? UrlImportUi.Failed ?: return
        _urlImport.value = null
        openUrlInput(f.targetPlanId, f.retryInput.orEmpty())
    }

    /** プラン [planId] の中の [url] から取り込んだものを、URL から取り直して置き換える。 */
    fun reloadFromUrl(planId: Long, url: String) = importFromUrl(url, planId, replaceUrl = url)

    private suspend fun showConfirm(item: ParsedGpx, label: String, targetPlanId: Long?, replaceUrl: String?) {
        val distance = withContext(Dispatchers.Default) {
            item.data.tracks.sumOf { t -> t.segments.sumOf { s -> Geo.lengthM(s.map { it.latLon() }) } }
        }
        val replace = if (replaceUrl != null && targetPlanId != null) {
            val (r, w) = plans.countFromSource(targetPlanId, replaceUrl)
            ReplaceInfo(replaceUrl, r, w)
        } else null
        _urlImport.value = UrlImportUi.Confirm(item, label, distance, targetPlanId, replace)
    }

    /** 確認画面の［取り込む］。プランが決まっていなければ、ファイルのときと同じく入れるプランを聞く。 */
    fun confirmUrlImport() {
        val c = _urlImport.value as? UrlImportUi.Confirm ?: return
        _urlImport.value = null
        viewModelScope.launch {
            val pid = c.targetPlanId
            when {
                c.replace != null && pid != null -> {
                    val r = plans.replaceFromSource(pid, c.replace.url, c.item.data, c.item.fileName)
                    val d = settingsStore.destination.value
                    if (d?.waypointId != null && d.waypointId in r.removedWaypointIds) setDestination(d.copy(waypointId = null))
                    say(R.string.msg_reloaded, r.routes, r.waypoints)
                    if (c.item.data.decimated) say(R.string.msg_decimated, c.item.fileName)
                    if (c.item.data.partial) say(R.string.msg_partial, c.item.fileName)
                }
                pid != null -> importInto(pid, listOf(c.item))
                else -> _pendingImport.value = PendingImport(listOf(c.item), c.item.data.name ?: c.item.fileName.substringBeforeLast('.'))
            }
        }
    }

    private fun gpxErrorMessage(reason: GpxException.Reason?, name: String): String = str(
        when (reason) {
            GpxException.Reason.NOT_GPX -> R.string.err_not_gpx
            GpxException.Reason.EMPTY -> R.string.err_empty_gpx
            GpxException.Reason.TOO_LARGE -> R.string.err_too_large
            else -> R.string.err_broken_gpx
        },
        name,
    )

    private fun remoteErrorMessage(e: RemoteGpxException): String = when (e.reason) {
        RemoteGpxException.Reason.NOT_URL -> str(R.string.url_err_not_url)
        RemoteGpxException.Reason.NOT_HTTPS -> str(R.string.url_err_not_https)
        RemoteGpxException.Reason.DRIVE_FOLDER -> str(R.string.url_err_drive_folder)
        RemoteGpxException.Reason.GOOGLE_DOCS -> str(R.string.url_err_google_docs)
        RemoteGpxException.Reason.DRIVE_NO_ID -> str(R.string.url_err_drive_no_id)
        RemoteGpxException.Reason.FOLDER -> str(R.string.url_err_folder)
        RemoteGpxException.Reason.REDIRECT_NOT_HTTPS -> str(R.string.url_err_redirect_http)
        RemoteGpxException.Reason.TOO_MANY_REDIRECTS -> str(R.string.url_err_redirects)
        RemoteGpxException.Reason.NEED_SHARING -> str(R.string.url_err_need_sharing)
        RemoteGpxException.Reason.RATE_LIMITED -> str(R.string.url_err_rate_limited)
        RemoteGpxException.Reason.NOT_FOUND -> str(R.string.url_err_not_found)
        RemoteGpxException.Reason.ACCESS_DENIED -> str(R.string.url_err_denied, e.httpCode)
        RemoteGpxException.Reason.HTTP_ERROR -> str(R.string.url_err_http, e.httpCode)
        RemoteGpxException.Reason.NOT_DOWNLOADABLE -> str(R.string.url_err_html)
        RemoteGpxException.Reason.ONEDRIVE_UNSUPPORTED -> str(R.string.url_err_onedrive)
        RemoteGpxException.Reason.NOT_GPX -> str(R.string.url_err_not_gpx)
        RemoteGpxException.Reason.EMPTY -> str(R.string.url_err_empty)
        RemoteGpxException.Reason.TOO_LARGE -> str(R.string.url_err_too_large, RemoteGpxFetcher.MAX_DOWNLOAD_BYTES / (1024 * 1024))
        RemoteGpxException.Reason.TIMEOUT -> str(R.string.url_err_timeout)
        RemoteGpxException.Reason.NETWORK -> str(R.string.url_err_network)
    }

    private fun displayName(uri: Uri): String? =
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    // ---- 書き出し・共有 ----

    fun exportPlan(planId: Long, uri: Uri) = viewModelScope.launch {
        val data = plans.toGpx(planId) ?: return@launch
        writeGpx(uri, data, null)
    }

    /** 記録を GPX に書き出す。[includeWaypoints] ならルートサイクリングで通った経由地を wpt に入れる。[source] = なめらかにした位置 / 記録した位置（0.9.0〜）。 */
    fun exportTrack(
        trackId: Long, uri: Uri, includeWaypoints: Boolean = false, includePhotos: Boolean = false,
        source: jp.kjtr.cycleroutelogger.data.GpxSource = jp.kjtr.cycleroutelogger.data.GpxSource.RECORDED,
    ) = viewModelScope.launch {
        val t = tracks.trackNow(trackId) ?: return@launch
        val data = trackGpx(trackId, includeWaypoints, includePhotos, source) ?: return@launch
        writeGpx(uri, data, t.startedAt)
    }

    /** 記録の GPX（軌跡 = trk、一時停止で trkseg を分ける。任意で通った経由地 = wpt、名前は通った時刻付き）。 */
    suspend fun trackGpx(
        trackId: Long, includeWaypoints: Boolean, includePhotos: Boolean = false,
        source: jp.kjtr.cycleroutelogger.data.GpxSource = jp.kjtr.cycleroutelogger.data.GpxSource.RECORDED,
    ): GpxData? = withContext(Dispatchers.IO) {
        val t = tracks.trackNow(trackId) ?: return@withContext null
        val raw = tracks.points(trackId)
        val pts = withContext(Dispatchers.Default) { TrackRepository.pointsFor(raw, source) }
        val photoWps = if (includePhotos) {
            photos.forTrackNow(trackId).filter { it.lat != null && it.lon != null }.map { ph ->
                jp.kjtr.cycleroutelogger.gpx.GpxWaypoint(ph.lat!!, ph.lon!!, null, ph.takenAt, str(R.string.photo_wpt_name, Format.time(ph.takenAt)), null, "photo", "Scenic Area")
            }
        } else emptyList()
        val wps = if (includeWaypoints && t.planId != null) {
            TrackExport.passed(pts, plans.waypointsNow(t.planId)) { LatLon(it.lat, it.lon) }.map { (w, at) ->
                val g = PlanRepository.waypointToGpx(w)
                val type = jp.kjtr.cycleroutelogger.plan.WaypointType.fromCode(w.type)
                g.copy(name = "${Format.time(at)} " + w.name.ifBlank { str(type.style().label) }, timeMs = at)
            }
        } else emptyList()
        TrackExport.gpx(t, pts, (wps + photoWps).sortedBy { it.timeMs ?: 0L })
    }

    /** ルートサイクリングで通った経由地の数（書き出しの選択肢に出す）。 */
    suspend fun passedWaypointCount(trackId: Long): Int = withContext(Dispatchers.IO) {
        val t = tracks.trackNow(trackId) ?: return@withContext 0
        val pid = t.planId ?: return@withContext 0
        TrackExport.passed(tracks.points(trackId), plans.waypointsNow(pid)) { LatLon(it.lat, it.lon) }.size
    }

    /** 書き出し・共有のファイル名（拡張子なし）。 */
    fun trackFileBase(t: TrackEntity): String =
        Format.safeFileName(TrackExport.fileBaseName(Format.dayStamp(t.startedAt), Format.dateForName(t.startedAt), t.name))

    private suspend fun writeGpx(uri: Uri, data: GpxData, time: Long?) {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                app.contentResolver.openOutputStream(uri, "wt")?.use { os ->
                    OutputStreamWriter(os, Charsets.UTF_8).use { w -> GpxWriter.write(w, data, time) }
                } ?: error("no stream")
            }.isSuccess
        }
        say(if (ok) R.string.msg_exported else R.string.msg_export_failed)
    }

    /** 共有用に GPX を cache/share/ に書き、FileProvider の Uri を返す。 */
    suspend fun shareFile(data: GpxData, baseName: String, time: Long?): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(app.cacheDir, "share").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, Format.safeFileName(baseName) + ".gpx")
            f.outputStream().use { os -> OutputStreamWriter(os, Charsets.UTF_8).use { w -> GpxWriter.write(w, data, time) } }
            FileProvider.getUriForFile(app, app.packageName + ".fileprovider", f)
        }.getOrNull()
    }

    // ---- 記録 → ルート ----

    /**
     * 記録をプランのルートとして保存する。[planId] が null なら新しいプラン [newPlanName]。
     * [toleranceM] で間引き（null = しない）、[dropStops] で止まっていた間の点を除く。
     */
    fun saveTrackAsRoute(
        trackId: Long, planId: Long?, newPlanName: String?, routeName: String, toleranceM: Double?, dropStops: Boolean,
    ) = viewModelScope.launch {
        val t = tracks.trackNow(trackId) ?: return@launch
        // 0.9.0〜 なめらかにした点（設定が ON のとき。地図の線と同じ）
        val pts = tracks.displayPoints(trackId)
        if (pts.isEmpty()) return@launch
        val segs = withContext(Dispatchers.Default) { TrackExport.segments(pts, toleranceM, dropStops) }
        val pid = planId ?: plans.createPlan(newPlanName?.ifBlank { null } ?: t.name)
        val color = PlanRepository.ROUTE_PALETTE[(plans.routesNow(pid).size) % PlanRepository.ROUTE_PALETTE.size]
        plans.insertRoute(pid, routeName.ifBlank { t.name }, color, segs, System.currentTimeMillis(), t.id)
        plans.touchPlan(pid)
        say(R.string.msg_saved_as_route, segs.sumOf { it.size })
        _nav.trySend("plan/$pid")
    }

    // ---- 写真 ----

    val photos = app.photos

    private val _lastCapturedThumb = MutableStateFlow<String?>(null)
    /** カメラ画面の左下に出す、最後に撮った写真の縮小版。 */
    val lastCapturedThumb: StateFlow<String?> = _lastCapturedThumb

    /** アプリ内カメラで撮れた（フォトライブラリに保存済み）。位置は 30 秒以内の測位、無ければ記録の軌跡から。 */
    fun onPhotoCaptured(uri: Uri, takenAt: Long) = viewModelScope.launch {
        val l = locationHub.last.value
        val ageMs = l?.let { (android.os.SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 }
        val fix = l?.takeIf { ageMs != null && ageMs < 30_000 && (!it.hasAccuracy() || it.accuracy <= 50f) }?.let { LatLon(it.latitude, it.longitude) }
        val p = photos.addCaptured(uri, takenAt, session.state.value?.trackId, fix, settings.value.photoExifLocation)
        _lastCapturedThumb.value = p.thumbPath
    }

    /** 記録の詳細の［この時間に撮った写真を追加］（Photo Picker）。 */
    fun importPhotos(trackId: Long, uris: List<Uri>) = viewModelScope.launch {
        val t = tracks.trackNow(trackId) ?: return@launch
        val r = photos.importPicked(uris, t)
        say(R.string.msg_photos_imported, r.added, r.outsideTime + r.noTime, r.duplicate)
        if (r.noLocation > 0) say(R.string.msg_photos_no_location, r.noLocation)
    }

    fun unlinkPhoto(p: jp.kjtr.cycleroutelogger.data.PhotoEntity) = viewModelScope.launch { photos.unlink(p) }

    fun renameTrack(id: Long, name: String) = viewModelScope.launch { tracks.rename(id, name) }
    fun deleteTrack(id: Long) = viewModelScope.launch { tracks.delete(id) }

    // ---- バックアップ ----

    fun exportBackup(uri: Uri) = viewModelScope.launch {
        val r = withContext(Dispatchers.IO) {
            runCatching { app.contentResolver.openOutputStream(uri, "wt")?.use { app.backup.export(it) } }.getOrNull()
        }
        if (r != null) say(R.string.msg_backup_done, r.plans, r.tracks) else say(R.string.msg_export_failed)
    }

    /** 設定の［バックアップを読み込む］で、バックアップではないファイルを選んだときの案内（0.10.2。DESIGN.md §6.1）。 */
    sealed interface BackupNotice {
        /** 記録の取り込みで読めるファイル（Strava の書き出しなど）。［取り込みへ進む］で同じ URI のまま取り込みを始める。 */
        data class Activity(val uri: Uri, val name: String?) : BackupNotice
        /** どちらでもない。 */
        data class Unknown(val name: String?) : BackupNotice
    }

    private val _backupNotice = MutableStateFlow<BackupNotice?>(null)
    val backupNotice: StateFlow<BackupNotice?> = _backupNotice
    fun dismissBackupNotice() { _backupNotice.value = null }

    /** 案内の［取り込みへ進む］: 選んだファイル（同じ URI）のまま取り込みの解析を始める。 */
    fun importAsActivities(uri: Uri) {
        _backupNotice.value = null
        app.activityImport.start(listOf(uri))
    }

    private val _restoring = MutableStateFlow(false)
    /** バックアップを判定・読み込み中（設定のボタンを止める）。 */
    val restoring: StateFlow<Boolean> = _restoring

    /**
     * 設定の［バックアップを読み込む］。先頭を覗いて、このアプリのバックアップでなければ案内を出す（0.10.2）。
     * [checked] = true は判定を飛ばす（取り込みの画面で「バックアップです」と分かったファイル）。
     */
    fun importBackup(uri: Uri, checked: Boolean = false) = viewModelScope.launch {
        if (_restoring.value) return@launch
        _restoring.value = true
        try {
            val name = withContext(Dispatchers.IO) { displayName(uri) }
            if (!checked) {
                val kind = withContext(Dispatchers.IO) { peekKind(uri, name) }
                when (kind) {
                    FileKind.Kind.ACTIVITY -> { _backupNotice.value = BackupNotice.Activity(uri, name); return@launch }
                    FileKind.Kind.UNKNOWN -> { _backupNotice.value = BackupNotice.Unknown(name); return@launch }
                    else -> Unit // BACKUP・決まらない（大きい zip）・読めない → 読み込んでみる
                }
            }
            val tmp = File(app.cacheDir, "restore.zip")
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    tmp.delete()
                    val input = app.contentResolver.openInputStream(uri) ?: return@runCatching null
                    input.use { tmp.outputStream().use { out -> it.copyTo(out) } }
                    app.backup.import(tmp)
                }.getOrNull()
            }
            if (r != null) {
                say(R.string.msg_restore_done, r.plans, r.tracks)
            } else {
                // manifest が無い大きな zip など: コピーした一時ファイルを最後まで見て、取り込みで読めるなら案内する
                val kind = withContext(Dispatchers.IO) {
                    runCatching { tmp.inputStream().use { FileKind.classify(name, it, Int.MAX_VALUE, Long.MAX_VALUE) } }.getOrNull()
                }
                when (kind) {
                    FileKind.Kind.ACTIVITY -> _backupNotice.value = BackupNotice.Activity(uri, name)
                    FileKind.Kind.UNKNOWN -> _backupNotice.value = BackupNotice.Unknown(name)
                    else -> say(R.string.msg_restore_failed) // バックアップらしいが壊れている・開けない
                }
            }
            withContext(Dispatchers.IO) { tmp.delete() }
        } finally {
            _restoring.value = false
        }
    }

    /** 選んだファイルの種類を先頭から覗く（開けなければ null）。 */
    private fun peekKind(uri: Uri, name: String?): FileKind.Kind? = runCatching {
        app.contentResolver.openInputStream(uri)?.use { FileKind.classify(name, it) }
    }.getOrNull()

    fun toast(context: Context, text: String) {
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }
}
